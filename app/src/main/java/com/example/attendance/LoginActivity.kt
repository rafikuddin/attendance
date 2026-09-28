package com.example.attendance

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(this)
        if (session.loggedIn) { openMain(); return }
        setContentView(R.layout.activity_login)

        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val btn = findViewById<Button>(R.id.btnLogin)
        val tvErr = findViewById<TextView>(R.id.tvError)

        btn.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) { tvErr.text = "Enter username and password"; return@setOnClickListener }
            btn.isEnabled = false
            tvErr.text = "Signing in..."
            api(JSONObject().put("action", "login").put("username", u).put("password", p)) { res, err ->
                btn.isEnabled = true
                if (res != null) {
                    session.username = u
                    session.password = p
                    session.name = res.optString("name", u)
                    openMain()
                } else tvErr.text = err
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
