package com.example.attendance

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The movement of one person on one day (a person with the "Track movement" right): the points their phone sent, the metres from the previous point, the
 * total km, and the route on the map. Opened from the Team card (managers above, admins) and by the person themselves. The server decides who may see it.
 */
class TrackActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_USER = "user"
        const val EXTRA_NAME = "name"
        const val EXTRA_DATE = "date"
    }

    private lateinit var session: Session
    private lateinit var list: LinearLayout
    private var user = ""
    private var nameOf = ""
    private var date = ""                      // the day shown (yyyy-MM-dd)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val pretty = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        user = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() } ?: session.username
        nameOf = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: user
        date = intent.getStringExtra(EXTRA_DATE)?.takeIf { it.isNotBlank() } ?: iso.format(java.util.Date())
        setupHeader("📍 Movement") { load() }
        setupBottomNav(null)
        list = findViewById(R.id.noticeList)
        load()
    }

    private fun today(): String = iso.format(java.util.Date())

    private fun shift(day: String, days: Int): String = try {
        val c = Calendar.getInstance().apply { time = iso.parse(day)!!; add(Calendar.DAY_OF_MONTH, days) }
        iso.format(c.time)
    } catch (e: Exception) { day }

    private fun niceDay(day: String): String = try { pretty.format(iso.parse(day)!!) } catch (e: Exception) { day }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Pal.c("#6B352A")); setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun cardOf(inner: View) = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

    /** ‹ the day before · the date · the day after ›. */
    private fun dateBar(): View {
        val shown = date
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(10) }
        }
        fun chip(label: String, active: Boolean, weight: Float, fill: String, tap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(Pal.c(if (active) "#6B352A" else "#C9B3A8"))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(fill)) }
            minHeight = dp(40); setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(6) }
            if (active) { isClickable = true; isFocusable = true; setOnClickListener { tap() } }
        }
        bar.addView(chip("‹", true, 0f, "#F8EFC4") { date = shift(shown, -1); load() })
        bar.addView(chip("📅 ${niceDay(shown)}", true, 1f, "#F0E3A6") { pick(shown) })
        bar.addView(chip("›", shown < today(), 0f, "#F8EFC4") { date = shift(shown, 1); load() })
        return bar
    }

    private fun pick(current: String) {
        val c = Calendar.getInstance()
        try { c.time = iso.parse(current)!! } catch (e: Exception) { }
        val dlg = android.app.DatePickerDialog(this, { _, y, m, d ->
            date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d); load()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.datePicker.minDate = 1577836800000L
        dlg.show()
    }

    private fun load() {
        val busy = findViewById<View>(R.id.btnRefresh)
        Spin.start(busy, true)
        list.removeAllViews()
        list.addView(dateBar())
        list.addView(note("Loading…"))
        api(JSONObject().put("action", "trackView").put("of", user).put("date", date)) { res, err ->
            Spin.stop(busy)
            list.removeAllViews()
            list.addView(dateBar())
            if (res == null) {
                list.addView(note(if (err?.contains("Unknown action", ignoreCase = true) == true) "The server (Code.gs) has not been updated to this version yet." else (err ?: "Could not load the movement.")))
                return@api
            }
            show(res)
        }
    }

    private fun metres(m: Int): String = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "$m m"

    private fun show(res: JSONObject) {
        val pts = res.optJSONArray("points") ?: JSONArray()
        val n = pts.length()
        val every = res.optInt("everyMinutes", 10)
        val tracked = res.optBoolean("tracked")
        val head = column()
        head.addView(TextView(this).apply {
            text = res.optString("name").ifEmpty { nameOf }; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16"))
        })
        if (n == 0) {
            head.addView(TextView(this).apply {
                text = if (!tracked) "Movement tracking is not switched on for this person.\n\nAn admin can tick 📍 Track movement in Admin > Users > Menu access."
                    else if (res.optBoolean("isToday")) "No points yet today.\n\nThe phone sends a point every $every minutes once the attendance is submitted, until 6 PM (a driver until the Day End). It needs Location switched on, and the app must not be stopped by the phone's battery saver."
                    else "No points were sent on this day."
                textSize = 13f; setTextColor(Pal.c("#6B352A")); setPadding(0, dp(6), 0, 0)
            })
            list.addView(cardOf(head))
            return
        }
        val first = pts.getJSONObject(0).optString("time")
        val last = pts.getJSONObject(n - 1).optString("time")
        head.addView(TextView(this).apply {
            text = "$n point" + (if (n == 1) "" else "s") + " · ${Tracking.nice(first)} to ${Tracking.nice(last)}\nDistance moved: " + String.format(Locale.US, "%.1f km", res.optDouble("km", 0.0))
            textSize = 13f; setTextColor(Pal.c("#6B352A")); setPadding(0, dp(6), 0, 0)
        })
        head.addView(TextView(this).apply {
            text = "A point every $every minutes. Steps under 25 m are counted as standing still."
            textSize = 12f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(4), 0, 0)
        })
        head.addView(TextView(this).apply {
            text = "🗺️  See the route on the map"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A"))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { openRoute(pts) }
        })
        list.addView(cardOf(head))

        val rows = column()
        for (i in 0 until n) {
            val p = pts.getJSONObject(i)
            val acc = p.optInt("acc")
            val m = p.optInt("m")
            val row = TextView(this).apply {
                text = Tracking.nice(p.optString("time")) + (if (i == 0) "  ·  start" else if (m >= 25) "  ·  ${metres(m)} from the previous" else "  ·  stayed")
                    .plus(if (acc > 0) "  ·  ±$acc m" else "")
                textSize = 13f; setTextColor(Pal.c(if (i == 0 || m >= 25) "#3E1D16" else "#8A5F52")); setPadding(0, dp(7), 0, dp(7))
                isClickable = true; isFocusable = true
                setOnClickListener { openPoint(p.optDouble("lat"), p.optDouble("lng"), nameOf + " · " + Tracking.nice(p.optString("time"))) }
            }
            rows.addView(row)
        }
        rows.addView(TextView(this).apply { text = "Tap a line to see that spot on the map."; textSize = 12f; setTextColor(Pal.c("#A98578")); setPadding(0, dp(6), 0, 0) })
        list.addView(cardOf(rows))
    }

    private fun openPoint(lat: Double, lng: Double, label: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")))
        } catch (e: Exception) { Toast.makeText(this, "No maps app found.", Toast.LENGTH_SHORT).show() }
    }

    /** The whole day as one route in the maps app: first point = start, last = end, up to 8 in between (a maps link takes no more). */
    private fun openRoute(pts: JSONArray) {
        val n = pts.length()
        fun at(i: Int): String { val p = pts.getJSONObject(i); return "${p.optDouble("lat")},${p.optDouble("lng")}" }
        if (n < 2) { val p = pts.getJSONObject(0); openPoint(p.optDouble("lat"), p.optDouble("lng"), nameOf); return }
        val via = ArrayList<String>()
        val middle = n - 2
        val take = minOf(8, middle)
        for (k in 1..take) via.add(at(1 + ((k - 1).toLong() * middle / take).toInt()))
        val url = "https://www.google.com/maps/dir/?api=1&origin=${at(0)}&destination=${at(n - 1)}" +
            (if (via.isNotEmpty()) "&waypoints=" + Uri.encode(via.joinToString("|")) else "") + "&travelmode=driving"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { Toast.makeText(this, "No maps app found.", Toast.LENGTH_SHORT).show() }
    }
}
