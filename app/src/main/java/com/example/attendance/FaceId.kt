package com.example.attendance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Is this selfie the account owner?" Turns the selfie into a short list of numbers (a face print) with a small face-recognition
 * model (a MobileFaceNet .tflite file placed in app/src/main/assets/face_model.tflite). Everything happens on the phone: ML Kit finds
 * the face, the model makes the numbers, and ONLY the numbers are sent along with the attendance. The server compares them with the
 * person's reference and only FLAGS a difference; it never blocks the attendance.
 *
 * It needs no setup in the code: if the model file is not in the app, or this phone cannot run it, [embed] reports nothing and attendance
 * works exactly as before. The model's input size, layout (NHWC / NCHW) and output size are read from the file itself.
 */
object FaceId {
    const val MODEL = "face_model.tflite"

    /** Result of a face print attempt. [print] is a comma list of numbers; [noFace] means the selfie had no face. Both empty = nothing to report. */
    class Result(val print: String?, val noFace: Boolean)

    fun available(ctx: Context): Boolean = try { ctx.assets.list("")?.contains(MODEL) == true } catch (e: Exception) { false }

    /** Heavy: call from a background thread only. Never throws. */
    fun embed(ctx: Context, photo: Bitmap): Result {
        if (!available(ctx)) return Result(null, false)
        var interpreter: InterpreterApi? = null
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build()
        )
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(photo, 0)), 20, TimeUnit.SECONDS)
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return Result(null, true)

            Tasks.await(TfLite.initialize(ctx), 30, TimeUnit.SECONDS)           // the TensorFlow Lite runtime lives in Google Play services (not in the APK)
            val bytes = ctx.assets.open(MODEL).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).also { it.put(bytes); it.rewind() }
            val options = InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
            val interp = InterpreterApi.create(model, options)
            interpreter = interp

            val inShape = interp.getInputTensor(0).shape()                 // [1, H, W, 3] or [1, 3, H, W]
            val nchw = inShape.size == 4 && inShape[1] == 3 && inShape[3] != 3
            val size = if (nchw) inShape[2] else inShape[1]
            if (inShape.size != 4 || size < 32 || size > 512) return Result(null, false)
            val quantized = interp.getInputTensor(0).dataType() != org.tensorflow.lite.DataType.FLOAT32

            val crop = alignedCrop(photo, face.boundingBox.centerX().toFloat(), face.boundingBox.centerY().toFloat(),
                max(face.boundingBox.width(), face.boundingBox.height()).toFloat(),
                face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { Pair(it.x, it.y) },
                face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { Pair(it.x, it.y) }, size)
            val pixels = IntArray(size * size)
            crop.getPixels(pixels, 0, size, 0, 0, size, size)

            val input = ByteBuffer.allocateDirect(size * size * 3 * (if (quantized) 1 else 4)).order(ByteOrder.nativeOrder())
            fun put(v: Int) { if (quantized) input.put(v.toByte()) else input.putFloat((v - 127.5f) / 128f) }   // the usual MobileFaceNet scaling: -1 .. 1
            if (nchw) { for (c in 0..2) for (p in pixels) put((p shr (16 - 8 * c)) and 255) }
            else for (p in pixels) { put((p shr 16) and 255); put((p shr 8) and 255); put(p and 255) }       // RGB
            input.rewind()

            val dim = interp.getOutputTensor(0).shape().last()
            if (dim < 64 || dim > 1024) return Result(null, false)
            val out = Array(1) { FloatArray(dim) }
            interp.run(input, out)

            var n = 0.0
            for (x in out[0]) n += x * x
            if (n < 1e-9) return Result(null, false)
            val k = 1.0 / sqrt(n)
            return Result(out[0].joinToString(",") { String.format(Locale.US, "%.5f", it * k) }, false)
        } catch (e: Exception) {
            return Result(null, false)                                         // the phone could not do it: attendance goes ahead without a face check
        } finally {
            try { interpreter?.close() } catch (e: Exception) { }
            try { detector.close() } catch (e: Exception) { }
        }
    }

    /** A [size] x [size] picture of the face: levelled by the line between the eyes when both are known, with a little room around the face. */
    private fun alignedCrop(src: Bitmap, cx: Float, cy: Float, side: Float, left: Pair<Float, Float>?, right: Pair<Float, Float>?, size: Int): Bitmap {
        var angle = 0f
        if (left != null && right != null) {
            val a = if (left.first <= right.first) left else right            // the eye that is further left in the picture
            val b = if (left.first <= right.first) right else left
            angle = Math.toDegrees(atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())).toFloat()
            if (angle > 45f || angle < -45f) angle = 0f                        // not believable: leave the picture as it is
        }
        val scale = size / (side * 1.3f)
        val m = Matrix()
        m.postTranslate(-cx, -cy)
        m.postRotate(-angle)                                                   // turn the eye line level
        m.postScale(scale, scale)
        m.postTranslate(size / 2f, size / 2f)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Words for a Face column value ("Match 78", "Mismatch 31", "Enrolled", "No face"), or null when there is nothing to say. */
    fun describe(flag: String): String? = when {
        flag.isBlank() -> null
        flag.startsWith("Match") -> "🙂 Face matches (${flag.removePrefix("Match").trim()}%)"
        flag.startsWith("Mismatch") -> "⚠️ Face does NOT match the usual face (${flag.removePrefix("Mismatch").trim()}%)"
        flag == "Enrolled" -> "🙂 Face saved as the reference"
        flag == "No face" -> "⚠️ No face found in the selfie"
        else -> null
    }

    /** True for the values a manager should look at. */
    fun isWarning(flag: String): Boolean = flag.startsWith("Mismatch") || flag == "No face"
}
