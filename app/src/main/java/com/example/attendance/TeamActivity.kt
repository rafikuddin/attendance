package com.example.attendance

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
    private lateinit var session: Session
    private lateinit var countsBox: LinearLayout
    private lateinit var listTeam: LinearLayout
    private lateinit var listLeaves: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        setupHeader("My Team")
        session = Session(this)
        countsBox = findViewById(R.id.countsBox)
        listTeam = findViewById(R.id.listTeam)
        listLeaves = findViewById(R.id.listLeaves)
        loadTeam()
        loadLeaves()
    }

    private fun body() = JSONObject().put("username", session.username).put("password", session.password)

    private fun loadTeam() {
        listTeam.removeAllViews()
        listTeam.addView(note("Loading..."))
        api(body().put("action", "team")) { res, err ->
            listTeam.removeAllViews()
            if (res == null) { listTeam.addView(note(err ?: "Failed to load")); return@api }
            renderCounts(res.getJSONObject("counts"))
            val arr = res.getJSONArray("reports")
            if (arr.length() == 0) { listTeam.addView(note("No one reports to you yet.")); return@api }
            for (i in 0 until arr.length()) listTeam.addView(teamCard(arr.getJSONObject(i)))
        }
    }

    private fun loadLeaves() {
        api(body().put("action", "teamLeaves")) { res, err ->
            listLeaves.removeAllViews()
            if (res == null) { listLeaves.addView(note(err ?: "Failed to load")); return@api }
            val arr = res.getJSONArray("leaves")
            if (arr.length() == 0) { listLeaves.addView(note("No leave requests from your team.")); return@api }
            for (i in 0 until arr.length()) listLeaves.addView(leaveCard(arr.getJSONObject(i)))
        }
    }

    private fun renderCounts(c: JSONObject) {
        countsBox.removeAllViews()
        val items = listOf(
            Triple("Present", c.optInt("present"), "#16A34A"),
            Triple("On Leave", c.optInt("onLeave"), "#D97706"),
            Triple("Pending", c.optInt("pending"), "#DC2626")
        )
        for ((label, value, color) in items) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        val (fg, bg) = when (status) {
            "Present" -> "#166534" to "#DCFCE7"
            "On Leave" -> "#B45309" to "#FEF3C7"
            else -> "#B91C1C" to "#FEE2E2"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#111827")) })
        val detail = o.optString("detail")
        col.addView(TextView(this).apply {
            text = "Present ${o.optInt("presentThisMonth")} day(s) this month" + if (detail.isNotEmpty() && status == "Present") " · $detail" else ""
            textSize = 13f; setTextColor(Color.parseColor("#6B7280"))
        })
        row.addView(col)
        row.addView(pill(if (status == "On Leave" && detail.isNotEmpty()) detail.substringBefore(" ") else status, fg, bg))
        return cardWrap(row)
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

        if (status == "Pending") {
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
            if (res != null) { loadTeam(); loadLeaves() }
            else { buttons.forEach { it.isEnabled = true }; android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
