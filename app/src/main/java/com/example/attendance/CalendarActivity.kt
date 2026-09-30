package com.example.attendance

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class CalendarActivity : AppCompatActivity() {
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val records = HashMap<String, JSONObject>()
    private val leaveDays = HashMap<String, JSONObject>()
    private val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private lateinit var grid: GridLayout
    private lateinit var tvMonth: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        setupHeader("My Attendance")
        grid = findViewById(R.id.grid)
        tvMonth = findViewById(R.id.tvMonth)
        tvCount = findViewById(R.id.tvCount)
        tvDetail = findViewById(R.id.tvDetail)
        findViewById<TextView>(R.id.btnPrev).setOnClickListener { cal.add(Calendar.MONTH, -1); render() }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { cal.add(Calendar.MONTH, 1); render() }
        render()

        val s = Session(this)
        tvCount.text = "Loading..."
        api(JSONObject().put("action", "history").put("username", s.username).put("password", s.password)) { res, err ->
            if (res == null) { tvCount.text = err; return@api }
            val arr = res.getJSONArray("records")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { records[it.getString("date")] = it }
            res.optJSONArray("leaves")?.let { lv ->
                for (i in 0 until lv.length()) {
                    val l = lv.getJSONObject(i)
                    val c = Calendar.getInstance(); c.time = iso.parse(l.getString("from"))!!
                    val end = iso.parse(l.getString("to"))!!.time
                    var n = 0
                    while (c.timeInMillis <= end && n++ < 120) { leaveDays[iso.format(c.time)] = l; c.add(Calendar.DAY_OF_MONTH, 1) }
                }
            }
            render()
        }
    }

    private fun cell(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#111827"))
        layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0; height = dp(44); setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun circle(fill: String?, stroke: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (fill != null) setColor(Color.parseColor(fill))
        if (stroke) setStroke(dp(2), Color.parseColor("#4F46E5"))
    }

    private fun render() {
        grid.removeAllViews()
        tvMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
        for (d in listOf("S", "M", "T", "W", "T", "F", "S"))
            grid.addView(cell(d).apply { setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#6B7280")) })
        repeat(cal.get(Calendar.DAY_OF_WEEK) - 1) { grid.addView(cell("")) }

        val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH) + 1
        val now = Calendar.getInstance()
        var present = 0; var late = 0; var leave = 0
        for (day in 1..cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val key = String.format(Locale.US, "%04d-%02d-%02d", y, m, day)
            val c = cell(day.toString())
            val isToday = y == now.get(Calendar.YEAR) && m == now.get(Calendar.MONTH) + 1 && day == now.get(Calendar.DAY_OF_MONTH)
            val lv = leaveDays[key]
            when {
                records.containsKey(key) -> {
                    present++
                    val isLate = records[key]?.optBoolean("late") == true
                    if (isLate) late++
                    c.setTextColor(Color.WHITE); c.background = circle(if (isLate) "#EA580C" else "#16A34A", isToday)
                    c.setOnClickListener { show(key) }
                }
                lv != null -> {
                    val approved = lv.optString("status") == "Approved"
                    if (approved) leave++
                    c.setTextColor(if (approved) Color.WHITE else Color.parseColor("#78350F"))
                    c.background = circle(if (approved) "#F59E0B" else "#FDE68A", isToday)
                    c.setOnClickListener { show(key) }
                }
                isToday -> c.background = circle(null, true)
            }
            if (isToday) c.setTypeface(null, Typeface.BOLD)
            grid.addView(c)
        }
        tvCount.text = "This month: $present present" + (if (late > 0) " ($late late)" else "") + " · $leave leave day(s)      Total present: ${records.size}"
    }

    private fun show(key: String) {
        val r = records[key]
        val lv = leaveDays[key]
        tvDetail.text = when {
            r != null -> "${if (r.optBoolean("late")) "⏰ Late Present" else "✅ Present"} · $key\nTime: ${r.optString("time")}\nLocation: ${r.optString("lat")}, ${r.optString("lng")}\n${r.optString("address")}"
            lv != null -> "🌴 ${lv.optString("type")} (${lv.optString("status")})\n${lv.optString("from")} → ${lv.optString("to")} · ${lv.optString("days")} day(s)\n${lv.optString("reason")}"
            else -> ""
        }
    }
}
