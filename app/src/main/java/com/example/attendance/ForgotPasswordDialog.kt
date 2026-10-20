package com.example.attendance

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * "Forgot password?" on the login page. Step 1: the username; the server e-mails a 6-digit code to the address saved in My Profile.
 * Step 2: the code and the new password. The server checks the code (10 minutes, 5 tries, works once) and sets the password.
 * [onDone] gets the username after the password was changed, so the login page can fill it in.
 */
object ForgotPasswordDialog {
    private const val MIN_LENGTH = 8   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    fun show(activity: AppCompatActivity, presetUser: String, onDone: (String) -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        fun field(label: String, type: Int) = FloatField(activity, label, type, password = (type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)

        val info = TextView(activity).apply {
            text = "Enter your Employee ID. We will e-mail a 6-digit code to the e-mail address saved in your My Profile."
            setTextColor(Pal.c("#6C7293"))
        }
        val etUser = field("Employee ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply { setText(presetUser) }
        val etCode = field("6-digit code from the e-mail", InputType.TYPE_CLASS_NUMBER).apply { visibility = View.GONE }
        val etNew = field("New password (at least $MIN_LENGTH characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { visibility = View.GONE }
        val etNew2 = field("Confirm new password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { visibility = View.GONE }
        val msg = TextView(activity).apply { setTextColor(Pal.c("#DC2626")); setPadding(0, pad / 2, 0, 0) }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(info); addView(etUser); addView(etCode); addView(etNew); addView(etNew2); addView(msg)
        }
        var step = 1
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Reset password")
            .setView(layout)
            .setPositiveButton("Send code", null)          // set below so errors do not close the dialog
            .setNeutralButton("Send a new code", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun sendCode(btn: android.widget.Button) {
            val u = etUser.text.toString().trim()
            if (u.isEmpty()) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Enter your Employee ID"; return }
            btn.isEnabled = false
            msg.setTextColor(Pal.c("#6C7293")); msg.text = "Sending…"
            activity.api(JSONObject().put("action", "resetRequest").put("username", u)) { res, err ->
                btn.isEnabled = true
                if (res != null) {
                    step = 2
                    info.text = res.optString("message")
                    etUser.isEnabled = false
                    etCode.visibility = View.VISIBLE; etNew.visibility = View.VISIBLE; etNew2.visibility = View.VISIBLE
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Change password"
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
                    msg.text = ""
                } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
            }
        }

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.visibility = View.GONE
            neutral.setOnClickListener { sendCode(neutral) }
            positive.setOnClickListener {
                if (step == 1) { sendCode(positive); return@setOnClickListener }
                val code = etCode.text.toString().trim()
                val p1 = etNew.text.toString()
                val p2 = etNew2.text.toString()
                msg.setTextColor(Pal.c("#DC2626"))
                when {
                    code.length != 6 -> msg.text = "Enter the 6-digit code from the e-mail"
                    p1.length < MIN_LENGTH -> msg.text = "Password must be at least $MIN_LENGTH characters"
                    p1 != p2 -> msg.text = "Passwords don't match"
                    else -> {
                        positive.isEnabled = false
                        msg.setTextColor(Pal.c("#6C7293")); msg.text = "Checking the code…"
                        val u = etUser.text.toString().trim()
                        activity.api(JSONObject().put("action", "resetConfirm").put("username", u).put("code", code).put("newPassword", p1)) { res, err ->
                            positive.isEnabled = true
                            if (res != null) { dialog.dismiss(); onDone(u) }
                            else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
