package com.example.attendance

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class LeaveActivity : AppCompatActivity() {
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nice = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val types = listOf("Casual Leave", "Sick Leave", "Earn Leave")
    private var from: Calendar? = null
    private var to: Calendar? = null
    private lateinit var session: Session
    private lateinit var ddType: MaterialAutoCompleteTextView
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var etReason: EditText
    private lateinit var tvDays: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnApply: MaterialButton
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave)
        setupHeader("Apply for Leave")
        session = Session(this)
        ddType = findViewById(R.id.ddType)
        etFrom = findViewById(R.id.etFrom); etTo = findViewById(R.id.etTo)
        etReason = findViewById(R.id.etReason)
        tvDays = findViewById(R.id.tvDays); tvMsg = findViewById(R.id.tvMsg)
        btnApply = findViewById(R.id.btnApply); list = findViewById(R.id.listLeaves)

        ddType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types))
        ddType.setText(types[0], false)

        etFrom.setOnClickListener {
            pick(from, null) { c -> from = c; if (to == null || to!!.before(c)) to = c.clone() as Calendar; refresh() }
        }
        etTo.setOnClickListener {
            pick(to ?: from, from) { c -> to = c; refresh() }
        }
        btnApply.setOnClickListener { apply() }
        refresh()
        loadList()
    }

    private fun days(): Int {
        val f = from ?: return 0
        val t = to ?: return 0
        return Math.round((t.timeInMillis - f.timeInMillis) / 86400000.0).toInt() + 1
    }

    private fun pick(start: Calendar?, min: Calendar?, onPick: (Calendar) -> Unit) {
        val c = start ?: Calendar.getInstance()
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            onPick(Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) })
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        min?.let { dlg.datePicker.minDate = it.timeInMillis - 43200000L }
        dlg.show()
    }

    private fun refresh() {
        etFrom.setText(from?.let { nice.format(it.time) } ?: "")
        etTo.setText(to?.let { nice.format(it.time) } ?: "")
        tvDays.text = "Total: ${days()} day(s)"
    }

    private fun apply() {
        val f = from; val t = to
        if (f == null || t == null) { msg("Please choose From and To dates", false); return }
        btnApply.isEnabled = false
        msg("Submitting...", true)
        val body = JSONObject().put("action", "leave").put("username", session.username).put("password", session.password)
            .put("type", ddType.text.toString()).put("from", iso.format(f.time)).put("to", iso.format(t.time))
            .put("reason", etReason.text.toString().trim())
        api(body) { res, err ->
            btnApply.isEnabled = true
            if (res != null) {
                msg("✅ Leave applied for ${res.optInt("days")} day(s). Status: Pending", true)
                from = null; to = null; etReason.setText(""); refresh(); loadList()
            } else msg("❌ $err", false)
        }
    }

    private fun msg(m: String, ok: Boolean) {
        tvMsg.text = m
        tvMsg.setTextColor(Color.parseColor(if (ok) "#166534" else "#DC2626"))
    }

    private fun fmt(s: String) = try { nice.format(iso.parse(s)!!) } catch (e: Exception) { s }

    private fun loadList() {
        api(JSONObject().put("action", "leaves").put("username", session.username).put("password", session.password)) { res, err ->
            list.removeAllViews()
            if (res == null) { list.addView(TextView(this).apply { text = err }); return@api }
            renderBalance(res)
            val arr = res.getJSONArray("leaves")
            if (arr.length() == 0) list.addView(TextView(this).apply { text = "No leave requests yet."; setTextColor(Color.GRAY) })
            for (i in 0 until arr.length()) list.addView(card(arr.getJSONObject(i)))
        }
    }

    private fun renderBalance(res: JSONObject) {
        findViewById<TextView>(R.id.tvBalTitle).text = "Leave Balance · ${res.optString("year")}"
        val box = findViewById<LinearLayout>(R.id.balBox)
        box.removeAllViews()
        val arr = res.optJSONArray("balance") ?: return
        var tQuota = 0; var tUsed = 0; var tRem = 0
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val quota = b.optInt("quota"); val used = b.optInt("used"); val pend = b.optInt("pending"); val rem = b.optInt("remaining")
            tQuota += quota; tUsed += used; tRem += rem
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply { text = b.optString("type").substringBefore(" "); textSize = 13f; setTextColor(Color.parseColor("#6B7280")) })
            col.addView(TextView(this).apply {
                text = rem.toString(); textSize = 32f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(if (rem <= 0) "#DC2626" else "#4F46E5"))
            })
            col.addView(TextView(this).apply { text = "of $quota left"; textSize = 12f; setTextColor(Color.parseColor("#6B7280")) })
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = quota; progress = (used + pend).coerceIn(0, quota)
                progressTintList = ColorStateList.valueOf(Color.parseColor(if (rem <= 0) "#DC2626" else "#4F46E5"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6); marginStart = dp(8); marginEnd = dp(8) }
            })
            col.addView(TextView(this).apply {
                text = "Used $used" + if (pend > 0) " · Pending $pend" else ""
                textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#6B7280"))
            })
            box.addView(col)
        }
        findViewById<TextView>(R.id.tvBalTotal).text = "Total: $tUsed used · $tRem remaining of $tQuota days"
    }

    private fun card(o: JSONObject): View {
        val status = o.optString("status", "Pending")
        val (fg, bg) = when (status) {
            "Approved" -> "#166534" to "#DCFCE7"
            "Rejected" -> "#B91C1C" to "#FEE2E2"
            else -> "#B45309" to "#FEF3C7"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(TextView(this).apply {
            text = "${o.optString("type")}  •  ${o.optString("days")} day(s)"
            textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor("#111827"))
        })
        col.addView(TextView(this).apply { text = "${fmt(o.optString("from"))}  →  ${fmt(o.optString("to"))}"; setTextColor(Color.parseColor("#6B7280")) })
        if (o.optString("reason").isNotEmpty()) col.addView(TextView(this).apply { text = o.optString("reason"); setTextColor(Color.parseColor("#6B7280")) })
        col.addView(TextView(this).apply {
            text = status; setTextColor(Color.parseColor(fg)); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(bg)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }
}
