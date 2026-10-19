package com.example.attendance

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

/**
 * Create account, in three steps on one page: 1 details (name, Employee ID, e-mail, password), 2 the 6-digit code that was e-mailed,
 * 3 "request sent" (an admin has to approve it; nobody can sign in before that).
 */
class SignupActivity : AppCompatActivity() {
    private companion object { const val MIN_LENGTH = 8 }   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    private var step = 1
    private var timer: CountDownTimer? = null

    private lateinit var etName: TextInputEditText
    private lateinit var etId: TextInputEditText
    private lateinit var etMail: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etPass2: TextInputEditText
    private lateinit var etCode: TextInputEditText
    private lateinit var btnSend: Button
    private lateinit var btnConfirm: Button
    private lateinit var tvErr1: TextView
    private lateinit var tvErr2: TextView
    private lateinit var tvResend: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        etName = findViewById(R.id.suName); etId = findViewById(R.id.suId); etMail = findViewById(R.id.suMail)
        etPass = findViewById(R.id.suPass); etPass2 = findViewById(R.id.suPass2); etCode = findViewById(R.id.suCode)
        btnSend = findViewById(R.id.btnSuSend); btnConfirm = findViewById(R.id.btnSuConfirm)
        tvErr1 = findViewById(R.id.tvSuError); tvErr2 = findViewById(R.id.tvSuError2); tvResend = findViewById(R.id.tvSuResend)

        btnSend.setOnClickListener { sendCode(false) }
        btnConfirm.setOnClickListener { confirm() }
        tvResend.setOnClickListener { if (tvResend.isEnabled) sendCode(true) }
        findViewById<View>(R.id.tvSuBack).setOnClickListener { setStep(1) }
        findViewById<View>(R.id.tvSuLogin).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSuBack).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step == 2) setStep(1) else finish() }
        })
        setStep(1)
    }

    override fun onDestroy() { timer?.cancel(); super.onDestroy() }

    private fun pill(v: TextView, active: Boolean, done: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = 40f * resources.displayMetrics.density
            setColor(if (active) Pal.c("#FFFFFF") else if (done) Pal.c("#33FFFFFF") else Pal.c("#1AFFFFFF"))
        }
        v.setTextColor(if (active) Pal.c("#1E2247") else Pal.c("#CCFFFFFF"))
    }

    private fun setStep(s: Int) {
        step = s
        findViewById<View>(R.id.suCard1).visibility = if (s == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.suCard2).visibility = if (s == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.suCard3).visibility = if (s == 3) View.VISIBLE else View.GONE
        pill(findViewById(R.id.suStep1), s == 1, s > 1)
        pill(findViewById(R.id.suStep2), s == 2, s > 2)
        pill(findViewById(R.id.suStep3), s == 3, false)
        findViewById<TextView>(R.id.suSub).text = when (s) { 1 -> "Your details"; 2 -> "Confirm your e-mail"; else -> "All done" }
        tvErr1.text = ""; tvErr2.text = ""
    }

    private fun sendCode(again: Boolean) {
        val err = if (again) tvErr2 else tvErr1
        err.setTextColor(Pal.c("#DC2626"))
        val n = etName.text.toString().trim()
        val id = etId.text.toString().trim()
        val m = etMail.text.toString().trim()
        val p1 = etPass.text.toString()
        when {
            n.length < 2 -> { err.text = "Enter your full name"; return }
            id.length < 3 -> { err.text = "Enter your Employee ID (at least 3 characters)"; return }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(m).matches() -> { err.text = "Enter a valid e-mail address"; return }
            p1.length < MIN_LENGTH -> { err.text = "Password must be at least $MIN_LENGTH characters"; return }
            p1 != etPass2.text.toString() -> { err.text = "Passwords don't match"; return }
        }
        btnSend.isEnabled = false; tvResend.isEnabled = false
        err.setTextColor(Pal.c("#6C7293")); err.text = "Sending the code…"
        api(JSONObject().put("action", "signupStart").put("name", n).put("username", id).put("email", m).put("password", p1)) { res, e ->
            btnSend.isEnabled = true
            if (res != null) {
                findViewById<TextView>(R.id.tvSuSentTo).text = "We sent a 6-digit code to\n$m\nIt works for 10 minutes."
                etCode.setText("")
                setStep(2)
                startResendTimer()
                etCode.requestFocus()
            } else {
                tvResend.isEnabled = true
                err.setTextColor(Pal.c("#DC2626")); err.text = e
            }
        }
    }

    private fun startResendTimer() {
        timer?.cancel()
        tvResend.isEnabled = false
        timer = object : CountDownTimer(30000, 1000) {
            override fun onTick(ms: Long) { tvResend.text = "Send a new code in ${ms / 1000 + 1}s"; tvResend.alpha = 0.5f }
            override fun onFinish() { tvResend.text = "Send a new code"; tvResend.alpha = 1f; tvResend.isEnabled = true }
        }.start()
    }

    private fun confirm() {
        tvErr2.setTextColor(Pal.c("#DC2626"))
        val code = etCode.text.toString().trim()
        if (code.length != 6) { tvErr2.text = "Enter the 6-digit code from the e-mail"; return }
        btnConfirm.isEnabled = false
        tvErr2.setTextColor(Pal.c("#6C7293")); tvErr2.text = "Checking the code…"
        api(JSONObject().put("action", "signupConfirm").put("username", etId.text.toString().trim()).put("code", code)) { res, e ->
            btnConfirm.isEnabled = true
            if (res != null) {
                timer?.cancel()
                findViewById<TextView>(R.id.tvSuDone).text = res.optString("message")
                setStep(3)
            } else { tvErr2.setTextColor(Pal.c("#DC2626")); tvErr2.text = e }
        }
    }
}
