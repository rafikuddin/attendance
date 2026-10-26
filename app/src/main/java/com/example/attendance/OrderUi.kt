package com.example.attendance

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Small helpers shared by the order screens (Orders, New order, Setup). */
object OrderFmt {
    fun money(d: Double): String = String.format(Locale.US, "%,.2f", d)

    /** Status name -> (text colour, background colour). */
    fun statusColors(status: String): Pair<String, String> = when (status) {
        "Pending Manager", "Pending Admin", "Pending Accounts" -> "#B45309" to "#FEF3C7"
        "With Delivery", "Ready to Ship" -> "#1D4ED8" to "#DBEAFE"
        "Shipped" -> "#6D28D9" to "#EDE9FE"
        "Delivered", "Received", "Approved" -> "#166534" to "#DCFCE7"
        "Rejected", "Cancelled" -> "#B91C1C" to "#FEE2E2"
        else -> "#6B352A" to "#F0E3A6"       // Pending (returns) and anything else
    }
}

fun AppCompatActivity.oLabel(text: String, size: Float = 14f, color: String = "#6B352A", bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(Pal.c(color)); if (bold) setTypeface(null, Typeface.BOLD)
}

fun AppCompatActivity.oColumn(pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
}

fun AppCompatActivity.oCard(inner: View) = MaterialCardView(this).apply {
    radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    addView(inner)
}

fun AppCompatActivity.oBig(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
    this.text = text
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
    setOnClickListener { onClick() }
}

/** A full-width soft button. */
fun AppCompatActivity.oChip(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 15f; setTextColor(Pal.c("#6B352A"))
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
    isClickable = true; isFocusable = true
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    setOnClickListener { onClick() }
}

fun AppCompatActivity.oPill(text: String, fg: String, bg: String) = TextView(this).apply {
    this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
}

fun AppCompatActivity.oStatus(status: String): TextView { val c = OrderFmt.statusColors(status); return oPill(status, c.first, c.second) }

fun AppCompatActivity.oToast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

/** A row of tab chips; [selected] is highlighted. Returns the scrollable bar. */
fun AppCompatActivity.oTabs(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(8)) }
    items.forEach { (key, text) ->
        val on = key == selected
        row.addView(TextView(this).apply {
            this.text = text; textSize = 14f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Pal.c(if (on) "#FFFFFF" else "#8A5F52"))
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true; isFocusable = true
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) Pal.chipOn else Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            setOnClickListener { onPick(key) }
        })
    }
    return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

/** A searchable list in a dialog. [items] are (key, text) pairs; [onPick] gets the key. */
fun AppCompatActivity.pickFromList(title: String, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
    val search = EditText(this).apply { hint = "Search"; inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true }
    val list = ListView(this)
    val shown = ArrayList<Pair<String, String>>(items)
    val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.second }.toMutableList())
    list.adapter = adapter
    col.addView(search)
    col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
    val dialog = AlertDialog.Builder(this).setTitle(title).setView(col).setNegativeButton("Cancel", null).create()
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s.toString().trim().lowercase()
            shown.clear(); shown.addAll(items.filter { q.isEmpty() || it.second.lowercase().contains(q) })
            adapter.clear(); adapter.addAll(shown.map { it.second }); adapter.notifyDataSetChanged()
        }
    })
    list.setOnItemClickListener { _, _, position, _ ->
        if (position in shown.indices) { val key = shown[position].first; dialog.dismiss(); onPick(key) }
    }
    dialog.show()
}

/** Asks for one piece of text (or a number). [ok] gets the trimmed answer. */
fun AppCompatActivity.askText(title: String, hint: String, initial: String = "", numeric: Boolean = false, multiline: Boolean = false, message: String? = null, ok: (String) -> Unit) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
    val et = EditText(this).apply {
        this.hint = hint; setText(initial)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
        if (multiline) { minLines = 2; maxLines = 5 }
    }
    col.addView(et)
    val b = AlertDialog.Builder(this).setTitle(title).setView(col)
        .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }
        .setNegativeButton("Cancel", null)
    if (message != null) b.setMessage(message)
    b.show()
}

fun JSONArray?.toObjects(): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    if (this != null) for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}
