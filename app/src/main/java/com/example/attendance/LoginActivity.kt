package com.example.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.util.Locale

class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MESSAGE = "msg"
    }

    // Where the phone is when somebody signs in. The server uses it ONLY in the "repeated wrong passwords" e-mail (and does not save it).
    private var afterPermission: (() -> Unit)? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterPermission; afterPermission = null; next?.invoke()
    }

    /** Best effort and quick (at most about 3 seconds): the last known position and its address, or nothing. */
    private fun whereAmI(done: (Location?, String) -> Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        val prefs = getSharedPreferences("login_location", MODE_PRIVATE)
        if (!granted) {
            if (prefs.getBoolean("asked", false)) { done(null, ""); return }          // ask only once; a "no" is respected
            prefs.edit().putBoolean("asked", true).apply()
            afterPermission = { whereAmI(done) }
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            if (l == null) { done(null, ""); return }
            Thread {
                val address = try {
                    @Suppress("DEPRECATION")
                    Geocoder(this, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0) ?: ""
                } catch (e: Exception) { "" }
                runOnUiThread { done(l, address) }
            }.start()
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 3000)
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { finish(it) }
                .addOnFailureListener { finish(null) }
        } catch (e: SecurityException) { finish(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(this)
        if (session.loggedIn) { openMain(); return }
        setContentView(R.layout.activity_login)

        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val btn = findViewById<Button>(R.id.btnLogin)
        val tvErr = findViewById<TextView>(R.id.tvError)
        val cbRemember = findViewById<CheckBox>(R.id.cbRemember)
        findViewById<TextView>(R.id.tvVersion).text = "Version " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
        intent.getStringExtra(EXTRA_MESSAGE)?.let { tvErr.text = it }

        findViewById<TextView>(R.id.tvSignup).setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
        findViewById<TextView>(R.id.tvForgot).setOnClickListener {
            ForgotPasswordDialog.show(this, etUser.text.toString().trim()) { u ->
                etUser.setText(u); etPass.setText("")
                tvErr.setTextColor(Pal.c("#166534")); tvErr.text = "Your password was changed. Sign in with the new password."
            }
        }
        btn.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) { tvErr.text = "Enter Employee ID and password"; return@setOnClickListener }
            btn.isEnabled = false
            tvErr.setTextColor(Pal.c("#DC2626"))
            tvErr.text = "Signing in..."
            // The password is sent only here, once. The server answers with a token, which api() saves; the password is not kept.
            whereAmI { loc, address ->
                val body = JSONObject().put("action", "login").put("username", u).put("password", p).put("remember", cbRemember.isChecked)
                if (loc != null) body.put("lat", loc.latitude).put("lng", loc.longitude).put("address", address)
                api(body) { res, err ->
                    btn.isEnabled = true
                    etPass.setText("")
                    if (res != null) {
                        session.username = u
                        session.name = res.optString("name", u)
                        Access.remember(this, res)
                        session.isManager = res.optBoolean("isManager")
                        AdminFlag.set(this, res.optBoolean("isAdmin"))
                        session.mustChangePassword = res.optBoolean("weakPassword")
                        NoticePopup.arm()                      // show the notices pop-up on Home after every sign-in
                        openMain()
                    } else tvErr.text = err
                }
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
