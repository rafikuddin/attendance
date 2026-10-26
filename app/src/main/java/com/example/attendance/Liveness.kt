package com.example.attendance

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * "Are you really there?" check that runs on the live camera picture BEFORE the Capture Selfie button appears.
 * One face must be in view and do two random things in a row (blink, turn the head to one side, smile). A photo or a still
 * picture cannot do that. It runs on the phone with ML Kit from Google Play services (the model is not inside the APK; no picture leaves the phone for it), and it keeps watching until
 * the selfie is taken: the Capture button only works while a face is still in view.
 *
 * It is a phone-side check: it stops casual cheating (holding up a photo), it is not proof against a modified app.
 * If the phone cannot run it at all, [onUnavailable] is called and attendance carries on without it, so nobody is locked out.
 */
class Liveness(
    private val onPrompt: (String) -> Unit,
    private val onPassed: () -> Unit,
    private val onUnavailable: () -> Unit
) : ImageAnalysis.Analyzer {
    private enum class Step { BLINK, TURN, SMILE }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.3f)
            .build()
    )
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    @Volatile var passed = false
        private set
    @Volatile var unavailable = false
        private set
    @Volatile private var lastFaceAt = 0L

    // everything below is used on the main thread only (ML Kit answers there)
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0
    private var stage = 0
    private var okFrames = 0
    private var errors = 0
    private var round = 0
    private var prefix = ""
    private var shown = ""

    /** True while a face has been in view a moment ago (or when the check is not available on this phone). */
    fun faceRecent(): Boolean = unavailable || SystemClock.elapsedRealtime() - lastFaceAt < 2500

    /** Starts again with two new random things to do. Call on the main thread. */
    fun restart(note: String = "") {
        if (closed) return
        passed = false
        unavailable = false
        steps = Step.values().toList().shuffled().take(2)
        stepIndex = 0; stage = 0; errors = 0; okFrames = 0
        round++
        val mine = round
        prefix = note; shown = ""
        showPrompt(prefix + promptText())
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (mine == round && !passed && !unavailable && okFrames == 0) giveUp() }, 8000)      // the detector never answered
        armTimeout()
    }

    private fun armTimeout() {
        val mine = round; val idx = stepIndex
        main.postDelayed({
            if (mine == round && idx == stepIndex && !passed && !unavailable && !closed) {
                restart("⏱ Time is up. Let's try again.\n")
            }
        }, 15000)
    }

    fun close() { closed = true; main.removeCallbacksAndMessages(null); try { detector.close() } catch (e: Exception) { } }

    private fun giveUp() {
        if (unavailable || closed) return
        unavailable = true; passed = false
        main.removeCallbacksAndMessages(null)
        onUnavailable()
    }

    private fun showPrompt(t: String) { if (!closed && t != shown) { shown = t; onPrompt(t) } }

    private fun promptText(): String {
        if (steps.isEmpty()) return "🙂 Look at the camera"
        val what = when (steps[stepIndex]) {
            Step.BLINK -> "👁  Blink your eyes"
            Step.TURN -> "↔️  Slowly turn your head to one side"
            Step.SMILE -> "😀  Give a big smile"
        }
        return "Live check ${stepIndex + 1} of ${steps.size}\n$what"
    }

    override fun analyze(image: ImageProxy) {
        if (closed || unavailable) { image.close(); return }
        val bitmap = try { image.toBitmap() } catch (e: Exception) { null }
        if (bitmap == null) { image.close(); main.post { failed() }; return }
        try {
            detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, image.imageInfo.rotationDegrees))
                .addOnSuccessListener { faces -> handle(faces) }
                .addOnFailureListener { failed() }
                .addOnCompleteListener { image.close() }
        } catch (e: Exception) { image.close(); main.post { failed() } }
    }

    private fun failed() {
        errors++
        if (errors >= 5 && okFrames == 0) giveUp()
    }

    private fun handle(faces: List<Face>) {
        if (closed || unavailable) return
        okFrames++; errors = 0
        val now = SystemClock.elapsedRealtime()
        if (faces.isEmpty()) { if (!passed && steps.isNotEmpty() && now - lastFaceAt > 1500) showPrompt("🙂 Show your face to the camera"); return }
        if (faces.size > 1) { lastFaceAt = 0L; if (!passed) showPrompt("👥 Only one person, please"); return }
        lastFaceAt = now
        if (passed || steps.isEmpty()) return
        val f = faces[0]
        val done = when (steps[stepIndex]) {
            Step.BLINK -> {
                val l = f.leftEyeOpenProbability; val r = f.rightEyeOpenProbability
                if (l == null || r == null) false else {
                    val open = l > 0.7f && r > 0.7f
                    val shut = l < 0.35f && r < 0.35f
                    when {
                        stage == 0 && open -> { stage = 1; false }
                        stage == 1 && shut -> { stage = 2; false }
                        stage == 2 && open -> true
                        else -> false
                    }
                }
            }
            Step.TURN -> {
                val y = Math.abs(f.headEulerAngleY)
                when {
                    stage == 0 && y < 12f -> { stage = 1; false }
                    stage == 1 && y > 22f -> true
                    else -> false
                }
            }
            Step.SMILE -> {
                val s = f.smilingProbability
                if (s == null) false else when {
                    stage == 0 && s < 0.3f -> { stage = 1; false }
                    stage == 1 && s > 0.75f -> true
                    else -> false
                }
            }
        }
        if (!done) { showPrompt(prefix + promptText()); return }
        stepIndex++; stage = 0
        if (stepIndex >= steps.size) { passed = true; main.removeCallbacksAndMessages(null); onPassed() }
        else { prefix = "✅ Good!\n"; showPrompt(prefix + promptText()); armTimeout() }
    }
}
