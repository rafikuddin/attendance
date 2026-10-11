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
    private const val MIN_LENGTH = 8   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    fun show(activity: AppCompatActivity, session: Session) {
        val ctx: Context = activity
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        fun field(label: String) = EditText(ctx).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val currentPass = field("Current password")
        val newPass = field("New password (at least $MIN_LENGTH characters)")
        val confirmPass = field("Confirm new password")
        val error = TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#DC2626")) }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(currentPass)
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
                val cur = currentPass.text.toString()
                val p1 = newPass.text.toString()
                val p2 = confirmPass.text.toString()
                when {
                    cur.isEmpty() -> error.text = "Enter your current password"
                    p1.length < MIN_LENGTH -> error.text = "Password must be at least $MIN_LENGTH characters"
                    p1 != p2 -> error.text = "Passwords don't match"
                    else -> {
                        error.text = "Updating..."
                        val body = JSONObject().put("action", "changePassword")
                            .put("currentPassword", cur).put("newPassword", p1)
                        // The server answers with a fresh token (the old one stops working); api() saves it.
                        activity.api(body) { res, err ->
                            if (res != null) {
                                session.mustChangePassword = false
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
