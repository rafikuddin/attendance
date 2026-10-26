package com.example.attendance

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Reads the picture of a leave application on the phone (ML Kit text recognition, the model is inside the app) and decides whether it
 * LOOKS like a leave application: enough readable words, at least one of leave / application / apply / subject, and the usual
 * letter words (sir, madam, dear, respectfully, kindly, request, grant, sick, casual, signature …). A selfie, a screenshot, a receipt or a
 * picture of something else has none of this. Only English / Latin text is read: it is a check, not a proof, and the admin can switch it off
 * for everybody with the Script Property LEAVE_ATTACHMENT_CHECK = off.
 */
object LeaveCheck {
    private val strong = listOf("leave", "application", "apply", "subject")
    private val weak = listOf(
        "sir", "madam", "dear", "respect", "kindly", "request", "grant", "sanction", "permission", "sick", "casual", "earn", "absent",
        "faithful", "sincere", "obedient", "yours", "reason", "days", "date", "signature", "approv", "holiday", "medical", "fever",
        "urgent", "family", "thank", "regards", "paternity", "manager", "officer", "from"
    )

    private fun hit(words: List<String>, k: String) = words.any { it == k || (k.length >= 4 && it.startsWith(k)) }

    /** The decision on the recognised text (separate so it can be tested without a picture). */
    fun looksLikeApplication(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.length >= 2 }
        if (words.size < 8) return false
        val strongHits = strong.count { hit(words, it) }
        if (strongHits == 0) return false
        val score = strongHits * 2 + weak.count { hit(words, it) }
        return score >= 4
    }

    /**
     * Calls [done] on the main thread with true (looks like a leave application), false (does not), or null when the phone could not run
     * the check at all (then the picture is accepted, so a phone problem never blocks a real leave).
     */
    fun check(bitmap: Bitmap, done: (Boolean?) -> Unit) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { t -> try { recognizer.close() } catch (e: Exception) { }; done(looksLikeApplication(t.text)) }
                .addOnFailureListener { try { recognizer.close() } catch (e: Exception) { }; done(null) }
        } catch (e: Exception) { done(null) }
    }
}
