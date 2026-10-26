package com.example.attendance

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * The "Late reason" line under today's status. It only shows when today's attendance was recorded as late: tap it to write why
 * (or change the text; the same day only). The reason is shown to line managers in Team and to admins and managers in the calendar.
 */
object LateReason {
    private var asking = false

    /**
     * The compulsory pop-up: it cannot be closed (no Cancel, back does nothing) until a reason of at least 3 characters is saved.
     * Shown right after a late attendance, and again on Home / Attendance while today's late reason is still missing.
     */
    fun require(act: AppCompatActivity, onSaved: (String) -> Unit) {
        if (asking || act.isFinishing || act.isDestroyed) return
        asking = true
        val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        val et = android.widget.EditText(act).apply {
            hint = "Why were you late?"; minLines = 2; maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        col.addView(et)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(act).setTitle("⏰ You were late today")
            .setMessage("Your attendance was recorded as Late Present. Please write the reason. This is required.")
            .setView(col).setCancelable(false).setPositiveButton("Save reason", null).create()
        dialog.setOnDismissListener { asking = false }
        dialog.show()
        val ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            val v = et.text.toString().trim()
            if (v.length < 3) { et.error = "Please write the reason (at least 3 characters)"; return@setOnClickListener }
            ok.isEnabled = false
            act.api(JSONObject().put("action", "lateReason").put("reason", v)) { res, err ->
                if (res == null) { ok.isEnabled = true; act.oToast(err ?: "Could not save the reason. Please try again."); return@api }
                val me = Session(act).username
                ResponseCache.remove(act, ResponseCache.homeKey(me), ResponseCache.calKey(me))
                dialog.dismiss()
                act.oToast("Reason saved.")
                onSaved(res.optString("reason", v))
            }
        }
    }

    fun bind(act: AppCompatActivity, tv: TextView, record: JSONObject?) {
        if (record == null || !record.optBoolean("late")) { tv.visibility = View.GONE; return }
        val reason = record.optString("lateReason")
        tv.visibility = View.VISIBLE
        tv.text = if (reason.isEmpty()) "📝  Add late reason" else "📝  Late reason: $reason  ✎"
        tv.setTextColor(Pal.c(if (reason.isEmpty()) "#B45309" else "#6B352A"))
        tv.setOnClickListener {
            act.askText("Late reason", "Why were you late?", reason, multiline = true, message = "Your line manager can see this.") { v ->
                if (v.length < 3) { act.oToast("Please write the reason (at least 3 characters)."); return@askText }
                act.api(JSONObject().put("action", "lateReason").put("reason", v)) { res, err ->
                    if (res == null) { act.oToast(err ?: "Could not save the reason."); return@api }
                    record.put("lateReason", res.optString("reason", v))
                    val me = Session(act).username
                    ResponseCache.remove(act, ResponseCache.homeKey(me), ResponseCache.calKey(me))
                    act.oToast("Saved.")
                    bind(act, tv, record)
                }
            }
        }
    }
}
