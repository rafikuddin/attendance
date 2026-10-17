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

    fun init(ctx: Context) {
        night = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("night", false)
        AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    /** Saves the choice and restyles the open screens straight away. */
    fun set(ctx: Context, on: Boolean) {
        if (on == night) return
        night = on
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("night", on).apply()
        AppCompatDelegate.setDefaultNightMode(if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    // The same hex strings the screens already use -> their night twin.
    private val NIGHT = mapOf(
        // text and lines
        "#1E2247" to "#E8EAFF", "#1B1E3B" to "#E8EAFF", "#111827" to "#F3F4F6", "#374151" to "#D1D5DB", "#4B5563" to "#C4C9D4",
        "#6C7293" to "#A9AFD3", "#6B7280" to "#A3A9BB", "#9AA0C0" to "#8087AC", "#9CA3AF" to "#8D95A8", "#3A3F73" to "#C9CDF7",
        // strong colours (text, small badges)
        "#DC2626" to "#F87171", "#B91C1C" to "#FCA5A5", "#B71C1C" to "#FCA5A5", "#B45309" to "#FBBF24", "#D97706" to "#FBBF24",
        "#15803D" to "#4ADE80", "#16A34A" to "#4ADE80", "#166534" to "#86EFAC", "#4F46E5" to "#8B93FF", "#4338CA" to "#A5B4FC",
        "#3730A3" to "#A5B4FC", "#5B21B6" to "#C4B5FD", "#7C3AED" to "#A78BFA", "#8B5CF6" to "#A78BFA", "#2563EB" to "#60A5FA",
        "#0D47A1" to "#93C5FD", "#0284C7" to "#38BDF8", "#0D9488" to "#2DD4BF", "#EA580C" to "#FB923C", "#C2410C" to "#FDBA74",
        "#9A3412" to "#FDBA74", "#7C2D12" to "#FED7AA", "#78350F" to "#FCD34D", "#F59E0B" to "#FBBF24",
        // pale tiles and fills -> deep tiles
        "#FEFEFE" to "#1D2142", "#F4F5FB" to "#232748", "#F3F4F6" to "#2A2D49", "#EEF0F5" to "#2A2D49", "#E5E7EB" to "#3A3E5C",
        "#EEF1FF" to "#262B55", "#EEF2FF" to "#262B55", "#ECEEFF" to "#262B55", "#E8EAFF" to "#2A2F5C", "#E6E9F7" to "#2A2F5C",
        "#ECEEF8" to "#353A63", "#EDE9FE" to "#2E2850", "#FEF3C7" to "#3A2F10", "#FEF3D6" to "#3A2F10", "#FDE68A" to "#4A3A10",
        "#FFEDD5" to "#3B2514", "#FFF4E5" to "#3B2A12", "#FEE2E2" to "#3D1B1E", "#FFEBEE" to "#3D1B1E", "#FDE8E8" to "#3D1B1E",
        "#DCFCE7" to "#12301F", "#E6F6EC" to "#12301F", "#E6F7F4" to "#12332F", "#E3F3FC" to "#12303F", "#E3F2FD" to "#12303F"
    )

    /** A colour written as "#RRGGBB". Day mode: exactly as written. Night mode: its night twin. */
    fun c(hex: String): Int {
        if (!night || hex.equals("#FFFFFF", true)) return Color.parseColor(hex)    // white text stays white
        NIGHT[hex.uppercase()]?.let { return Color.parseColor(it) }
        val v = Color.parseColor(hex)
        if (hex.length != 7) return v                                    // translucent colours are left alone
        val lum = (0.299 * Color.red(v) + 0.587 * Color.green(v) + 0.114 * Color.blue(v)) / 255.0
        return when {
            lum > 0.85 -> mix(v, Color.parseColor("#1D2142"), 0.82)      // an unlisted pale tile
            lum < 0.40 -> mix(v, Color.WHITE, 0.55)                      // an unlisted dark text colour
            else -> v
        }
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun m(x: Int, y: Int) = (x * (1 - t) + y * t).toInt().coerceIn(0, 255)
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    /** Card and panel surfaces (white by day, deep indigo by night). */
    val card: Int get() = if (night) Color.parseColor("#1D2142") else Color.WHITE
    /** The filled "selected" chip: dark ink by day, bright indigo by night (its text stays white). */
    val chipOn: Int get() = if (night) Color.parseColor("#4F46E5") else Color.parseColor("#1E2247")
}

/** Applies the saved Day / Night choice before any screen opens. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pal.init(this)
    }
}
