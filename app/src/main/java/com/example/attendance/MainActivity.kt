package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.PopupMenu
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
        findViewById<TextView>(R.id.tvAvatar).text = session.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { loadStatus() }
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenu(it) }
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

    private fun showMenu(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Change Password")
        menu.menu.add("Logout")
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Change Password" -> ChangePasswordDialog.show(this, session)
                "Logout" -> { session.clear(); goLogin() }
            }
            true
        }
        menu.show()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // Friday is the weekly off day — never counted as absent, never shown as "not marked yet".
    private fun loadStatus() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        val today = fmt.format(now.time)
        val isFridayToday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18   // matches Code.gs's ABSENT_AFTER_HOUR (6:00 PM)

        api(JSONObject().put("action", "history").put("username", session.username).put("password", session.password)) { res, err ->
            if (res == null) { tvStatus.text = "Offline"; tvMonth.text = err ?: ""; return@api }
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            val presentThisMonth = HashSet<String>()
            val recs = res.getJSONArray("records")
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late") }
                if (d.startsWith(today.substring(0, 7))) { present++; presentThisMonth.add(d) }
            }
            val leaves = res.optJSONArray("leaves")
            fun approvedLeaveOn(dateKey: String): String? {
                leaves?.let { lv ->
                    for (i in 0 until lv.length()) {
                        val l = lv.getJSONObject(i)
                        if (l.optString("status") == "Approved" && dateKey >= l.getString("from") && dateKey <= l.getString("to"))
                            return l.optString("type")
                    }
                }
                return null
            }
            val leaveType = approvedLeaveOn(today)

            // Absent = days from the 1st of this month to yesterday, that aren't Friday, present, or approved leave.
            var absent = 0
            val dayCal = Calendar.getInstance()
            for (day in 1 until now.get(Calendar.DAY_OF_MONTH)) {
                dayCal.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), day)
                val dKey = fmt.format(dayCal.time)
                if (dayCal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) continue
                if (presentThisMonth.contains(dKey)) continue
                if (approvedLeaveOn(dKey) != null) continue
                absent++
            }

            when {
                todayTime != null && todayLate -> { tvStatus.text = "⏰ Late Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                todayTime != null -> { tvStatus.text = "✅ Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#16A34A")) }
                leaveType != null -> { tvStatus.text = "🌴 On $leaveType"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                isFridayToday -> { tvStatus.text = "🗓️ Weekly Off (Friday)"; tvStatus.setTextColor(Color.parseColor("#6B7280")) }
                isAfterCutoff -> { tvStatus.text = "❌ Absent today"; tvStatus.setTextColor(Color.parseColor("#B91C1C")) }
                else -> { tvStatus.text = "⏳ Not marked yet"; tvStatus.setTextColor(Color.parseColor("#DC2626")) }
            }
            tvMonth.text = "Present this month: $present day(s)" + (if (absent > 0) "  ·  Absent: $absent" else "")
            findViewById<android.view.View>(R.id.tileTeam).visibility =
                if (res.optBoolean("isManager")) android.view.View.VISIBLE else android.view.View.GONE
            res.optJSONArray("balance")?.let { b ->
                tvLeaveBal.text = "Leave left: " + (0 until b.length()).joinToString("  ·  ") {
                    val o = b.getJSONObject(it); o.getString("type").substringBefore(" ") + " " + o.optInt("remaining")
                }
            }
        }
    }
}
