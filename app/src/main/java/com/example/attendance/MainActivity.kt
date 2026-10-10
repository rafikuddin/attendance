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
    private lateinit var tvTodayLoc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_home)
        tvStatus = findViewById(R.id.tvStatus)
        tvMonth = findViewById(R.id.tvMonth)
        tvTodayLoc = findViewById(R.id.tvTodayLoc)
        findViewById<TextView>(R.id.tvName).text = session.name
        findViewById<TextView>(R.id.tvAvatar).text = session.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { loadStatus() }
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<android.view.View>(R.id.tileAttendance).setOnClickListener { open(AttendanceActivity::class.java) }
        findViewById<android.view.View>(R.id.tileLeave).setOnClickListener { open(LeaveActivity::class.java) }
        findViewById<android.view.View>(R.id.tileNight).setOnClickListener { open(NightHoldActivity::class.java) }
        findViewById<android.view.View>(R.id.tileCalendar).setOnClickListener { open(CalendarActivity::class.java) }
        findViewById<android.view.View>(R.id.tileTeam).setOnClickListener { open(TeamActivity::class.java) }

        // Bottom navigation (Home is this screen)
        findViewById<android.view.View>(R.id.btnMark).setOnClickListener { open(AttendanceActivity::class.java) }
        findViewById<android.view.View>(R.id.navLeave).setOnClickListener { open(LeaveActivity::class.java) }
        findViewById<android.view.View>(R.id.navTeam).setOnClickListener { open(TeamActivity::class.java) }
        findViewById<android.view.View>(R.id.navCalendar).setOnClickListener { open(CalendarActivity::class.java) }
        findViewById<android.view.View>(R.id.navSettings).setOnClickListener { showMenu(it) }
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
                "Logout" -> { ResponseCache.clear(this); session.clear(); goLogin() }
            }
            true
        }
        menu.show()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** Fills the three leave bars: the bar shows what is left (full = nothing used yet). */
    private fun showBalance(b: org.json.JSONArray) {
        findViewById<TextView>(R.id.tvBalHeading).text = "LEAVE BALANCE ${Calendar.getInstance().get(Calendar.YEAR)}"
        var used = 0
        var pending = 0
        for (i in 0 until b.length()) {
            val o = b.getJSONObject(i)
            val quota = o.optInt("quota")
            val rem = o.optInt("remaining").coerceIn(0, maxOf(quota, 0))
            used += o.optInt("used")
            pending += o.optInt("pending")
            val pct = if (quota > 0) (rem * 100 / quota) else 0
            val ids = when (o.optString("type").substringBefore(" ")) {
                "Casual" -> Triple(R.id.pbCasual, R.id.tvCasual, R.id.tvCasualVal)
                "Sick" -> Triple(R.id.pbSick, R.id.tvSick, R.id.tvSickVal)
                "Earn" -> Triple(R.id.pbEarn, R.id.tvEarn, R.id.tvEarnVal)
                else -> null
            }
            if (ids != null) {
                findViewById<android.widget.ProgressBar>(ids.first).progress = pct
                findViewById<TextView>(ids.third).text = "$rem/$quota ($pct%)"
            }
        }
        findViewById<TextView>(R.id.tvBalUsed).text = "Used $used" + (if (pending > 0) " · Pending $pending" else "")
    }

    // Friday is the weekly off day — never counted as absent, never shown as "not marked yet".
    // "This month" runs PERIOD_START_DAY → PERIOD_END_DAY (26th → 25th), not the calendar month —
    // keep this in sync with Code.gs's PERIOD_START_DAY/PERIOD_END_DAY if you ever change the cycle.
    private fun loadStatus() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        val today = fmt.format(now.time)
        val isFridayToday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18   // matches Code.gs's ABSENT_AFTER_HOUR (6:00 PM)

        val periodStartDay = 26
        val periodEndDay = 25
        val periodStart = (now.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_MONTH) < periodStartDay) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, periodStartDay)
        }
        val periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, periodEndDay) }
        val periodStartKey = fmt.format(periodStart.time)
        val periodEndKey = fmt.format(periodEnd.time)

        val render: (JSONObject) -> Unit = { res ->
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            var todayAddr = ""
            val presentThisPeriod = HashSet<String>()
            val recs = res.getJSONArray("records")
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late"); todayAddr = r.optString("address") }
                if (d >= periodStartKey && d <= periodEndKey) { present++; presentThisPeriod.add(d) }
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

            // Absent = days from the start of this pay period to yesterday, that aren't Friday, present, or approved leave.
            var absent = 0
            val cursor = periodStart.clone() as Calendar
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            while (!cursor.after(yesterday)) {
                val dKey = fmt.format(cursor.time)
                if (cursor.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY && !presentThisPeriod.contains(dKey) && approvedLeaveOn(dKey) == null) absent++
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }

            when {
                todayTime != null && todayLate -> { tvStatus.text = "⏰ Late Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                todayTime != null -> { tvStatus.text = "✅ Present · $todayTime"; tvStatus.setTextColor(Color.parseColor("#16A34A")) }
                leaveType != null -> { tvStatus.text = "🌴 On $leaveType"; tvStatus.setTextColor(Color.parseColor("#D97706")) }
                isFridayToday -> { tvStatus.text = "🗓️ Weekly Off (Friday)"; tvStatus.setTextColor(Color.parseColor("#6B7280")) }
                isAfterCutoff -> { tvStatus.text = "❌ Absent today"; tvStatus.setTextColor(Color.parseColor("#B91C1C")) }
                else -> { tvStatus.text = "⏳ Not marked yet"; tvStatus.setTextColor(Color.parseColor("#DC2626")) }
            }
            tvMonth.text = "Present: $present day(s) (26th–25th)" + (if (absent > 0) "  ·  Absent: $absent" else "")
            val manager = if (res.optBoolean("isManager")) android.view.View.VISIBLE else android.view.View.GONE
            findViewById<android.view.View>(R.id.tileTeam).visibility = manager
            findViewById<android.view.View>(R.id.navTeam).visibility = manager
            tvTodayLoc.text = when {
                todayTime != null && todayAddr.isNotEmpty() -> "📍 $todayAddr"
                todayTime != null -> "📍 Location saved with today's attendance"
                else -> "📍 Location is captured when you mark attendance"
            }
            res.optJSONArray("balance")?.let { showBalance(it) }
        }

        // Shows the last saved answer straight away (so Home opens instantly), then refreshes it.
        // Only this pay period's records are requested, so the reply stays small as history grows.
        apiCached(
            cacheKey = ResponseCache.homeKey(session.username),
            body = JSONObject().put("action", "history").put("username", session.username)
                .put("password", session.password).put("from", periodStartKey),
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<android.view.View>(R.id.btnRefresh),
            onData = { res -> render(res) },
            onError = { err, hadSaved -> if (!hadSaved) { tvStatus.text = "Offline"; tvMonth.text = err ?: "" } }
        )
    }
}
