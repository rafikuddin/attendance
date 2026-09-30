package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Home dashboard */
class MainActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var tvStatus: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvLeaveBal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_home)
        tvStatus = findViewById(R.id.tvStatus)
        tvMonth = findViewById(R.id.tvMonth)
        tvLeaveBal = findViewById(R.id.tvLeaveBal)
        findViewById<TextView>(R.id.tvName).text = session.name
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.tvLogout).setOnClickListener { session.clear(); goLogin() }
        findViewById<android.view.View>(R.id.tileAttendance).setOnClickListener { open(AttendanceActivity::class.java) }
        findViewById<android.view.View>(R.id.tileLeave).setOnClickListener { open(LeaveActivity::class.java) }
        findViewById<android.view.View>(R.id.tileCalendar).setOnClickListener { open(CalendarActivity::class.java) }
        findViewById<android.view.View>(R.id.tileTeam).setOnClickListener { open(TeamActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        if (session.loggedIn) loadStatus()
    }

    private fun open(c: Class<*>) = startActivity(Intent(this, c))

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun loadStatus() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        api(JSONObject().put("action", "history").put("username", session.username).put("password", session.password)) { res, err ->
            if (res == null) { tvStatus.text = "Offline"; tvMonth.text = err ?: ""; return@api }
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            val recs = res.getJSONArray("records")
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late") }
                if (d.startsWith(today.substring(0, 7))) present++
            }
            var leaveType: String? = null
            res.optJSONArray("leaves")?.let { lv ->
                for (i in 0 until lv.length()) {
                    val l = lv.getJSONObject(i)
                    if (l.optString("status") == "Approved" && today >= l.getString("from") && today <= l.getString("to"))
                        leaveType = l.optString("type")
                }
            }
            when {
                todayTime != null && todayLate -> { tvStatus.text = "⏰ Late Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                todayTime != null -> { tvStatus.text = "✅ Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#16A34A")) }
                leaveType != null -> { tvStatus.text = "🌴 On $leaveType"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                else -> { tvStatus.text = "⏳ Not marked yet"; tvStatus.setTextColor(Color.parseColor("#DC2626")) }
            }
            tvMonth.text = "Present this month: $present day(s)"
            findViewById<android.view.View>(R.id.tileTeam).visibility =
                if (res.optBoolean("isManager")) android.view.View.VISIBLE else android.view.View.GONE
            res.optJSONArray("balance")?.let { b ->
                tvLeaveBal.text = "🌴 Leave left: " + (0 until b.length()).joinToString("  ·  ") {
                    val o = b.getJSONObject(it); o.getString("type").substringBefore(" ") + " " + o.optInt("remaining")
                }
            }
        }
    }
}
