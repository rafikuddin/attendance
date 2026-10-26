package com.example.attendance

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * New order: pick one of my customers, add products with quantities, see the price, the free items from trade offers and the total
 * (all worked out by the server), then send. The order goes to the line manager first.
 */
class OrderNewActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var customers = emptyList<JSONObject>()
    private var products = emptyList<JSONObject>()
    private var customer: JSONObject? = null
    private val qty = LinkedHashMap<String, Int>()        // sku -> quantity
    private var note = ""
    private var quote: JSONObject? = null
    private var quoting = 0
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("New order") { start() }
        setupBottomNav(null)
        enforceAccess("orders")
        content = findViewById(R.id.noticeList)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        start()
    }

    private fun start() {
        content.removeAllViews()
        content.addView(oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        api(JSONObject().put("action", "orderInit")) { res, err ->
            content.removeAllViews()
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            customers = res.optJSONArray("customers").toObjects()
            products = res.optJSONArray("products").toObjects()
            if (customers.isEmpty()) { content.addView(oLabel("No customer is assigned to you yet. Ask the admin to assign your customers.", 15f, "#B45309", true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }); return@api }
            if (products.isEmpty()) { content.addView(oLabel("There are no products yet. Ask the admin to add the product list.", 15f, "#B45309", true).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }); return@api }
            if (customer != null && customers.none { it.optString("code") == customer!!.optString("code") }) customer = null
            if (customers.size == 1 && customer == null) customer = customers[0]
            draw()
        }
    }

    private fun productOf(sku: String) = products.firstOrNull { it.optString("sku") == sku }

    private fun draw() {
        content.removeAllViews()
        // customer
        val cc = oColumn()
        cc.addView(oLabel("CUSTOMER", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        val c = customer
        cc.addView(oLabel(if (c == null) "No customer chosen" else c.optString("name") + "  (" + c.optString("code") + ")", 16f, "#3E1D16", true).apply { setPadding(0, dp(6), 0, 0) })
        if (c != null && (c.optString("address").isNotEmpty() || c.optString("phone").isNotEmpty()))
            cc.addView(oLabel(listOf(c.optString("address"), c.optString("phone")).filter { it.isNotEmpty() }.joinToString("\n"), 13f, "#8A5F52"))
        if (customers.size > 1) cc.addView(oChip(if (c == null) "Choose the customer" else "Change the customer") {
            pickFromList("Customer", customers.map { it.optString("code") to (it.optString("name") + "  (" + it.optString("code") + ")") }) { code ->
                customer = customers.firstOrNull { it.optString("code") == code }; refreshQuote(); draw()
            }
        })
        content.addView(oCard(cc))

        // products
        val pc = oColumn()
        pc.addView(oLabel("PRODUCTS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        val ql = (quote?.optJSONArray("lines")).toObjects().associateBy { it.optString("sku") }
        if (qty.isEmpty()) pc.addView(oLabel("Nothing added yet.", 14f).apply { setPadding(0, dp(8), 0, dp(4)) })
        qty.forEach { (sku, q) ->
            val p = productOf(sku)
            val line = ql[sku]
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(oLabel((p?.optString("name") ?: sku), 15f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(this).apply {
                text = "✕"; textSize = 18f; setTextColor(Pal.c("#B91C1C")); setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener { qty.remove(sku); refreshQuote(); draw() }
            })
            row.addView(top)
            val price = p?.optDouble("price") ?: 0.0
            row.addView(oLabel(sku + "  ·  " + OrderFmt.money(price) + " / " + (p?.optString("unit") ?: ""), 12f, "#8A5F52"))
            row.addView(oLabel("Qty $q  →  " + OrderFmt.money(line?.optDouble("amount") ?: (price * q)) + "   (tap to change)", 14f, "#1D4ED8", true).apply {
                setPadding(0, dp(4), 0, 0); isClickable = true
                setOnClickListener { askQty(sku, q) }
            })
            if (line != null && line.optInt("freeQty") > 0)
                row.addView(oLabel("🎁 Free: " + line.optInt("freeQty") + " × " + line.optString("freeName").ifEmpty { line.optString("freeSku") }, 13f, "#166534", true))
            pc.addView(row)
        }
        pc.addView(oChip("➕  Add a product") {
            pickFromList("Product", products.map { it.optString("sku") to (it.optString("name") + "  ·  " + it.optString("sku") + "  ·  " + OrderFmt.money(it.optDouble("price"))) }) { sku ->
                askQty(sku, qty[sku] ?: 0)
            }
        })
        content.addView(oCard(pc))

        // note + total
        val tc = oColumn()
        val noteBox = EditText(this).apply { hint = "Note (optional)"; setText(note); maxLines = 3 }
        noteBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { note = s.toString().trim() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        tc.addView(noteBox)
        val q = quote
        if (q != null && qty.isNotEmpty()) {
            tc.addView(oLabel("TOTAL  " + OrderFmt.money(q.optDouble("total")), 20f, "#3E1D16", true).apply { setPadding(0, dp(10), 0, 0) })
        } else if (quoting > 0 && qty.isNotEmpty()) tc.addView(oLabel("Working out the price…", 13f))
        tc.addView(oLabel("Prices and free items come from the product list and trade offers. The order goes to your line manager first.", 12f, "#8A5F52").apply { setPadding(0, dp(6), 0, 0) })
        content.addView(oCard(tc))

        content.addView(oBig("📨  Send the order") {
            note = noteBox.text.toString().trim()
            send()
        })
    }

    private fun askQty(sku: String, current: Int) {
        val p = productOf(sku)
        askText(p?.optString("name") ?: sku, "Quantity (whole number)", if (current > 0) current.toString() else "", numeric = true, message = "Price " + OrderFmt.money(p?.optDouble("price") ?: 0.0) + " per " + (p?.optString("unit") ?: "unit")) { v ->
            val n = v.toDoubleOrNull()?.toInt() ?: 0
            if (n < 1 || n > 1000000) { oToast("Type a quantity of 1 or more."); return@askText }
            qty[sku] = n; refreshQuote(); draw()
        }
    }

    private fun linesJson(): JSONArray { val a = JSONArray(); qty.forEach { (s, q) -> a.put(JSONObject().put("sku", s).put("qty", q)) }; return a }

    private fun refreshQuote() {
        val c = customer
        if (c == null || qty.isEmpty()) { quote = null; return }
        val id = ++quoting
        api(JSONObject().put("action", "orderQuote").put("customer", c.optString("code")).put("lines", linesJson())) { res, err ->
            if (id != quoting) return@api
            quoting = 0
            if (res == null) { quote = null; oToast(err ?: "Could not work out the price."); return@api }
            quote = res
            draw()
        }
    }

    private fun send() {
        val c = customer
        if (c == null) { oToast("Choose the customer first."); return }
        if (qty.isEmpty()) { oToast("Add at least one product."); return }
        if (sending) return
        val total = quote?.optDouble("total")
        AlertDialog.Builder(this).setTitle("Send this order?")
            .setMessage(c.optString("name") + "\n" + qty.size + " product(s)" + (if (total != null) "\nTotal " + OrderFmt.money(total) else ""))
            .setPositiveButton("Send") { _, _ ->
                sending = true
                api(JSONObject().put("action", "orderCreate").put("customer", c.optString("code")).put("lines", linesJson()).put("note", note)) { res, err ->
                    sending = false
                    if (res == null) { oToast(err ?: "Could not send the order."); return@api }
                    oToast("Order " + res.optString("orderNo") + " sent. Status: " + res.optString("status"))
                    startActivity(android.content.Intent(this, OrdersActivity::class.java).putExtra(OrdersActivity.EXTRA_ORDER, res.optString("orderNo")))
                    finish()
                }
            }.setNegativeButton("Back", null).show()
    }
}
