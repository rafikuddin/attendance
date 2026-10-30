package com.example.attendance

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/**
 * Day / Night colours for the whole app.
 * - Colours in the XML files switch by themselves (res/values-night).
 * - Colours written in the Kotlin screens go through [Pal.c]: in Night mode dark text becomes light, pale tiles become deep tiles and
 *   strong colours (red, green, amber…) become a little brighter, so every screen stays readable without a second copy of each screen.
 * The choice is kept on the phone (☀️ Light / 🌙 Dark switch in the side menu).
 */
object Pal {
    @Volatile @JvmField var night = false
    /** Set just before the colours are switched, so Home opens the side menu again after it restarts. */
    @JvmField var reopenMenu = false
    private const val FILE = "ui_theme"

    // ---- Accent colour: the whole app can be re-tinted (🎨 in the side menu). "clay" is the original brown and butter. ----
    class Accent(val key: String, val label: String, val hue: Float, val sat: Float) { val neutral: Boolean get() = key == "clay" }
    val ACCENTS = listOf(
        Accent("clay", "Clay", 0f, 1f), Accent("forest", "Forest", 150f, 0.85f), Accent("ocean", "Ocean", 208f, 0.95f),
        Accent("violet", "Violet", 265f, 0.8f), Accent("rose", "Rose", 336f, 0.85f), Accent("slate", "Slate", 215f, 0.3f)
    )
    @Volatile @JvmField var accent: Accent = ACCENTS[0]
    /** Goes up each time the accent changes: a screen that was built with an older number rebuilds itself when it comes back (see [App]). */
    @Volatile @JvmField var rev = 0

    fun setAccent(ctx: Context, key: String) {
        val a = ACCENTS.firstOrNull { it.key == key } ?: return
        if (a === accent) return
        accent = a; rev++
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("accent", key).apply()
    }

    /** The theme overlay that carries the accent's colours for pop-up dialogs, switches and text buttons (res/values/overlays.xml); 0 for the original clay. */
    fun overlayRes(): Int = when (accent.key) {
        "forest" -> R.style.ThemeOverlay_Sam_Forest
        "ocean" -> R.style.ThemeOverlay_Sam_Ocean
        "violet" -> R.style.ThemeOverlay_Sam_Violet
        "rose" -> R.style.ThemeOverlay_Sam_Rose
        "slate" -> R.style.ThemeOverlay_Sam_Slate
        else -> 0
    }

    /** The brand colours (clay browns and butter creams) that follow the accent. Status colours (red, green, amber) never change. */
    private val BRAND_HEX = setOf(
        "#6B352A", "#3E1D16", "#4A241C", "#6F4A3F", "#8A5F52", "#A98578", "#B39284", "#8A4333", "#CDB78A", "#FFFDF3", "#FFFAE3", "#F8EFC4",
        "#EBDDA0", "#FFF6C4", "#FFF1A6", "#F0E3A6", "#F3E7B0", "#FBEBC8", "#FFFDF2", "#FBEBB0", "#F1DFA3", "#D9C592"
    )
    /** The same idea for colours that come from the XML files (day and night values of the brand colours there), as RRGGBB numbers. */
    private val BRAND_RGB: Set<Int> = ("1A0C09 1F0F0C 2E1813 321B16 38201A 3A1D16 3A3A1A 3E1D16 44381A 4A241C 4A2A22 4A3220 4A332D 4A3A1A 5A362B 6A453A " +
        "6B352A 8A4333 8A5F52 CDB78A CDB88A D6A06B D9C9A8 EAD98F EDF2B8 F0E3A6 FBE9A0 FFD9C4 FFE0B8 FFE680 FFEBD2 FFEFA0 FFF1A6 FFF7CF FFF8D2 FFFBE8 FFFDF0 FFFDF2")
        .split(" ").map { it.toInt(16) }.toSet()

    fun isBrand(color: Int): Boolean = BRAND_RGB.contains(color and 0xFFFFFF)

    /**
     * The accent version of a brand colour: its hue moves to the accent's hue and its LIGHTNESS (relative luminance) stays exactly the same, so every
     * text / background pair keeps the contrast it has in the original clay palette (a green and a brown of the same HSV brightness are not equally
     * light, which is why the brightness is matched by luminance, not by the HSV value).
     */
    fun tint(color: Int): Int = tintWith(accent, color)

    private val tintCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun tintWith(a: Accent, color: Int): Int {
        if (a.neutral) return color
        val key = (ACCENTS.indexOf(a).toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
        tintCache[key]?.let { return it }
        val out = tintCompute(a, color)
        tintCache[key] = out
        return out
    }

    private fun luminance(c: Int): Double {
        fun f(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c))
    }

