package com.example.attendance

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.util.WeakHashMap
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

fun AppCompatActivity.setupHeader(title: String, onRefresh: (() -> Unit)? = null) {
    findViewById<TextView>(R.id.tvTitle).text = title
    findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    val refresh = findViewById<TextView>(R.id.btnRefresh)
    refresh.visibility = android.view.View.GONE        // there is no refresh button any more: pull the page down instead
    if (onRefresh != null) pullToRefresh(refresh, onRefresh)
}

/**
 * Pull down on the page and let go: a round spinner drops in at the top while [onRefresh] reloads the screen, then it goes away.
 * The page's (first) ScrollView is wrapped in a SwipeRefreshLayout; [anchor] is the old refresh button, which stays in the layout (hidden)
 * only because the loading code spins it and stops it when the answer arrives ([Spin] also closes the pull spinner then).
 */
fun AppCompatActivity.pullToRefresh(anchor: View, onRefresh: () -> Unit) {
    val sv = findScroll(findViewById<ViewGroup>(android.R.id.content)) ?: return
    val parent = sv.parent as? ViewGroup ?: return
    if (parent is SwipeRefreshLayout) return
    val index = parent.indexOfChild(sv)
    val lp = sv.layoutParams
    parent.removeView(sv)
    val swipe = SwipeRefreshLayout(this).apply {
        layoutParams = lp
        setColorSchemeColors(Pal.c("#6B352A"))
        setProgressBackgroundColorSchemeColor(Pal.card)
    }
    swipe.addView(sv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    parent.addView(swipe, index)
    Spin.tie(anchor, swipe)
    swipe.setOnRefreshListener {
        Spin.start(anchor)
        onRefresh()
        // Screens that report when they finish (see apiCached) stop it themselves; for any other, a moment is enough. Never spin for ever.
        anchor.postDelayed({ if (!Spin.isTied(anchor)) Spin.stop(anchor) }, 900)
        anchor.postDelayed({ swipe.isRefreshing = false }, 20000)
    }
}

private fun findScroll(v: View): ScrollView? {
    if (v is ScrollView) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
    return null
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
        label.setTextColor(Pal.tintIfBrand(ContextCompat.getColor(this, if (on) R.color.primary else R.color.muted)))
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

    private val pulls = WeakHashMap<View, SwipeRefreshLayout>()
    /** Links the (hidden) refresh button to the page's pull-down spinner, so finishing the load also closes that spinner. */
    fun tie(v: View, pull: SwipeRefreshLayout) { pulls[v] = pull }

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
        pulls[v]?.isRefreshing = false
        val s = running.remove(v) ?: return
        val cur = v.rotation % 360f
        s.anim.cancel()
        val left = 360f - cur
        v.rotation = cur
        v.animate().rotation(360f).setDuration((700f * left / 360f).toLong().coerceAtLeast(150L))
            .setInterpolator(DecelerateInterpolator()).withEndAction { v.rotation = 0f }.start()
    }
}

/**
 * A short message on its own soft card: "nothing here yet", "could not load". Loose text on the page glow is hard to read by day (the glow turns tan
 * and the brown text was only about 1.5 to 3.5:1 on it), so these sit on a card with the same surface as every other panel.
 * [tone] "error" gives a pale red card with red text; anything else is the normal card. For an empty list use [emptyState].
 */
fun AppCompatActivity.messageCard(text: String, icon: String? = null, tone: String = "info"): View {
    val bad = tone == "error"
    val card = MaterialCardView(this).apply {
        radius = dp(20).toFloat(); cardElevation = dp(2).toFloat()
        setCardBackgroundColor(if (bad) Pal.c("#FEE2E2") else Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(10) }
    }
    val tall = icon != null
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(if (tall) 20 else 16), dp(20), dp(if (tall) 20 else 16))
    }
    if (icon != null) col.addView(TextView(this).apply { this.text = icon; textSize = 30f; gravity = Gravity.CENTER })
    col.addView(TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(if (bad) Pal.c("#B91C1C") else Pal.c("#3E1D16"))
        if (tall) setPadding(0, dp(6), 0, 0)
    })
    card.addView(col)
    return card
}

/** The "nothing here yet" panel for an empty list: an icon and one line on a soft card. */
fun AppCompatActivity.emptyState(text: String, icon: String = "🗂️"): View = messageCard(text, icon)
