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
    }

    private lateinit var session: Session
    private lateinit var countsBox: LinearLayout
    private lateinit var listTeam: LinearLayout
    private lateinit var listLeaves: LinearLayout
    private var of: String? = null          // null = viewing my own team
    private var nightHost: LinearLayout? = null      // the Night hold summary inside the top card
    private var nightDate: String? = null            // a date picked by the manager; null = the latest night
    private var nightCycle: String? = null           // "prev" | "current" | "next" = a whole pay cycle (26th to 25th); null = one night
    private var canDecide = false           // true for anyone below you, at any depth (server re-checks every decision)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        of = intent.getStringExtra(EXTRA_OF)
        val ofName = intent.getStringExtra(EXTRA_OF_NAME)
        setupHeader(if (ofName != null) "$ofName's Team" else "My Team") { loadAll() }
        setupBottomNav(Tab.TEAM)
        enforceAccess("team")
        session = Session(this)
        countsBox = findViewById(R.id.countsBox)
        listTeam = findViewById(R.id.listTeam)
        listLeaves = findViewById(R.id.listLeaves)
        loadAll()
    }

    private fun body(): JSONObject {
        val b = JSONObject().put("username", session.username)
        of?.let { b.put("of", it) }
        return b
    }

    private fun openTeamOf(username: String, name: String) {
        startActivity(Intent(this, TeamActivity::class.java)
            .putExtra(EXTRA_OF, username).putExtra(EXTRA_OF_NAME, name))
    }

    // One request for the whole screen — status, roll-ups, counts and leave requests together.
    // Opens instantly from the last saved copy (if it is from the last 30 minutes), then refreshes.
    private fun loadAll() {
        val showedSaved = apiCached(
            cacheKey = ResponseCache.teamKey(session.username, of),
            body = body().put("action", "team"),
            maxAgeMs = HOUR_MS / 2,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> render(res) },
            onError = { err, hadSaved ->
                if (!hadSaved) {
                    listTeam.removeAllViews(); listLeaves.removeAllViews()
                    listTeam.addView(note(err ?: "Failed to load")); listLeaves.addView(note(err ?: "Failed to load"))
                }
            }
        )
        if (!showedSaved) {
            listTeam.removeAllViews(); listTeam.addView(note("Loading..."))
            listLeaves.removeAllViews(); listLeaves.addView(note("Loading..."))
        }
    }

    private fun render(res: JSONObject) {
        listTeam.removeAllViews(); listLeaves.removeAllViews()
        renderCounts(res.getJSONObject("counts"), res.optJSONArray("people"))
        if (res.getJSONArray("reports").length() > 0) loadNight()
        val reports = res.getJSONArray("reports")
        if (reports.length() == 0) listTeam.addView(note("No one reports here yet."))
        else for (i in 0 until reports.length()) listTeam.addView(teamCard(reports.getJSONObject(i)))

        canDecide = res.optBoolean("canDecide", res.optBoolean("isOwn"))
        val leaves = res.getJSONArray("leaves")
        if (leaves.length() == 0) listLeaves.addView(note("No leave requests here."))
        else for (o in pendingFirst(leaves)) listLeaves.addView(leaveCard(o))
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
    private fun renderCounts(c: JSONObject, people: org.json.JSONArray?) {
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
            text = "TEAM TODAY"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")); letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(pill("$total member" + (if (total == 1) "" else "s"), "#6B352A", "#F0E3A6"))
        countsBox.addView(head)
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
        nightHost = LinearLayout(this).apply {                          // the Night hold summary is filled in by loadNight()
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        countsBox.addView(nightHost)
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

    private fun renderNight(host: LinearLayout, res: JSONObject) {
        val date = res.optString("nightDate")
        val cycleMode = res.optString("mode") == "cycle"
        val span = if (cycleMode) "${niceShort(res.optString("from"))} – ${niceShort(res.optString("to"))}" else niceDay(date)
        val records = res.optJSONArray("records") ?: org.json.JSONArray()
        val groups = res.optJSONArray("groups") ?: org.json.JSONArray()
        val total = res.optInt("total")
        val divider = View(this).apply {
            setBackgroundColor(Pal.c("#EBDDA0")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(12) }
        }
        host.addView(divider)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "🌙 NIGHT HOLD"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")); letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (cycleMode) head.addView(pill("🗓 $span", "#6B352A", "#F0E3A6"))
        else head.addView(pill("📅 $span ▾", "#6B352A", "#F0E3A6").apply { setOnClickListener { pickNightDate(date) } })
        host.addView(head)

        // one night  |  previous / current / next pay cycle
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        listOf(Pair(null, "Night"), Pair("prev", "Previous"), Pair("current", "Current"), Pair("next", "Next")).forEach { (key, label) ->
            val on = (nightCycle == key)
            modes.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(Pal.c(if (on) "#FFFFFF" else "#6B352A"))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Pal.c(if (on) "#6B352A" else "#F8EFC4")) }
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                isClickable = true; isFocusable = true
                setOnClickListener { if (nightCycle != key) { nightCycle = key; loadNight() } }
            })
        }
        host.addView(modes)

        // the total
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#FFF1A6")) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            isClickable = true; isFocusable = true
            setOnClickListener { showNightList("Night hold · $span", records, null) }
        }
        val people = res.optInt("people")
        totalRow.addView(TextView(this).apply { text = "${res.optInt("submitted")}"; textSize = 30f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")) })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tcol.addView(TextView(this).apply { text = if (cycleMode) "Night holds · by $people ${if (people == 1) "person" else "people"}" else "Total night holds · of $total"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")) })
        tcol.addView(TextView(this).apply { text = "Tap to see names, places and dates"; textSize = 12f; setTextColor(Pal.c("#8A5F52")) })
        totalRow.addView(tcol)
        totalRow.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(Pal.c("#6B352A")) })
        host.addView(totalRow)

        // team by team
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val key = g.optString("key"); val name = g.optString("name")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#FFFAE3")) }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                isClickable = true; isFocusable = true
                setOnClickListener { showNightList("$name · $span", records, key) }
            }
            row.addView(TextView(this).apply {
                text = name; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(pill(if (cycleMode) "${g.optInt("submitted")} by ${g.optInt("people")} of ${g.optInt("total")}" else "${g.optInt("submitted")} of ${g.optInt("total")}", "#6B352A", "#F0E3A6"))
            row.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(Pal.c("#B39284")); setPadding(dp(8), 0, 0, 0) })
            host.addView(row)
        }
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
            val hit = when (label) { "Late" -> st == "Present" && p.optBoolean("late"); else -> st == label }
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
        this.text = text; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (fg == "#FFFFFF") Color.parseColor(bg) else Pal.c(bg)) }
    }

    private fun cardWrap(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
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
        val address = o.optString("address")
        if (status == "Present" && address.isNotEmpty()) {
            nameCol.addView(TextView(this).apply {
                text = "📍 $address"; textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                maxLines = 2; setPadding(0, dp(2), 0, 0)
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

    private fun leaveCard(o: JSONObject): View {
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
        return cardWrap(col)
    }

    private fun decide(o: JSONObject, decision: String, vararg buttons: MaterialButton) {
        buttons.forEach { it.isEnabled = false }
        val body = body().put("action", "decideLeave").put("empUsername", o.optString("username"))
            .put("appliedOn", o.optString("appliedOn")).put("from", o.optString("from")).put("to", o.optString("to"))
            .put("decision", decision)
        api(body) { res, err ->
            if (res != null) { loadAll() }
            else { buttons.forEach { it.isEnabled = true }; android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
