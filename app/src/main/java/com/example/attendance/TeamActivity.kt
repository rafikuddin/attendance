package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private var canDecide = false           // only true for your own direct team

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        of = intent.getStringExtra(EXTRA_OF)
        val ofName = intent.getStringExtra(EXTRA_OF_NAME)
        setupHeader(if (ofName != null) "$ofName's Team" else "My Team") { loadAll() }
        session = Session(this)
        countsBox = findViewById(R.id.countsBox)
        listTeam = findViewById(R.id.listTeam)
        listLeaves = findViewById(R.id.listLeaves)
        loadAll()
    }

    private fun body(): JSONObject {
        val b = JSONObject().put("username", session.username).put("password", session.password)
        of?.let { b.put("of", it) }
        return b
    }

    private fun openTeamOf(username: String, name: String) {
        startActivity(Intent(this, TeamActivity::class.java)
            .putExtra(EXTRA_OF, username).putExtra(EXTRA_OF_NAME, name))
    }

    // One request for the whole screen — status, roll-ups, counts and leave requests together.
    private fun loadAll() {
        listTeam.removeAllViews(); listTeam.addView(note("Loading..."))
        listLeaves.removeAllViews(); listLeaves.addView(note("Loading..."))
        api(body().put("action", "team")) { res, err ->
            listTeam.removeAllViews(); listLeaves.removeAllViews()
            if (res == null) {
                listTeam.addView(note(err ?: "Failed to load")); listLeaves.addView(note(err ?: "Failed to load")); return@api
            }
            renderCounts(res.getJSONObject("counts"))
            val reports = res.getJSONArray("reports")
            if (reports.length() == 0) listTeam.addView(note("No one reports here yet."))
            else for (i in 0 until reports.length()) listTeam.addView(teamCard(reports.getJSONObject(i)))

            canDecide = res.optBoolean("isOwn")
            val leaves = res.getJSONArray("leaves")
            if (leaves.length() == 0) listLeaves.addView(note("No leave requests here."))
            else for (i in 0 until leaves.length()) listLeaves.addView(leaveCard(leaves.getJSONObject(i)))
        }
    }

    private fun renderCounts(c: JSONObject) {
        countsBox.removeAllViews()
        val items = listOf(
            Triple("Present", c.optInt("present"), "#16A34A"),
            Triple("Late", c.optInt("late"), "#D97706"),
            Triple("On Leave", c.optInt("onLeave"), "#EA580C"),
            Triple("Off", c.optInt("off"), "#6B7280"),
            Triple("Absent", c.optInt("absent"), "#B91C1C"),
            Triple("Pending", c.optInt("pending"), "#DC2626")
        )
        for ((label, value, color) in items) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            col.addView(TextView(this).apply {
                text = value.toString(); textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(color))
            })
            col.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(Color.parseColor("#6B7280")) })
            countsBox.addView(col)
        }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(bg)) }
    }

    private fun cardWrap(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
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
            status == "Off" -> "#374151" to "#E5E7EB"
            status == "Absent" -> "#FFFFFF" to "#B91C1C"
            else -> "#B91C1C" to "#FEE2E2"
        }
        val isManagerRow = o.optBoolean("isManager")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#111827")) })
        val detail = o.optString("detail")
        nameCol.addView(TextView(this).apply {
            text = "Own attendance: ${o.optInt("presentThisMonth")} day(s) this month" + if (detail.isNotEmpty() && status == "Present") " · $detail" else ""
            textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
        })
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
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#4F46E5"))
                setPadding(0, dp(8), 0, 0)
            })
            col.addView(TextView(this).apply {
                text = "Tap to view →"; textSize = 12f; setTextColor(Color.parseColor("#9CA3AF"))
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
        name.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#111827")) })
        name.addView(TextView(this).apply { text = "${o.optString("type")} · ${o.optString("days")} day(s)"; textSize = 13f; setTextColor(Color.parseColor("#6B7280")) })
        top.addView(name)
        top.addView(pill(status, fg, bg))
        col.addView(top)
        col.addView(TextView(this).apply {
            text = "${o.optString("from")}  →  ${o.optString("to")}"; textSize = 13f; setTextColor(Color.parseColor("#6B7280"))
            (layoutParams as? LinearLayout.LayoutParams)
            setPadding(0, dp(6), 0, 0)
        })
        if (o.optString("reason").isNotEmpty())
            col.addView(TextView(this).apply { text = o.optString("reason"); textSize = 13f; setTextColor(Color.parseColor("#6B7280")) })

        if (status == "Pending" && canDecide) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            val approve = MaterialButton(this).apply {
                text = "✓ Approve"; setBackgroundColor(Color.parseColor("#16A34A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            }
            val reject = MaterialButton(this).apply {
                text = "✕ Reject"; setBackgroundColor(Color.parseColor("#DC2626"))
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
