package com.example.attendance

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsSeekBar
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import java.util.WeakHashMap

/**
 * Screen effects for the whole app, applied from one place (no screen has to ask for them):
 *  - accent colour: the brand colours of a screen follow the accent picked in the side menu (🎨); see [Pal.tint];
 *  - cards pop in with a little bounce when a screen opens (only during the first few seconds, so lists that redraw while you type stay calm);
 *  - tappable things squeeze a little when pressed and spring back;
 *  - big numbers count up from 0;
 *  - flat rounded cards get a soft, accent-tinted shadow.
 * Every step is wrapped so that a surprise (an odd drawable, an old phone) only skips the effect and never stops a screen.
 */
object Fx {
    private val stamp = WeakHashMap<Activity, Int>()
    private val born = WeakHashMap<Activity, Long>()
    private const val BOUNCE_WINDOW_MS = 4000L
    private const val BOUNCE_MAX = 14

    private class Stat { var n = 0 }

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { stamp[a] = Pal.rev }
            override fun onActivityStarted(a: Activity) { try { attach(a) } catch (e: Exception) { } }
            override fun onActivityResumed(a: Activity) {
                // the accent was changed on another screen while this one waited: rebuild it in the new colours
                if (stamp[a] != Pal.rev && !a.isFinishing) { stamp[a] = Pal.rev; a.recreate() }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun attach(a: Activity) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        if (decor.getTag(R.id.fx_attached) != null) return
        decor.setTag(R.id.fx_attached, true)
        born[a] = SystemClock.uptimeMillis()
        try {
            if (!Pal.accent.neutral) {
                a.window.statusBarColor = Pal.tintIfBrand(a.window.statusBarColor)
                a.window.navigationBarColor = Pal.tintIfBrand(a.window.navigationBarColor)
            }
        } catch (e: Exception) { }
        watch(decor, a)
    }

