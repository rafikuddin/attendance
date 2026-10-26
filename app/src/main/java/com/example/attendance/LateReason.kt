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
