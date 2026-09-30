package com.example.attendance

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

object ChangePasswordDialog {
    fun show(activity: AppCompatActivity, session: Session) {
        val ctx: Context = activity
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        val newPass = EditText(ctx).apply {
            hint = "New password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmPass = EditText(ctx).apply {
            hint = "Confirm new password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val error = TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#DC2626")) }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(newPass)
            addView(confirmPass)
            addView(error)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Change Password")
            .setView(layout)
            .setPositiveButton("Change", null)   // set below so we can block auto-dismiss on error
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = newPass.text.toString()
                val p2 = confirmPass.text.toString()
                when {
                    p1.length < 4 -> error.text = "Password must be at least 4 characters"
                    p1 != p2 -> error.text = "Passwords don't match"
                    else -> {
                        error.text = "Updating..."
                        val body = JSONObject().put("action", "changePassword")
                            .put("username", session.username).put("password", session.password)
                            .put("newPassword", p1)
                        activity.api(body) { res, err ->
                            if (res != null) {
                                session.password = p1
                                Toast.makeText(ctx, "Password changed", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            } else error.text = err
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
