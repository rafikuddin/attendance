package com.example.attendance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * Loads a selfie into an ImageView. A selfie never changes once saved, so after the first download
 * it is kept on the phone (and in memory) and every later view is instant with no server call.
 */
object PhotoLoader {
    private val memory = LruCache<String, Bitmap>(48)

    fun load(activity: AppCompatActivity, session: Session, fileId: String, target: ImageView) {
        target.tag = fileId
        memory.get(fileId)?.let { target.setImageBitmap(it); return }

        val username = session.username
        val password = session.password
        val safeName = fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
        val file = File(activity.cacheDir, "selfie_$safeName.jpg")

        Thread {
            var bmp = decode(file)
            if (bmp == null) {
                try {
                    val res = postJson(
                        JSONObject().put("action", "photo")
                            .put("username", username).put("password", password).put("id", fileId)
                    )
                    if (res.optBoolean("ok")) {
                        file.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                        bmp = decode(file)
                    }
                } catch (e: Exception) {
                    // Leave the initials circle in place; a later refresh will try again.
                }
            }
            val result = bmp
            if (result != null) {
                memory.put(fileId, result)
                activity.runOnUiThread { if (target.tag == fileId) target.setImageBitmap(result) }
            }
        }.start()
    }

    /** Decodes at roughly avatar size so memory stays small. Deletes a corrupt file. */
    private fun decode(f: File): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) f.delete()
        return bmp
    }
}
