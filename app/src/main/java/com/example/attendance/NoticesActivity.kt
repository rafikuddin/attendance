package com.example.attendance

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/** Everyone's list of notices posted by an admin (newest first). */
class NoticesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session(this).loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Notices") { load() }
        setupBottomNav(null)
        list = findViewById(R.id.noticeList)
        load()
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.parseColor("#6C7293"))
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun load() {
        list.removeAllViews()
        list.addView(note("Loading…"))
        api(JSONObject().put("action", "notices")) { res, err ->
            list.removeAllViews()
            if (res == null) { list.addView(note(err ?: "Could not load notices.")); return@api }
            val arr = res.optJSONArray("notices")
            NoticeState.markSeen(this, arr)          // opening the list clears the bell's red count
            if (arr == null || arr.length() == 0) { list.addView(note("No notices right now.")); return@api }
            for (i in 0 until arr.length()) list.addView(card(arr.getJSONObject(i)))
        }
    }

    private fun card(o: JSONObject): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(TextView(this).apply {
            text = "📢  ${o.optString("title")}"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#111827"))
        })
        col.addView(TextView(this).apply {
            text = o.optString("message"); textSize = 14f; setTextColor(Color.parseColor("#374151"))
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "${o.optString("date")} ${o.optString("time")} · ${o.optString("postedBy")}"
            textSize = 12f; setTextColor(Color.parseColor("#6B7280")); setPadding(0, dp(8), 0, 0)
        })
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }
}
