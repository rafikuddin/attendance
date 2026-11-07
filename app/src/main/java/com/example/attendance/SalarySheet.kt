package com.example.attendance

import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The "💰 Salary sheet" card of My Reports and of the Admin Reports tab: ONE tap makes one Excel file for a whole pay period
 * (26th to 25th), one row per person: Present, Late, Leave by type, Absent, Day Off, Pending, Night Holds and, for drivers, the km driven.
 * The server builds the file (kind = "salary") and decides whose rows may be in it.
 */
object SalarySheet {
    /** "26 Oct 2026 – 25 Nov 2026" for this pay period, or the one before it. */
    fun periodText(previous: Boolean): String {
        val show = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val start = Calendar.getInstance().apply {
            if (get(Calendar.DAY_OF_MONTH) < 26) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 26)
            if (previous) add(Calendar.MONTH, -1)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 25) }
        return show.format(start.time) + " – " + show.format(end.time)
    }

    /**
     * [action] is "myReport" (the person, or a manager's team when [who] = "team") or "adminReport" (everybody; [who] = null).
     * [sub] says whose rows the file holds. [onReady] gets the saved file and the server's answer (users, days, from, to).
     */
    fun card(a: AppCompatActivity, action: String, who: String?, sub: String, onReady: (File, JSONObject) -> Unit): View {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        fun tv(t: String, size: Float, color: String, bold: Boolean) = TextView(a).apply {
            this.text = t; textSize = size; setTextColor(Pal.c(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(tv("💰  Salary sheet", 18f, "#3E1D16", true))
        col.addView(tv("One tap, one Excel file for a whole pay period: Present, Late, Leave (by type), Absent, Day Off, Night holds and, for drivers, the km driven. $sub", 13f, "#8A5F52", false).apply { setPadding(0, dp(4), 0, 0) })
        val msg = tv("", 14f, "#8A5F52", false).apply { setPadding(0, dp(8), 0, 0) }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val buttons = ArrayList<MaterialButton>()
        fun make(period: String) {
            buttons.forEach { it.isEnabled = false }
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Preparing the salary sheet… this can take a few seconds."
            val body = JSONObject().put("action", action).put("kind", "salary").put("period", period)
            if (who != null) body.put("who", who)
            a.api(body) { res, err ->
                buttons.forEach { it.isEnabled = true }
                if (res == null) { msg.setTextColor(Pal.c("#DC2626")); msg.text = err ?: "Could not prepare the file."; return@api }
                try {
                    val dir = File(a.cacheDir, "reports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, res.optString("name", "salary_sheet.xlsx"))
                    f.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                    msg.text = ""
                    onReady(f, res)
                } catch (e: Exception) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Could not prepare the file." }
            }
        }
        row.addView(MaterialButton(a).apply {
            text = "This pay period"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { make("current") }
        }.also { buttons.add(it) })
        row.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Last pay period"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
            setOnClickListener { make("previous") }
        }.also { buttons.add(it) })
        col.addView(row)
        col.addView(tv("This: " + periodText(false) + "  (counted up to today)\nLast: " + periodText(true), 12f, "#8A5F52", false).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(msg)
        return MaterialCardView(a).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }
}
