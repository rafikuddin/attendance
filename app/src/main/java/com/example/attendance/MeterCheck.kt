package com.example.attendance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * The text-to-number part of the car meter check. It is plain Kotlin (no Android), so it can be tested on its own.
 * A km reading is a run of 4 to 9 digits ("123456", "123,456", "012345 km", "ODO 87654"). A decimal point ends a number, so the tenths of
 * "12345.6" are not glued on. A few letters the reader often mistakes for digits (O for 0, I or l for 1) are fixed inside number-like words.
 */
object MeterReading {
    private val thousands = Regex("(?<=\\d)[,'’](?=\\d{3}(?!\\d))")
    private val digitRun = Regex("\\d+")

    /** The biggest km-like number in the text (digits only), or "" when there is none. */
    fun readKm(text: String): String {
        var best = ""
        for (raw in text.split('\n')) {
            val line = fixLookAlikes(raw).replace(thousands, "")
            for (m in digitRun.findAll(line)) {
                val d = m.value
                if (d.length in 4..9 && d.length > best.length) best = d
            }
        }
        return best
    }

    /** "12O456" -> "120456", "1I23" -> "1123": only inside a word of 4+ characters that is made of digits, O, o, I and l and has 3+ real digits. */
    private fun fixLookAlikes(line: String): String =
        line.split(' ').joinToString(" ") { word ->
            if (word.length >= 4 && word.all { it in "0123456789OoIl" } && word.count { it.isDigit() } >= 3)
                word.map { c -> when (c) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; else -> c } }.joinToString("")
            else word
        }
}

/**
 * Reads the picture of a car meter (the km counter) on the phone with ML Kit text recognition from Google Play services (the model is not inside
 * the APK; it is the same reader as the leave-application check) and says which km number it found. A picture where no number of 4 to 9 digits can
 * be read is not accepted: a driver's Submit button only shows after this check. If the first look finds nothing, it looks once more at a
 * grey, higher-contrast copy of the picture (faint or glowing digits read better that way).
 */
object MeterCheck {
    /** [ran] = the phone could run the reader at all (false: no Google Play services model yet, offline first use ...); [km] = the number read, "" when none. */
    class Reading(val ran: Boolean, val km: String)

    /** Calls [done] on the main thread. */
    fun check(bitmap: Bitmap, done: (Reading) -> Unit) {
        recognise(bitmap) { first ->
            if (first == null) { done(Reading(false, "")); return@recognise }
            val km = MeterReading.readKm(first)
            if (km.isNotEmpty()) { done(Reading(true, km)); return@recognise }
            Thread {
                val sharper = enhance(bitmap)
                Handler(Looper.getMainLooper()).post {
                    if (sharper == null) done(Reading(true, ""))
                    else recognise(sharper) { second -> done(Reading(true, if (second == null) "" else MeterReading.readKm(second))) }
                }
            }.start()
        }
    }

    /** Runs the text reader; [onText] gets the recognised text, or null when the phone could not run it. Always on the main thread. */
    private fun recognise(bitmap: Bitmap, onText: (String?) -> Unit) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { t -> try { recognizer.close() } catch (e: Exception) { }; onText(t.text) }
                .addOnFailureListener { try { recognizer.close() } catch (e: Exception) { }; onText(null) }
        } catch (e: Exception) { onText(null) }
    }

    /** A grey copy with stronger contrast (null if the phone is out of memory). */
    private fun enhance(src: Bitmap): Bitmap? = try {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val grey = ColorMatrix().apply { setSaturation(0f) }
        val k = 1.8f
        val shift = (-0.5f * k + 0.5f) * 255f
        grey.postConcat(ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, shift,
            0f, k, 0f, 0f, shift,
            0f, 0f, k, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(grey) })
        out
    } catch (e: Throwable) { null }
}
