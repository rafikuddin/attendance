package com.example.attendance

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.setupHeader(title: String, onRefresh: (() -> Unit)? = null) {
    findViewById<TextView>(R.id.tvTitle).text = title
    findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    val refresh = findViewById<TextView>(R.id.btnRefresh)
    if (onRefresh != null) {
        refresh.visibility = android.view.View.VISIBLE
        refresh.setOnClickListener { onRefresh() }
    } else {
        refresh.visibility = android.view.View.GONE
    }
}

fun AppCompatActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


enum class Tab(val navId: Int) {
    HOME(R.id.navHome), ATTENDANCE(R.id.navAttendance), LEAVE(R.id.navLeave), TEAM(R.id.navTeam), CALENDAR(R.id.navCalendar), NIGHT(R.id.navNight)
}

/** Wires the bottom bar (layout/bottom_nav.xml) that every screen shows, and marks [active]. */
fun AppCompatActivity.setupBottomNav(active: Tab) {
    val manager = Session(this).isManager
    for (tab in Tab.values()) {
        val item = findViewById<LinearLayout>(tab.navId)
        val on = tab == active
        if (tab == Tab.TEAM) item.visibility = if (manager || on) View.VISIBLE else View.GONE
        val dot = item.getChildAt(0)
        val icon = item.getChildAt(1) as TextView
        val label = item.getChildAt(2) as TextView
        if (on) dot.setBackgroundResource(R.drawable.bg_dot) else dot.background = null
        icon.alpha = if (on) 1f else 0.6f
        label.setTextColor(ContextCompat.getColor(this, if (on) R.color.primary else R.color.muted))
        label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        item.setOnClickListener {
            if (!on) {
                val target: Class<*> = when (tab) {
                    Tab.HOME -> MainActivity::class.java
                    Tab.ATTENDANCE -> AttendanceActivity::class.java
                    Tab.LEAVE -> LeaveActivity::class.java
                    Tab.TEAM -> TeamActivity::class.java
                    Tab.CALENDAR -> CalendarActivity::class.java
                    Tab.NIGHT -> NightHoldActivity::class.java
                }
                val go = Intent(this, target)
                if (tab == Tab.HOME) go.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(go)
                // Switching tabs from a sub-screen replaces it, so Back always returns to Home.
                if (this !is MainActivity) finish()
            }
        }
    }
}