    private fun tintCompute(a: Accent, color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.02f) return color                                  // white / grey: nothing to tint
        var h = hsv[0]; if (h > 180f) h -= 360f
        val hue = (a.hue + (h.coerceIn(0f, 70f) - 25f) * 0.25f + 360f) % 360f
        val sat0 = (hsv[1] * a.sat).coerceIn(0f, 1f)
        val target = luminance(color)
        for (k in floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.4f, 0.25f, 0f)) {   // a very pale colour cannot hold full saturation: ease it off
            val sat = sat0 * k
            if (luminance(Color.HSVToColor(floatArrayOf(hue, sat, 1f))) >= target) {
                var lo = 0f; var hi = 1f
                repeat(14) {
                    val mid = (lo + hi) / 2f
                    if (luminance(Color.HSVToColor(floatArrayOf(hue, sat, mid))) < target) lo = mid else hi = mid
                }
                return Color.HSVToColor(Color.alpha(color), floatArrayOf(hue, sat, hi))
            }
        }
        return Color.HSVToColor(Color.alpha(color), floatArrayOf(hue, 0f, 1f))
    }
    /** Tints a colour that comes from an XML file, only when it is one of the brand colours. */
    fun tintIfBrand(color: Int): Int = if (!accent.neutral && isBrand(color)) tint(color) else color

    fun init(ctx: Context) {
        night = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("night", false)
        accent = ACCENTS.firstOrNull { it.key == ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("accent", "clay") } ?: ACCENTS[0]
        AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    /** Saves the choice and restyles the open screens straight away. */
    fun set(ctx: Context, on: Boolean) {
        if (on == night) return
        night = on
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("night", on).apply()
        AppCompatDelegate.setDefaultNightMode(if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    // The same hex strings the screens already use -> their night twin. Day palette: Clay Brown #6B352A (text, headers, buttons) on Soft Butter
    // #FFF1A6 and cream; night palette: butter text on deep clay.
    private val NIGHT = mapOf(
        // text and lines
        "#6B352A" to "#FFF1A6", "#3E1D16" to "#FBEBB0", "#4A241C" to "#F1DFA3", "#6F4A3F" to "#D9C592",
        "#8A5F52" to "#CDB78A", "#A98578" to "#A68A6C", "#B39284" to "#A68A6C", "#8A4333" to "#FFE680",
        // strong colours (text, small badges)
        "#DC2626" to "#F87171", "#B91C1C" to "#FCA5A5", "#B71C1C" to "#FCA5A5", "#B45309" to "#FBBF24", "#D97706" to "#FBBF24",
        "#15803D" to "#4ADE80", "#16A34A" to "#4ADE80", "#166534" to "#86EFAC",
        "#7C3AED" to "#A78BFA", "#8B5CF6" to "#A78BFA", "#2563EB" to "#60A5FA",
        "#0D47A1" to "#93C5FD", "#0284C7" to "#38BDF8", "#0D9488" to "#2DD4BF", "#EA580C" to "#FB923C", "#C2410C" to "#FDBA74",
        "#9A3412" to "#FDBA74", "#7C2D12" to "#FED7AA", "#78350F" to "#FCD34D", "#F59E0B" to "#FBBF24",
        // pale tiles and fills -> deep tiles
        "#FFFDF3" to "#2E1813", "#FFFAE3" to "#38201A", "#F8EFC4" to "#3A2A1F", "#EBDDA0" to "#5A362B",
        "#FFF6C4" to "#3A2A18", "#FFF1A6" to "#4A3A1A", "#F0E3A6" to "#4A2A22", "#F3E7B0" to "#5A362B", "#FBEBC8" to "#3A2A1F",
        "#FEF3C7" to "#3A2F10", "#FEF3D6" to "#3A2F10", "#FDE68A" to "#4A3A10",
        "#FFEDD5" to "#3B2514", "#FFF4E5" to "#3B2A12", "#FEE2E2" to "#3D1B1E", "#FFEBEE" to "#3D1B1E", "#FDE8E8" to "#3D1B1E",
        "#DCFCE7" to "#12301F", "#E6F6EC" to "#12301F", "#E6F7F4" to "#12332F", "#E3F3FC" to "#12303F", "#E3F2FD" to "#12303F"
    )

    /** A colour written as "#RRGGBB". Day mode: exactly as written. Night mode: its night twin. */
    fun c(hex: String): Int {
        val v = cBase(hex)
        return if (accent.neutral || !BRAND_HEX.contains(hex.uppercase())) v else tint(v)
    }

    private fun cBase(hex: String): Int {
        if (!night) when (hex.uppercase()) {                             // the two palest text browns were too faint to read (3.3:1 and 2.8:1): now 4.9:1 and 3.3:1
            "#A98578" -> return Color.parseColor("#8F6556")
            "#B39284" -> return Color.parseColor("#A98578")
        }
        if (!night || hex.equals("#FFFFFF", true)) return Color.parseColor(hex)    // white text stays white
        NIGHT[hex.uppercase()]?.let { return Color.parseColor(it) }
        val v = Color.parseColor(hex)
        if (hex.length != 7) return v                                    // translucent colours are left alone
        val lum = (0.299 * Color.red(v) + 0.587 * Color.green(v) + 0.114 * Color.blue(v)) / 255.0
        return when {
            lum > 0.85 -> mix(v, Color.parseColor("#2E1813"), 0.82)      // an unlisted pale tile
            lum < 0.40 -> mix(v, Color.parseColor("#FFF1A6"), 0.6)                      // an unlisted dark text colour
            else -> v
        }
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun m(x: Int, y: Int) = (x * (1 - t) + y * t).toInt().coerceIn(0, 255)
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    /** Card and panel surfaces (warm cream by day, deep clay by night). */
    val card: Int get() = tintIfBrand(if (night) Color.parseColor("#2E1813") else Color.parseColor("#FFFDF2"))
    /** The filled "selected" chip: Clay Brown by day, a lighter clay by night (its text stays white). */
    val chipOn: Int get() = tintIfBrand(if (night) Color.parseColor("#8A4333") else Color.parseColor("#6B352A"))
}

/** Applies the saved Day / Night choice before any screen opens. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pal.init(this)
        Fx.register(this)
        // A compulsory update stops every screen (except sign-in) until it is installed.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { AppUpdate.enforce(activity) }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