    /** Looks over [root] now and again whenever its layout changes (new cards, new numbers). */
    private fun watch(root: ViewGroup, a: Activity?) {
        val run = { try { walk(root, a, false, Stat()) } catch (e: Exception) { } }
        run()
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { run() }
        })
    }

    /** For a bottom sheet or dialog that is built in code: accent colours and the press squeeze (no pop-in). */
    fun polish(root: View) { if (root is ViewGroup) { try { watch(root, null) } catch (e: Exception) { } } }

    // ------------------------------------------------------------------------------------------------ the walk
    private fun walk(v: View, a: Activity?, inCard: Boolean, st: Stat) {
        if (v.getTag(R.id.fx_seen) == null) {
            v.setTag(R.id.fx_seen, true)
            retint(v)
        }
        var card = v.getTag(R.id.fx_card) as? Boolean ?: false
        if (v.width > 0 && v.height > 0 && v.getTag(R.id.fx_laid) == null) {
            v.setTag(R.id.fx_laid, true)
            card = !inCard && looksLikeCard(v)
            v.setTag(R.id.fx_card, card)
            if (v.isClickable) press(v)
            if (card) decorateCard(v, a, st)
        }
        if (v is TextView && v !is EditText && v.getTag(R.id.fx_count) == null) countUp(v)
        if (v is ViewGroup) {
            val inside = inCard || card
            for (i in 0 until v.childCount) walk(v.getChildAt(i), a, inside, st)
        }
    }

    private fun d(v: View, x: Float): Float = x * v.resources.displayMetrics.density

    // ------------------------------------------------------------------------------------------------ cards
    private fun gradientOf(dr: Drawable?): GradientDrawable? = when (dr) {
        is GradientDrawable -> dr
        is LayerDrawable -> (0 until dr.numberOfLayers).firstNotNullOfOrNull { gradientOf(dr.getDrawable(it)) }
        else -> null
    }

    /** A rounded, solid panel of a useful size: the kind of thing that should feel like a card. */
    private fun looksLikeCard(v: View): Boolean {
        if (v !is ViewGroup) return false
        if (v.width < d(v, 90f) || v.height < d(v, 52f)) return false
        if (v is MaterialCardView) return v.radius >= d(v, 14f)
        val g = gradientOf(v.background) ?: return false
        if (g.getColors() != null) return false
        val fill = g.getColor() ?: return false
        if (Color.alpha(fill.defaultColor) < 0xF0) return false
        return g.cornerRadius >= d(v, 14f)
    }

    private fun decorateCard(v: View, a: Activity?, st: Stat) {
        // a soft shadow in the accent colour for flat cards (cards that already have one keep it)
        if (v !is MaterialCardView && v.elevation == 0f) {
            v.elevation = d(v, 3f)
            if (!Pal.night && Build.VERSION.SDK_INT >= 28) {
                val c = Pal.tint(Color.parseColor("#6B352A"))
                v.outlineSpotShadowColor = Color.argb(0x70, Color.red(c), Color.green(c), Color.blue(c))
                v.outlineAmbientShadowColor = Color.argb(0x30, Color.red(c), Color.green(c), Color.blue(c))
            }
        }
        // the pop-in: only in the first seconds of a screen, only for cards you can see, not the bottom bar, at most a handful
        val t0 = if (a != null) born[a] else null
        if (t0 == null || SystemClock.uptimeMillis() - t0 > BOUNCE_WINDOW_MS || st.n >= BOUNCE_MAX) return
        val r = Rect()
        if (!v.getLocalVisibleRect(r) || r.height() < v.height / 2) return
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val screenH = v.resources.displayMetrics.heightPixels
        if (loc[1] + v.height > screenH * 0.9f) return
        val a0 = v.alpha
        v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.translationY = d(v, 22f)
        v.animate().alpha(a0).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(minOf(st.n, 7) * 45L).setDuration(480).setInterpolator(OvershootInterpolator(1.3f))
            .withEndAction { v.animate().setStartDelay(0) }.start()
        st.n++
    }

    // ------------------------------------------------------------------------------------------------ press squeeze
    private val PRESS = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(340).setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false                                                              // never consumes the touch: clicks work exactly as before
    }

    private fun press(v: View) {
        if (v is EditText || v is AbsSeekBar) return
        if (v.width < d(v, 32f) || v.height > d(v, 300f)) return
        v.setOnTouchListener(PRESS)
    }

    // ------------------------------------------------------------------------------------------------ numbers
    private fun countUp(tv: TextView) {
        if (tv.textSize / tv.resources.displayMetrics.scaledDensity < 20f || !tv.isShown) return
        val s = tv.text.toString()
        if (s.isEmpty() || s.length > 5 || !s.all { it in '0'..'9' }) return
        val n = s.toInt()
        if (n < 2 || n in 1900..2100) return                              // 0 and 1 have nothing to count; years are not counts
        tv.setTag(R.id.fx_count, true)
        var last = "0"
        tv.text = last
        val an = ValueAnimator.ofInt(0, n).apply { duration = 450L + minOf(n, 60) * 8L; interpolator = DecelerateInterpolator(1.6f) }
        an.addUpdateListener {
            if (!tv.isAttachedToWindow || tv.text.toString() != last) { an.cancel(); return@addUpdateListener }   // the app wrote another number meanwhile
            last = (it.animatedValue as Int).toString(); tv.text = last
        }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(x: Animator) { if (tv.text.toString() == last || tv.text.toString() == "0") tv.text = s }
        })
        an.start()
    }

    // ------------------------------------------------------------------------------------------------ accent colours of XML-made views
    private fun tintCsl(c: ColorStateList?): ColorStateList? {
        if (c == null) return null
        val off = intArrayOf(-android.R.attr.state_enabled)
        val def = c.defaultColor; val dis = c.getColorForState(off, def)
        if (!Pal.isBrand(def) && !Pal.isBrand(dis)) return null
        return if (c.isStateful) ColorStateList(arrayOf(off, intArrayOf()), intArrayOf(Pal.tintIfBrand(dis), Pal.tintIfBrand(def)))
        else ColorStateList.valueOf(Pal.tintIfBrand(def))
    }

    private fun retint(v: View) {
        if (Pal.accent.neutral) return
        try { v.background?.let { tintDrawable(it) } } catch (e: Exception) { }
        try {
            if (v is MaterialCardView) {
                tintCsl(v.cardBackgroundColor)?.let { v.setCardBackgroundColor(it) }
                tintCsl(v.strokeColorStateList)?.let { v.setStrokeColor(it) }
            }
        } catch (e: Exception) { }
        try {
            if (v is TextView) {
                tintCsl(v.textColors)?.let { v.setTextColor(it) }
                tintCsl(v.hintTextColors)?.let { v.setHintTextColor(it) }
            }
        } catch (e: Exception) { }
        try { tintCsl(v.backgroundTintList)?.let { v.backgroundTintList = it } } catch (e: Exception) { }
    }

    private fun tintDrawable(dr: Drawable) {
        when (dr) {
            is RippleDrawable -> {
                dr.mutate()
                val c = Pal.tint(Color.parseColor("#6B352A"))
                dr.setColor(ColorStateList.valueOf(Color.argb(0x22, Color.red(c), Color.green(c), Color.blue(c))))
                for (i in 0 until dr.numberOfLayers) tintDrawable(dr.getDrawable(i))
            }
            is LayerDrawable -> { dr.mutate(); for (i in 0 until dr.numberOfLayers) tintDrawable(dr.getDrawable(i)) }
            is GradientDrawable -> {
                dr.mutate()
                val cols = dr.getColors()
                if (cols != null) { if (cols.any { Pal.isBrand(it) }) dr.setColors(IntArray(cols.size) { Pal.tintIfBrand(cols[it]) }) }
                else tintCsl(dr.getColor())?.let { dr.setColor(it) }
            }
            is ColorDrawable -> if (Pal.isBrand(dr.color)) { dr.mutate(); dr.color = Pal.tint(dr.color) }
            else -> {}
        }
    }
}
