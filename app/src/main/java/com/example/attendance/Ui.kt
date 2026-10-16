package com.example.attendance

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.util.WeakHashMap
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
        refresh.setOnClickListener {
            Spin.start(refresh)
            onRefresh()
            // Screens that report when they finish (see apiCached) stop the spin themselves; for any other, one turn is enough.
            refresh.postDelayed({ if (!Spin.isTied(refresh)) Spin.stop(refresh) }, 900)
        }
    } else {
        refresh.visibility = android.view.View.GONE
    }
}

fun AppCompatActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


enum class Tab(val navId: Int) {
    HOME(R.id.navHome), ATTENDANCE(R.id.navAttendance), LEAVE(R.id.navLeave), TEAM(R.id.navTeam), CALENDAR(R.id.navCalendar), NIGHT(R.id.navNight)
}

/** Wires the bottom bar (layout/bottom_nav.xml) that every screen shows, and marks [active] (null = none, for screens like Admin that are not a tab). */
fun AppCompatActivity.setupBottomNav(active: Tab?) {
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
        val key = when (tab) { Tab.ATTENDANCE -> "attendance"; Tab.LEAVE -> "leave"; Tab.TEAM -> "team"; Tab.CALENDAR -> "calendar"; Tab.NIGHT -> "night"; else -> null }
        label.text = label.text.toString().removePrefix("🔒 ")
        if (key != null && !on && isLocked(key)) { icon.alpha = 0.25f; label.text = "🔒 " + label.text }
        item.setOnClickListener {
            if (key != null && !on && isLocked(key)) { toastLocked(key); return@setOnClickListener }
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

/**
 * The ⟳ refresh button spins while a screen is loading and glides to a stop on the upright position when it is done.
 * [start] with tied = true means the caller will call [stop] itself when its data arrives.
 */
object Spin {
    private class State(val anim: ObjectAnimator, var tied: Boolean)
    private val running = WeakHashMap<View, State>()

    fun isTied(v: View): Boolean = running[v]?.tied == true

    fun start(v: View, tied: Boolean = false) {
        val existing = running[v]
        if (existing != null) { if (tied) existing.tied = true; return }
        v.animate().cancel()
        val a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f).apply {
            duration = 700; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
        }
        running[v] = State(a, tied)
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {}
            override fun onViewDetachedFromWindow(x: View) { running.remove(x)?.anim?.cancel(); x.removeOnAttachStateChangeListener(this) }
        })
        a.start()
    }

    /** Finishes the turn it is in (slowing down) and rests at 0 degrees. */
    fun stop(v: View) {
        val s = running.remove(v) ?: return
        val cur = v.rotation % 360f
        s.anim.cancel()
        val left = 360f - cur
        v.rotation = cur
        v.animate().rotation(360f).setDuration((700f * left / 360f).toLong().coerceAtLeast(150L))
            .setInterpolator(DecelerateInterpolator()).withEndAction { v.rotation = 0f }.start()
    }
}
