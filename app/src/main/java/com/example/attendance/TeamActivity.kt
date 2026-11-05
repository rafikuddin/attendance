package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

class TeamActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OF = "of"
        const val EXTRA_OF_NAME = "ofName"
        const val EXTRA_DATE = "date"           // the day shown (yyyy-MM-dd); absent = today. A team opened from this screen keeps the same day.
    }

    private lateinit var session: Session
    private lateinit var countsBox: LinearLayout
    private lateinit var listTeam: LinearLayout
    private var of: String? = null          // null = viewing my own team
    private var nightHost: LinearLayout? = null      // the Night hold summary inside the top card
    private var nightDate: String? = null            // a date picked by the manager; null = the latest night
    private var nightCycle: String? = null           // "prev" | "current" | "next" = a whole pay cycle (26th to 25th); null = one night
    private var canDecide = false           // true for anyone below you, at any depth (server re-checks every decision)
    private var attDate: String? = null      // the day the manager picked for the attendance list and the numbers; null = today
    private var shownDate = ""               // the day the server answered for (empty until the first answer)
    private var shownIsToday = true
    private var leavesAll = org.json.JSONArray()           // every leave request below you, as the server sent it (pending first, newest first)
    private var cycleFrom = ""                             // the current salary cycle (26th to 25th): first and last day, yyyy-MM-dd
    private var cycleTo = ""
    private var leaveSheetRefresh: (() -> Unit)? = null    // redraws the open "leave applications" sheet when a new answer arrives (after a decision)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        of = intent.getStringExtra(EXTRA_OF)
        attDate = intent.getStringExtra(EXTRA_DATE)?.takeIf { it.isNotBlank() }
        val ofName = intent.getStringExtra(EXTRA_OF_NAME)
        setupHeader(if (ofName != null) "$ofName's Team" else "My Team") { loadAll() }
        setupBottomNav(Tab.TEAM)
        enforceAccess("team")
        session = Session(this)
        countsBox = findViewById(R.id.countsBox)
        listTeam = findViewById(R.id.listTeam)
        loadAll()
    }

    private fun body(): JSONObject {
        val b = JSONObject().put("username", session.username)
        of?.let { b.put("of", it) }
        return b
    }

    private fun openTeamOf(username: String, name: String) {
        startActivity(Intent(this, TeamActivity::class.java)
            .putExtra(EXTRA_OF, username).putExtra(EXTRA_OF_NAME, name).putExtra(EXTRA_DATE, attDate))
    }

    // One request for the whole screen — status, roll-ups, counts and leave requests together.
    // Opens instantly from the last saved copy (if it is from the last 30 minutes), then refreshes.
    private fun loadAll() {
        val showedSaved = apiCached(
            cacheKey = ResponseCache.teamKey(session.username, of) + (attDate?.let { "|$it" } ?: ""),
            body = body().put("action", "team").also { b -> attDate?.let { b.put("date", it) } },
            maxAgeMs = HOUR_MS / 2,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> render(res) },
            onError = { err, hadSaved ->
                if (!hadSaved) {
                    listTeam.removeAllViews()
                    listTeam.addView(note(err ?: "Failed to load"))
                }
            }
        )
        if (!showedSaved) {
            listTeam.removeAllViews(); listTeam.addView(note("Loading..."))
        }
    }

    private fun render(res: JSONObject) {
        listTeam.removeAllViews()
        shownDate = res.optString("date")
        shownIsToday = res.optBoolean("isToday", true)
        if (attDate != null && shownDate.isEmpty()) {                         // an older Code.gs answers with today and no date
            attDate = null
            android.widget.Toast.makeText(this, "Picking a day needs the latest Code.gs (deploy a New version).", android.widget.Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.tvAttTitle).text = if (shownIsToday || shownDate.isEmpty()) "Today's Attendance" else "Attendance · ${niceDay(shownDate)}"
        leavesAll = res.optJSONArray("leaves") ?: org.json.JSONArray()
        setCycle(res.optJSONObject("payCycle"))
        val reports = res.getJSONArray("reports")
        renderCounts(res.getJSONObject("counts"), res.optJSONArray("people"), reports.length() > 0)
        if (reports.length() > 0) loadNight()
        if (reports.length() == 0) listTeam.addView(emptyState("No one reports here yet.", "👥"))
        else for (i in 0 until reports.length()) listTeam.addView(teamCard(reports.getJSONObject(i)))

        canDecide = res.optBoolean("canDecide", res.optBoolean("isOwn"))
        leaveSheetRefresh?.invoke()                       // an open "leave applications" sheet shows the new answer too
    }

    // Shows today's selfie (loaded privately through the Apps Script photo proxy) when present,
    // otherwise a plain initials circle — so every row always has a consistent-looking avatar.
    private fun avatarView(o: JSONObject): View {
        val size = dp(46)
        val card = MaterialCardView(this).apply {
            radius = (size / 2).toFloat(); cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
        }
        val selfie = o.optString("selfie")
        if (o.optString("status") == "Present" && selfie.isNotEmpty()) {
            card.setCardBackgroundColor(Pal.c("#EBDDA0"))
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            card.clipToOutline = true
            card.addView(img)
            PhotoLoader.load(this, session, selfie, img)
            card.isClickable = true; card.isFocusable = true
            card.setOnClickListener { showPhotoDialog(session, selfie) }            // tap the small picture: the selfie, big
        } else {
            card.setCardBackgroundColor(Pal.c("#F0E3A6"))
            card.addView(TextView(this).apply {
                text = o.optString("name").trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A")); setTypeface(null, Typeface.BOLD); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        return card
    }

    /** A title line, then six equal coloured tiles in two rows of three. Nothing scrolls sideways, so no label is ever cut off. */
    private fun renderCounts(c: JSONObject, people: org.json.JSONArray?, withLeaves: Boolean) {
        countsBox.removeAllViews()
        // label, number, text colour, tile colour, symbol
        val items = listOf(
            listOf("Present", c.optInt("present"), "#15803D", "#E6F6EC", "✅"),
            listOf("Late", c.optInt("late"), "#B45309", "#FEF3C7", "⏰"),
            listOf("On Leave", c.optInt("onLeave"), "#C2410C", "#FFEDD5", "🌴"),
            listOf("Off", c.optInt("off"), "#6F4A3F", "#F8EFC4", "🗓"),
            listOf("Absent", c.optInt("absent"), "#B91C1C", "#FDE8E8", "❌"),
            listOf("Pending", c.optInt("pending"), "#6B352A", "#FFF1A6", "⏳")
        )
        val total = c.optInt("total", items.sumOf { v -> v[1] as Int })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = if (shownIsToday || shownDate.isEmpty()) "TEAM TODAY" else "TEAM · " + niceShort(shownDate).uppercase(); textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")); letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(pill("$total member" + (if (total == 1) "" else "s"), "#6B352A", "#F0E3A6"))
        countsBox.addView(head)
        countsBox.addView(dateBar())

        // the hero card: everybody below you, big, in the accent colour (tap = the whole list)
        val here = c.optInt("present") + c.optInt("late")
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Pal.chipOn) }
            setPadding(dp(18), dp(14), dp(16), dp(14)); elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            isClickable = true; isFocusable = true
            setOnClickListener { showPeople("All members", "👥", "#6B352A", "#F0E3A6", people) }
        }
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        hcol.addView(TextView(this).apply { text = "Team members"; textSize = 13f; setTextColor(Color.parseColor("#E6FFFFFF")) })
        hcol.addView(TextView(this).apply { text = total.toString(); textSize = 38f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) })
        hcol.addView(TextView(this).apply { text = "$here here " + (if (shownIsToday || shownDate.isEmpty()) "today" else "on ${niceShort(shownDate)}") + "  ·  tap to see everyone"; textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF")) })
        hero.addView(hcol)
        hero.addView(TextView(this).apply {
            text = "↗"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#33FFFFFF")) }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        countsBox.addView(hero)
        for (rowStart in listOf(0, 3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            for (k in 0 until 3) {
                val item = items[rowStart + k]
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c(item[3] as String)) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (k > 0) marginStart = dp(5); if (k < 2) marginEnd = dp(5)
                    }
                }
                tile.addView(TextView(this).apply {
                    text = (item[1] as Int).toString(); textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(item[2] as String))
                })
                tile.addView(TextView(this).apply {
                    text = (item[4] as String) + " " + (item[0] as String); textSize = 12f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Pal.c(item[2] as String)); maxLines = 1
                })
                tile.isClickable = true; tile.isFocusable = true
                tile.setOnClickListener { showPeople(item[0] as String, item[4] as String, item[2] as String, item[3] as String, people) }       // tap a number: who is behind it
                row.addView(tile)
            }
            countsBox.addView(row)
        }
        if (withLeaves) countsBox.addView(leaveSummary())              // leave applications of the current salary cycle, between the numbers and Night hold
        nightHost = LinearLayout(this).apply {                          // the Night hold summary is filled in by loadNight()
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        countsBox.addView(nightHost)
    }

    // ------------------------------------------------------------------ The day that is shown
    private val isoFmt get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

    /** Today by the phone's clock, as yyyy-MM-dd. */
    private fun phoneToday(): String = isoFmt.format(java.util.Date())

    private fun shiftDay(iso: String, days: Int): String = try {
        val c = java.util.Calendar.getInstance().apply { time = isoFmt.parse(iso)!!; add(java.util.Calendar.DAY_OF_MONTH, days) }
        isoFmt.format(c.time)
    } catch (e: Exception) { iso }

    /** Shows another day: today (or later) means the normal "today" view. */
    private fun setAttDate(day: String?) {
        attDate = if (day == null || day >= phoneToday()) null else day
        loadAll()
    }

    private fun pickAttDate(current: String) {
        val c = java.util.Calendar.getInstance()
        try { c.time = isoFmt.parse(current)!! } catch (e: Exception) { }
        val dlg = android.app.DatePickerDialog(this, { _, y, m, d -> setAttDate(String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()               // up to today
        dlg.datePicker.minDate = 1577836800000L                           // 1 Jan 2020, as far back as the server looks
        dlg.show()
    }

    /** The day of the numbers and the list: ‹ the day before · the date (tap to pick any earlier day) · the day after › · Today. Same look as the Night hold date. */
    private fun dateBar(): View {
        val today = phoneToday()
        val shown = if (shownDate.isEmpty()) today else shownDate
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        fun chip(label: String, active: Boolean, weight: Float, fill: String, tap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(Pal.c(if (active) "#6B352A" else "#C9B3A8"))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(fill)) }
            minHeight = dp(40); setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(6) }
            if (active) { isClickable = true; isFocusable = true; setOnClickListener { tap() } }
        }
        bar.addView(chip("‹", true, 0f, "#F8EFC4") { setAttDate(shiftDay(shown, -1)) })
        bar.addView(chip("📅 ${niceDay(shown)} ▾", true, 1f, "#F0E3A6") { pickAttDate(shown) })
        bar.addView(chip("›", shown < today, 0f, "#F8EFC4") { setAttDate(shiftDay(shown, 1)) })
        if (!shownIsToday) bar.addView(chip("Today", true, 0f, "#F8EFC4") { setAttDate(null) })
        return bar
    }

    // ------------------------------------------------------------------ Leave applications of the current salary cycle (Pending / Approved / Rejected)
    /**
     * The current salary cycle (26th to 25th) as the server names it ("payCycle"). An older Code.gs does not send it, so the same two dates are
     * worked out from the phone's date.
     */
    private fun setCycle(p: JSONObject?) {
        val s = p?.optString("start").orEmpty()
        val e = p?.optString("end").orEmpty()
        if (s.length == 10 && e.length == 10) { cycleFrom = s; cycleTo = e; return }
        val own = cycleOf(java.util.Calendar.getInstance())
        cycleFrom = own.first; cycleTo = own.second
    }

    /** The salary cycle (26th to 25th) that contains [day], as two yyyy-MM-dd texts: first day, last day. */
    private fun cycleOf(day: java.util.Calendar): Pair<String, String> {
        val c = day.clone() as java.util.Calendar
        if (c.get(java.util.Calendar.DAY_OF_MONTH) < 26) c.add(java.util.Calendar.MONTH, -1)
        c.set(java.util.Calendar.DAY_OF_MONTH, 26)
        val first = isoFmt.format(c.time)
        c.add(java.util.Calendar.MONTH, 1)
        c.set(java.util.Calendar.DAY_OF_MONTH, 25)
        return Pair(first, isoFmt.format(c.time))
    }

    /** A leave request belongs to the cycle when any day of it falls inside the cycle (the leave days are what the salary is worked out from). */
    private fun inCycle(o: JSONObject): Boolean {
        val from = o.optString("from").take(10)
        val to = o.optString("to").take(10).ifEmpty { from }
        return from.isNotEmpty() && from <= cycleTo && to >= cycleFrom
    }

    /** The leave requests of one status ("Pending", "Approved" or "Rejected") in the current cycle, in the order the server sent them (newest first). */
    private fun leavesOf(status: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until leavesAll.length()) {
            val o = leavesAll.optJSONObject(i) ?: continue
            if (o.optString("status").ifEmpty { "Pending" } == status && inCycle(o)) out.add(o)
        }
        return out
    }

    /**
     * Pending requests whose leave days all fall outside the current cycle: an old one nobody decided, or one for a later cycle.
     * They are not in the tile numbers, but the Pending list shows them under the cycle's own, so no request is ever left without a place to decide it.
     */
    private fun pendingOutside(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until leavesAll.length()) {
            val o = leavesAll.optJSONObject(i) ?: continue
            if (o.optString("status").ifEmpty { "Pending" } == "Pending" && !inCycle(o)) out.add(o)
        }
        return out
    }

    /** Between the Team today numbers and Night hold: the leave applications of the current salary cycle as three tiles. Tap a tile for the applications. */
    private fun leaveSummary(): View {
        val matchW = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(16) }
        }
        host.addView(View(this).apply {                                          // the line that separates it from the numbers above
            setBackgroundColor(Pal.c("#EBDDA0")); layoutParams = LinearLayout.LayoutParams(matchW, dp(1)).apply { bottomMargin = dp(14) }
        })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "📝"; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f) }
        titles.addView(TextView(this).apply { text = "Leave applications"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        titles.addView(TextView(this).apply {
            text = "Current salary cycle · ${niceShort(cycleFrom)} – ${niceShort(cycleTo)}"
            textSize = 12f; setTextColor(Pal.c("#8A5F52")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        head.addView(titles)
        host.addView(head)

        // status, symbol, text colour, tile colour: the same colours as Late, Present and Absent above, so they read at a glance
        val items = listOf(
            listOf("Pending", "⏳", "#B45309", "#FEF3C7"),
            listOf("Approved", "✅", "#15803D", "#E6F6EC"),
            listOf("Rejected", "❌", "#B91C1C", "#FDE8E8")
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(14) }
        }
        for (k in items.indices) {
            val item = items[k]
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c(item[3])) }
                layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f).apply {
                    if (k > 0) marginStart = dp(5); if (k < 2) marginEnd = dp(5)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { showLeaves(item[0], item[1], item[2], item[3]) }
            }
            tile.addView(TextView(this).apply {
                text = leavesOf(item[0]).size.toString(); textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(item[2]))
            })
            tile.addView(TextView(this).apply {
                text = item[1] + " " + item[0]; textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Pal.c(item[2])); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(tile)
        }
        host.addView(row)
        host.addView(TextView(this).apply {
            text = "Counted by leave dates · tap a number for the list"
            textSize = 12f; setTextColor(Pal.c("#8A5F52")); setPadding(dp(2), dp(8), 0, 0)
        })
        val outside = pendingOutside().size
        if (outside > 0) {                                                           // a waiting request of another cycle still has its place to be decided
            host.addView(TextView(this).apply {
                text = if (outside == 1) "⏳ 1 more pending in another salary cycle · tap Pending to decide"
                       else "⏳ $outside more pending in other salary cycles · tap Pending to decide"
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#B45309")); setPadding(dp(2), dp(4), 0, 0)
            })
        }
        return host
    }

    /**
     * The leave applications of one status in the current cycle, newest first, one card each. This is the only place a leave is decided from:
     * a pending one can be approved or rejected right here; the sheet stays open and redraws when the new answer comes back ([leaveSheetRefresh]).
     * The Pending list also shows, below the cycle's own, the pending ones of other cycles ([pendingOutside]).
     */
    private fun showLeaves(status: String, symbol: String, fg: String, bg: String) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Pal.card); cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(14), dp(16), dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        // header: coloured symbol tile, the title with the count and the cycle, and a close button
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = symbol; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c(bg)) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        titleCol.addView(TextView(this).apply { text = "$status leave"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(fg)) })
        val count = TextView(this).apply { textSize = 13f; setTextColor(Pal.c("#8A5F52")) }
        titleCol.addView(count)
        head.addView(titleCol)
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { sheet.dismiss() }
        })
        root.addView(head)

        // search (only when the list is long)
        val search = android.widget.EditText(this).apply {
            hint = "🔍  Search name or ID"; textSize = 15f; setSingleLine(true)
            setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F8EFC4")) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(6) }
        }
        fun others(): List<JSONObject> = if (status == "Pending") pendingOutside() else emptyList()      // only the Pending list shows other cycles
        if (leavesOf(status).size + others().size > 6) root.addView(search)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        }
        root.addView(scroll)

        val span = "${niceShort(cycleFrom)} – ${niceShort(cycleTo)}"
        fun fill(q: String) {
            list.removeAllViews()
            val key = q.trim().lowercase()
            fun match(o: JSONObject) = key.isEmpty() || o.optString("name").lowercase().contains(key) || o.optString("username").lowercase().contains(key)
            val all = leavesOf(status)
            val rows = all.filter { match(it) }
            val otherRows = others().filter { match(it) }
            count.text = (if (key.isEmpty()) "${all.size} application" + (if (all.size == 1) "" else "s") else "${rows.size} of ${all.size}") + "  ·  $span"
            if (all.isEmpty()) list.addView(note("No ${status.lowercase()} leave applications in this salary cycle."))
            else if (rows.isEmpty() && otherRows.isEmpty()) list.addView(note("No match."))
            rows.forEach { list.addView(leaveCard(it, true)) }
            if (otherRows.isNotEmpty()) {
                list.addView(TextView(this).apply {
                    text = "⏳ Pending in other salary cycles · ${otherRows.size}"
                    textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#B45309")); setPadding(dp(4), dp(14), 0, dp(6))
                })
                otherRows.forEach { list.addView(leaveCard(it, true)) }
            }
        }
        fill("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { fill(e?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        leaveSheetRefresh = { fill(search.text?.toString() ?: "") }
        sheet.setOnDismissListener { leaveSheetRefresh = null }
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
        Fx.polish(root)
    }

    // ------------------------------------------------------------------ Night hold summary (total, team by team, who / where / when)
    private fun loadNight() {
        val host = nightHost ?: return
        host.removeAllViews()
        host.addView(note("🌙 Night hold: loading…"))
        val b = body().put("action", "teamNight")
        val cyc = nightCycle
        if (cyc != null) b.put("cycle", cyc) else nightDate?.let { b.put("nightDate", it) }
        api(b) { res, err ->
            if (nightHost !== host) return@api                          // the screen was redrawn meanwhile
            host.removeAllViews()
            if (res == null) { host.addView(note("🌙 Night hold: ${err ?: "could not load"}")); return@api }
            renderNight(host, res)
        }
    }

    private fun niceDay(iso: String): String = try {
        java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!)
    } catch (e: Exception) { iso }

    private fun niceShort(iso: String): String = try {
        java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!)
    } catch (e: Exception) { iso }

    /** A thin progress bar: [frac] of the track is filled with the accent colour (any value above zero keeps a small visible piece). */
    private fun nightBar(frac: Float, heightDp: Int, trackHex: String, topDp: Int): View {
        val h = dp(heightDp)
        val f = if (frac <= 0f) 0f else frac.coerceIn(0.03f, 1f)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = h / 2f; setColor(Pal.c(trackHex)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(topDp) }
        }
        if (f > 0f) {
            track.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = h / 2f; setColor(Pal.c("#6B352A")) }     // the accent text colour: dark by day, light at night
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f)
            })
            if (f < 1f) track.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f) })
        }
        return track
    }

    /** "2 / 82": the first number bold and dark (quiet when it is 0), the rest in the soft colour. */
    private fun nightRatio(done: Int, all: Int): CharSequence {
        val first = done.toString()
        val sb = android.text.SpannableStringBuilder(first)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, first.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(Pal.c(if (done == 0) "#8A5F52" else "#3E1D16")), 0, first.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append(" / $all")
        sb.setSpan(android.text.style.ForegroundColorSpan(Pal.c("#8A5F52")), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * The Night hold block at the bottom of the top card: a title with the date, one control for the four views (a night, or the previous / current /
     * next pay cycle), the total as a big number with a bar, and the teams as one tidy list (name, how many of how many, a bar). Tapping the total or a
     * team opens the list of names, places and dates ([showNightList]).
     */
    private fun renderNight(host: LinearLayout, res: JSONObject) {
        val date = res.optString("nightDate")
        val cycleMode = res.optString("mode") == "cycle"
        val span = if (cycleMode) "${niceShort(res.optString("from"))} – ${niceShort(res.optString("to"))}" else niceDay(date)
        val records = res.optJSONArray("records") ?: org.json.JSONArray()
        val groups = res.optJSONArray("groups") ?: org.json.JSONArray()
        val total = res.optInt("total")
        val people = res.optInt("people")
        val submitted = res.optInt("submitted")
        val matchW = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT

        // the line that separates it from the numbers above
        host.addView(View(this).apply {
            setBackgroundColor(Pal.c("#EBDDA0")); layoutParams = LinearLayout.LayoutParams(matchW, dp(1)).apply { bottomMargin = dp(14) }
        })

        // title: a moon tile, "Night hold", what is shown, and (for one night) the date button
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "🌙"; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f) }
        titles.addView(TextView(this).apply { text = "Night hold"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        val cycleName = when (res.optString("cycle")) { "prev" -> "Previous pay cycle"; "next" -> "Next pay cycle"; else -> "Current pay cycle" }
        titles.addView(TextView(this).apply {
            text = if (cycleMode) "$cycleName · $span" else "One night"
            textSize = 12f; setTextColor(Pal.c("#8A5F52")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        head.addView(titles)
        if (!cycleMode) {
            head.addView(TextView(this).apply {                          // opens the date picker, so it is a proper tap target (40dp tall)
                text = "📅 $span ▾"; textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A"))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c("#F0E3A6")) }
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(wrapH, dp(40)).apply { marginStart = dp(8) }
                isClickable = true; isFocusable = true
                setOnClickListener { pickNightDate(date) }
            })
        }
        host.addView(head)

        // one control for the four views: one night | previous / current / next pay cycle
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Pal.c("#F8EFC4")) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(14) }
        }
        listOf(Pair(null, "Night"), Pair("prev", "Previous"), Pair("current", "Current"), Pair("next", "Next")).forEach { (key, label) ->
            val on = (nightCycle == key)
            modes.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
                setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (on) Color.WHITE else Pal.c("#6F4A3F"))
                if (on) background = GradientDrawable().apply { cornerRadius = dp(19).toFloat(); setColor(Pal.chipOn) }
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                isClickable = true; isFocusable = true
                setOnClickListener { if (nightCycle != key) { nightCycle = key; loadNight() } }
            })
        }
        host.addView(modes)

        // the total: a big number, what it counts, a bar, and the way into the list
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Pal.c("#FFF1A6")) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(14) }
            isClickable = true; isFocusable = true
            setOnClickListener { showNightList("Night hold · $span", records, null) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f) }
        left.addView(TextView(this).apply {
            text = "TOTAL NIGHT HOLDS"; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#8A5F52")); letterSpacing = 0.08f
        })
        val figures = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }      // no gravity: the number and its words sit on one text line
        figures.addView(TextView(this).apply { text = "$submitted"; textSize = 34f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")) })
        figures.addView(TextView(this).apply {
            text = if (cycleMode) "by $people of $total members" else "of $total members"
            textSize = 14f; setTextColor(Pal.c("#8A5F52")); setPadding(dp(8), 0, 0, 0)
        })
        left.addView(figures)
        top.addView(left)
        top.addView(TextView(this).apply {
            text = "›"; textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")); setPadding(0, 0, 0, dp(2))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        hero.addView(top)
        val heroDone = if (cycleMode) people else submitted                  // a cycle: how many people held a night; one night: how many nights
        hero.addView(nightBar(if (total > 0) heroDone.toFloat() / total else 0f, 8, "#EBDDA0", 12))
        hero.addView(TextView(this).apply { text = "Tap to see names, places and dates"; textSize = 12f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(8), 0, 0) })
        host.addView(hero)

        // team by team: one grouped list, a thin bar under each name
        if (groups.length() == 0) return
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(34), dp(8))                              // the right edge lines up with the numbers below
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(20) }
        }
        cap.addView(TextView(this).apply {
            text = "BY TEAM"; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#8A5F52")); letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f)
        })
        cap.addView(TextView(this).apply {
            text = if (cycleMode) "PEOPLE / MEMBERS" else "SUBMITTED / MEMBERS"
            textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#8A5F52")); letterSpacing = 0.04f
        })
        host.addView(cap)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Pal.c("#FFFAE3")) }
            setPadding(0, dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(matchW, wrapH)
        }
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val key = g.optString("key"); val name = g.optString("name")
            val all = g.optInt("total")
            val nights = g.optInt("submitted")
            val done = if (cycleMode) g.optInt("people") else nights
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { showNightList("$name · $span", records, key) }
            }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = name; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(if (done == 0) "#6F4A3F" else "#3E1D16")); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, wrapH, 1f)
            })
            line.addView(TextView(this).apply { text = nightRatio(done, all); textSize = 14f; setPadding(dp(8), 0, 0, 0) })
            line.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(Pal.c("#B39284")); setPadding(dp(8), 0, 0, 0) })
            row.addView(line)
            row.addView(nightBar(if (all > 0) done.toFloat() / all else 0f, 6, "#F0E3A6", 8))
            if (cycleMode && nights > 0) row.addView(TextView(this).apply {
                text = "$nights night hold" + (if (nights == 1) "" else "s"); textSize = 12f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(6), 0, 0)
            })
            box.addView(row)
            if (i < groups.length() - 1) box.addView(View(this).apply {
                setBackgroundColor(Pal.c("#EBDDA0"))
                layoutParams = LinearLayout.LayoutParams(matchW, dp(1)).apply { marginStart = dp(14); marginEnd = dp(14) }
            })
        }
        host.addView(box)
    }

    private fun pickNightDate(current: String) {
        val c = java.util.Calendar.getInstance()
        try { c.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(current)!! } catch (e: Exception) { }
        val dlg = android.app.DatePickerDialog(this, { _, y, m, d ->
            nightDate = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            nightCycle = null
            loadNight()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()               // up to today
        dlg.show()
    }

    /** Who submitted a night hold, with the place and the date (all of them, or one team's: [group]). Tap a row to see the spot on the map. */
    private fun showNightList(title: String, records: org.json.JSONArray, group: String?) {
        val all = ArrayList<JSONObject>()
        for (i in 0 until records.length()) { val r = records.getJSONObject(i); if (group == null || r.optString("group") == group) all.add(r) }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Pal.card); cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(14), dp(16), dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "🌙"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        titleCol.addView(TextView(this).apply { text = title; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")) })
        val count = TextView(this).apply { textSize = 13f; setTextColor(Pal.c("#8A5F52")) }
        titleCol.addView(count); head.addView(titleCol)
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)); setOnClickListener { sheet.dismiss() }
        })
        root.addView(head)
        val search = android.widget.EditText(this).apply {
            hint = "🔍  Search name, ID or place"; textSize = 15f; setSingleLine(true)
            setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F8EFC4")) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(6) }
        }
        if (all.size > 6) root.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list) })

        fun initials(n: String): String = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase() }
        fun nice(t: String): String = t.replace(Regex("(\\d+:\\d\\d):\\d\\d"), "$1")
        fun fill(q: String) {
            list.removeAllViews()
            val key = q.trim().lowercase()
            val rows = all.filter { key.isEmpty() || (it.optString("name") + " " + it.optString("username") + " " + it.optString("hotel") + " " + it.optString("thana") + " " + it.optString("district")).lowercase().contains(key) }
            count.text = if (key.isEmpty()) "${all.size} ${if (all.size == 1) "night hold" else "night holds"}" else "${rows.size} of ${all.size}"
            if (rows.isEmpty()) list.addView(note(if (all.isEmpty()) "No night holds." else "No match."))
            rows.forEach { r ->
                val name = r.optString("name")
                val place = listOf(r.optString("thana"), r.optString("district")).filter { it.isNotEmpty() }.joinToString(", ")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#FFFAE3")) }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener { openMap(r.optString("lat"), r.optString("lng"), name) }
                }
                row.addView(TextView(this).apply {
                    text = initials(name); textSize = 15f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A"))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#F0E3A6")) }
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
                })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                col.addView(TextView(this).apply { text = name; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
                col.addView(TextView(this).apply { text = "🏨 ${r.optString("hotel")}"; textSize = 13f; setTextColor(Pal.c("#6B352A")) })
                col.addView(TextView(this).apply { text = "📍 $place"; textSize = 12f; setTextColor(Pal.c("#8A5F52")) })
                col.addView(TextView(this).apply {
                    text = "🗓 ${niceDay(r.optString("date"))} · ${nice(r.optString("time"))}" + (if (r.optBoolean("manual")) " · manual entry" else "") + "  ·  ID ${r.optString("username")}"
                    textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                })
                row.addView(col)
                row.addView(TextView(this).apply { text = "🗺"; textSize = 20f; setPadding(dp(8), 0, 0, 0) })
                list.addView(row)
            }
        }
        fill("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { fill(e?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
        Fx.polish(root)
    }

    private fun openMap(lat: String, lng: String, label: String) {
        if (lat.isBlank() || lng.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(${android.net.Uri.encode(label)})")))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No maps app found.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The people behind one number (Present, Late, On Leave, Off, Absent or Pending): everybody below, line managers included.
     * A full-height sheet with a search box; each person is one row, and tapping a row opens that person's calendar.
     */
    private fun showPeople(label: String, symbol: String, fg: String, bg: String, people: org.json.JSONArray?) {
        if (people == null) { android.widget.Toast.makeText(this, "Pull down to refresh, then tap again.", android.widget.Toast.LENGTH_SHORT).show(); return }
        val all = ArrayList<JSONObject>()
        for (i in 0 until people.length()) {
            val p = people.getJSONObject(i)
            val st = p.optString("status")
            val hit = when (label) { "All members" -> true; "Late" -> st == "Present" && p.optBoolean("late"); else -> st == label }
            if (hit) all.add(p)
        }
        all.sortBy { it.optString("name").lowercase() }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Pal.card); cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(14), dp(16), dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        // header: coloured symbol tile, the title with the count, and a close button
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = symbol; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c(bg)) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        titleCol.addView(TextView(this).apply { text = label; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(fg)) })
        val count = TextView(this).apply { textSize = 13f; setTextColor(Pal.c("#8A5F52")) }
        titleCol.addView(count)
        head.addView(titleCol)
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { sheet.dismiss() }
        })
        root.addView(head)

        // search
        val search = android.widget.EditText(this).apply {
            hint = "🔍  Search name or ID"; textSize = 15f; setSingleLine(true)
            setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F8EFC4")) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(6) }
        }
        if (all.size > 6) root.addView(search)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        }
        root.addView(scroll)

        fun nice(t: String): String = t.replace(Regex("(\\d+:\\d\\d):\\d\\d"), "$1")          // 8:53:49 AM -> 8:53 AM
        fun initials(n: String): String = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase() }

        fun fill(q: String) {
            list.removeAllViews()
            val key = q.trim().lowercase()
            val rows = all.filter { key.isEmpty() || it.optString("name").lowercase().contains(key) || it.optString("username").lowercase().contains(key) }
            count.text = if (key.isEmpty()) "${all.size} ${if (all.size == 1) "person" else "people"}" else "${rows.size} of ${all.size}"
            if (rows.isEmpty()) list.addView(note(if (all.isEmpty()) "Nobody is $label right now." else "No match."))
            rows.forEach { p ->
                val name = p.optString("name"); val id = p.optString("username")
                val base = p.optString("base"); val detail = p.optString("detail")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#FFFAE3")) }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener { openCalendar(id, name) }
                }
                row.addView(TextView(this).apply {            // avatar with the initials
                    text = initials(name); textSize = 15f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Pal.c(fg))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c(bg)) }
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
                })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                col.addView(TextView(this).apply { text = name; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(TextView(this).apply {
                    text = "ID $id" + (if (base.isNotEmpty()) "  ·  📍 $base" else ""); textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(col)
                if (detail.isNotEmpty()) row.addView(TextView(this).apply {                 // the time (or the leave type) as a small chip
                    text = if (p.optString("status") == "Present") nice(detail) else detail
                    textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(fg))
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Pal.c(bg)) }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
                })
                row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Pal.c("#B39284")); setPadding(dp(8), 0, 0, 0) })
                list.addView(row)
            }
        }
        fill("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { fill(e?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
        Fx.polish(root)
    }

    /** Opens one person's calendar: the admin's full view for an admin, the read-only team view for a line manager with the Team members right. */
    private fun openCalendar(username: String, name: String) {
        val admin = AdminFlag.get(this)
        if (!admin && !Access.canViewTeam(this)) {
            android.widget.Toast.makeText(this, "Opening a calendar needs the \"Team members\" right. Ask an admin to give it to you.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, CalendarActivity::class.java)
            .putExtra(CalendarActivity.EXTRA_USER, username).putExtra(CalendarActivity.EXTRA_NAME, name).putExtra(CalendarActivity.EXTRA_VIEW_ONLY, !admin))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c("#3E1D16"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (fg == "#FFFFFF") Color.parseColor(bg) else Pal.c(bg)) }
    }

    /** [flat] = the quieter card for inside a sheet: a tinted fill and no shadow, like the rows of the people lists. */
    private fun cardWrap(inner: View, flat: Boolean = false): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(if (flat) 18 else 24).toFloat(); cardElevation = if (flat) 0f else dp(3).toFloat()
        setCardBackgroundColor(if (flat) Pal.c("#FFFAE3") else Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun teamCard(o: JSONObject): View {
        val status = o.optString("status")
        val late = o.optBoolean("late")
        val pillLabel = if (status == "Present" && late) "Late" else status
        val (fg, bg) = when {
            status == "Present" && late -> "#9A3412" to "#FFEDD5"
            status == "Present" -> "#166534" to "#DCFCE7"
            status == "On Leave" -> "#B45309" to "#FEF3C7"
            status == "Off" -> "#4A241C" to "#EBDDA0"
            status == "Absent" -> "#FFFFFF" to "#B91C1C"
            else -> "#B91C1C" to "#FEE2E2"
        }
        val isManagerRow = o.optBoolean("isManager")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(avatarView(o))
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        val detail = o.optString("detail")
        nameCol.addView(TextView(this).apply {
            text = "Own attendance: ${o.optInt("presentThisMonth")} day(s) this month" + if (detail.isNotEmpty() && status == "Present") " · $detail" else ""
            textSize = 12f; setTextColor(Pal.c("#8A5F52"))
        })
        // Where the selfie was taken. Tap the line: the spot opens on the map. (No address: the coordinates are shown instead.)
        val address = o.optString("address")
        val lat = o.optString("lat"); val lng = o.optString("lng")
        val canMap = lat.isNotBlank() && lng.isNotBlank() && lat != "null" && lng != "null"
        if (status == "Present" && (address.isNotEmpty() || canMap)) {
            nameCol.addView(TextView(this).apply {
                text = "📍 " + (if (address.isNotEmpty() && address != "Address unavailable") address else if (canMap) "$lat, $lng" else address)
                textSize = 12f; maxLines = 2; setPadding(0, dp(4), 0, dp(4))
                if (canMap) {
                    setTextColor(Pal.c("#6B352A")); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { openMap(lat, lng, o.optString("name")) }
                } else setTextColor(Pal.c("#8A5F52"))
            })
        }
        if (status == "Present" && late) {
            val why = o.optString("lateReason")
            nameCol.addView(TextView(this).apply {
                text = if (why.isEmpty()) "📝 No late reason yet" else "📝 Late reason: $why"; textSize = 12f
                setTextColor(Pal.c(if (why.isEmpty()) "#B45309" else "#6B352A")); setPadding(0, dp(2), 0, 0)
            })
        }
        if (status == "Present" && FaceId.isWarning(o.optString("face"))) {
            nameCol.addView(TextView(this).apply {
                text = FaceId.describe(o.optString("face")); textSize = 12f
                setTextColor(Pal.c("#B45309")); setPadding(0, dp(2), 0, 0)
            })
        }
        // A driver's car meter picture (and the km the phone read from it): tap to open it. The server only sends it to the person and the managers above.
        val meterId = o.optString("meter")
        if (status == "Present" && meterId.isNotEmpty()) {
            val km = o.optString("meterKm")
            nameCol.addView(TextView(this).apply {
                text = "🚗 Car meter" + (if (km.isNotEmpty()) " · $km km" else "") + " · tap to see the picture"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Pal.c("#6B352A")); setPadding(0, dp(4), 0, dp(2))
                setOnClickListener { showPhotoDialog(session, meterId) }
            })
        }
        // The day end of a driver (a second car meter picture at the end of the work day): the time, the km and the km driven since the morning picture.
        // A driver who has marked attendance with a meter picture but has not sent the day end yet shows "not yet".
        val endTime = o.optString("dayEnd")
        if (status == "Present" && (endTime.isNotEmpty() || meterId.isNotEmpty())) {
            val endId = o.optString("dayEndMeter")
            val endKm = o.optString("dayEndKm")
            val driven = o.optInt("driven", -1)
            nameCol.addView(TextView(this).apply {
                text = if (endTime.isEmpty()) "🏁 Day end · not yet"
                    else "🏁 Day end · $endTime" + (if (endKm.isNotEmpty()) " · $endKm km" else "") + (if (driven >= 0) " · $driven km driven" else "") +
                        (if (endId.isNotEmpty()) " · tap to see the picture" else "")
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Pal.c(if (endTime.isEmpty()) "#A98578" else "#6B352A")); setPadding(0, dp(2), 0, dp(2))
                if (endId.isNotEmpty()) setOnClickListener { showPhotoDialog(session, endId) }
            })
        }
        // Movement tracking (an admin ticked it for this person): how many points the phone has sent that day and when the last one came. Tap: the route.
        if (o.optBoolean("tracked") && (status == "Present" || o.optInt("trackCount") > 0)) {
            val n = o.optInt("trackCount")
            val last = Tracking.nice(o.optString("trackLast"))
            nameCol.addView(TextView(this).apply {
                text = if (n == 0) "🛰️ Movement · no points yet · tap to open"
                    else "🛰️ Movement · $n point" + (if (n == 1) "" else "s") + (if (last.isNotEmpty()) " · last $last" else "") + " · tap to see the route"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Pal.c("#6B352A")); setPadding(0, dp(2), 0, dp(2))
                setOnClickListener {
                    startActivity(Intent(this@TeamActivity, TrackActivity::class.java)
                        .putExtra(TrackActivity.EXTRA_USER, o.optString("username")).putExtra(TrackActivity.EXTRA_NAME, o.optString("name"))
                        .putExtra(TrackActivity.EXTRA_DATE, shownDate))
                }
            })
        }
        top.addView(nameCol)
        top.addView(pill(if (status == "On Leave" && detail.isNotEmpty()) detail.substringBefore(" ") else pillLabel, fg, bg))
        col.addView(top)

        val summary = o.optJSONObject("summary")
        if (isManagerRow && summary != null) {
            val p = summary.optInt("present"); val lt = summary.optInt("late"); val l = summary.optInt("onLeave")
            val off = summary.optInt("off"); val ab = summary.optInt("absent"); val pe = summary.optInt("pending"); val t = summary.optInt("total")
            col.addView(TextView(this).apply {
                text = "👥 Team: $p Present" + (if (lt > 0) " ($lt Late)" else "") + " · $l On Leave" +
                    (if (off > 0) " · $off Off" else "") + (if (ab > 0) " · $ab Absent" else "") + " · $pe Pending  (of $t)"
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A"))
                setPadding(0, dp(8), 0, 0)
            })
            col.addView(TextView(this).apply {
                text = "Tap to view →"; textSize = 12f; setTextColor(Pal.c("#B39284"))
                setPadding(0, dp(2), 0, 0)
            })
        }

        val card = cardWrap(col)
        if (isManagerRow) {
            card.isClickable = true; card.isFocusable = true
            card.setOnClickListener { openTeamOf(o.optString("username"), o.optString("name")) }
        }
        return card
    }

    private fun leaveCard(o: JSONObject, flat: Boolean = false): View {
        val status = o.optString("status")
        val (fg, bg) = when (status) {
            "Approved" -> "#166534" to "#DCFCE7"
            "Rejected" -> "#B91C1C" to "#FEE2E2"
            else -> "#B45309" to "#FEF3C7"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        name.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        name.addView(TextView(this).apply { text = "${o.optString("type")} · ${o.optString("days")} day(s)"; textSize = 13f; setTextColor(Pal.c("#8A5F52")) })
        top.addView(name)
        top.addView(pill(status, fg, bg))
        col.addView(top)
        col.addView(TextView(this).apply {
            text = "${o.optString("from")}  →  ${o.optString("to")}"; textSize = 13f; setTextColor(Pal.c("#8A5F52"))
            (layoutParams as? LinearLayout.LayoutParams)
            setPadding(0, dp(6), 0, 0)
        })
        if (o.optString("reason").isNotEmpty())
            col.addView(TextView(this).apply { text = o.optString("reason"); textSize = 13f; setTextColor(Pal.c("#8A5F52")) })
        if (o.optString("application").isNotEmpty()) col.addView(applicationLink(session, o.optString("application")))
        decidedLine(o).takeIf { it.isNotEmpty() }?.let { line ->
            col.addView(TextView(this).apply { text = line; textSize = 13f; setTextColor(Pal.c(fg)); setPadding(0, dp(6), 0, 0) })
        }

        if (status == "Pending" && canDecide) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            val approve = MaterialButton(this).apply {
                text = "✓ Approve"; setBackgroundColor(Pal.c("#16A34A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            }
            val reject = MaterialButton(this).apply {
                text = "✕ Reject"; setBackgroundColor(Pal.c("#DC2626"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
            }
            approve.setOnClickListener { decide(o, "Approved", approve, reject) }
            reject.setOnClickListener { decide(o, "Rejected", approve, reject) }
            btnRow.addView(approve); btnRow.addView(reject)
            col.addView(btnRow)
        }
        return cardWrap(col, flat)
    }

    private fun decide(o: JSONObject, decision: String, vararg buttons: MaterialButton) {
        buttons.forEach { it.isEnabled = false }
        val body = body().put("action", "decideLeave").put("empUsername", o.optString("username"))
            .put("appliedOn", o.optString("appliedOn")).put("from", o.optString("from")).put("to", o.optString("to"))
            .put("decision", decision)
        api(body) { res, err ->
            if (res != null) {
                ResponseCache.remove(this, ResponseCache.teamKey(session.username, of) + (attDate?.let { "|$it" } ?: ""))     // the saved copy is out of date now: do not show it first
                loadAll()
            }
            else { buttons.forEach { it.isEnabled = true }; android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
