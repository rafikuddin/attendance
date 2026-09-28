package com.example.attendance

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    // Paste the Web app URL you get after deploying the Google Apps Script (Code.gs)
    const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbxK4vyNRX0J9JrQ-k4-y4Xr_SpYa72vhIGZ-MfHIGTtTniTBFPIC6h8YZ0xcg7r58As/exec"
}

class Session(ctx: Context) {
    private val p = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    var username: String
        get() = p.getString("u", "") ?: ""
        set(v) { p.edit().putString("u", v).apply() }
    var password: String
        get() = p.getString("p", "") ?: ""
        set(v) { p.edit().putString("p", v).apply() }
    var name: String
        get() = p.getString("n", "") ?: ""
        set(v) { p.edit().putString("n", v).apply() }
    val loggedIn: Boolean get() = username.isNotEmpty()
    fun clear() { p.edit().clear().apply() }
}

/** Calls the Google Apps Script backend on a background thread; done() runs on the UI thread. */
fun AppCompatActivity.api(body: JSONObject, done: (JSONObject?, String?) -> Unit) {
    Thread {
        var result: JSONObject? = null
        var error: String? = null
        try {
            val c = URL(Config.SCRIPT_URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
            val json = JSONObject(stream.bufferedReader().readText())
            if (json.optBoolean("ok")) result = json else error = json.optString("error", "Request failed")
        } catch (e: Exception) {
            error = "Network or server error. Check internet and the script URL."
        }
        runOnUiThread { done(result, error) }
    }.start()
}
