package com.example.attendance

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/** Admin only: the product list with prices, the customers (each given to one sales person) and the trade offers. */
class OrderSetupActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var tab = "products"
    private var products = emptyList<JSONObject>()
    private var offers = emptyList<JSONObject>()
    private var custQuery = ""
    private var loadId = 0
    private var importKind = ""

    private val pickExcel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readExcel(uri, importKind)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Order setup") { load() }
        setupBottomNav(null)
        content = findViewById(R.id.noticeList)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        load()
    }

    private fun load() {
        val id = ++loadId
        content.removeAllViews()
        content.addView(oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        api(JSONObject().put("action", "adminOrderCatalog")) { res, err ->
            if (id != loadId) return@api
            content.removeAllViews()
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            products = res.optJSONArray("products").toObjects()
            offers = res.optJSONArray("offers").toObjects()
            draw(res.optInt("customerCount"))
        }
    }

    private fun draw(customerCount: Int) {
        content.removeAllViews()
        content.addView(oTabs(listOf("products" to "📦 Products (${products.size})", "customers" to "👥 Customers ($customerCount)", "offers" to "🎁 Offers (${offers.size})"), tab) { tab = it; draw(customerCount) })
        when (tab) {
            "products" -> drawProducts()
            "customers" -> drawCustomers()
            "offers" -> drawOffers()
        }
    }

    private fun result(what: String, res: JSONObject): String {
        val sb = StringBuilder("$what finished.\n\nAdded ${res.optInt("added")}, updated ${res.optInt("updated")}")
        if (res.optInt("skipped") > 0) sb.append(", skipped ${res.optInt("skipped")}")
        if (res.optInt("withoutPerson") > 0) sb.append("\n${res.optInt("withoutPerson")} customer(s) have no sales person yet, so nobody can order for them.")
        val pr = res.optJSONArray("problems")
        if (pr != null && pr.length() > 0) { sb.append("\n\nProblems:"); for (i in 0 until pr.length()) sb.append("\n• ").append(pr.optString(i)) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Excel
    private fun chooseExcel(kind: String) {
        importKind = kind
        pickExcel.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/vnd.ms-excel", "*/*"))
    }

    private fun readExcel(uri: android.net.Uri, kind: String) {
        oToast("Reading the file…")
        Thread {
            var rows: List<JSONObject> = emptyList(); var skipped = 0; var problem: String? = null
            try {
                val table = contentResolver.openInputStream(uri)?.use { Xlsx.read(it) } ?: throw IllegalArgumentException("Could not open the file.")
                val r = if (kind == "products") OrderImport.parseProducts(table) else OrderImport.parseCustomers(table)
                rows = r.rows; skipped = r.skipped
                if (rows.isEmpty()) problem = "No usable rows were found in the file."
            } catch (e: IllegalArgumentException) { problem = e.message ?: "Could not read the file." }
            catch (e: Exception) { problem = "Could not read the file. Please use an Excel (.xlsx) file." }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (problem != null) { AlertDialog.Builder(this).setTitle("Cannot use this file").setMessage(problem).setPositiveButton("OK", null).show(); return@runOnUiThread }
                val label = if (kind == "products") "product" else "customer"
                AlertDialog.Builder(this).setTitle("Load ${rows.size} ${label}s?")
                    .setMessage("A $label that already exists is updated by its code; the others are added." + (if (skipped > 0) "\n\n$skipped row(s) in the file were skipped (missing a code, name or price)." else ""))
                    .setPositiveButton("Load") { _, _ ->
                        val arr = JSONArray(); rows.forEach { arr.put(it) }
                        adminApi(JSONObject().put("action", if (kind == "products") "adminProductUpload" else "adminCustomerUpload").put("rows", arr)) { res, err ->
                            if (res == null) oToast(err ?: "Could not load the file.")
                            else { AlertDialog.Builder(this).setTitle("Done").setMessage(result("Upload", res)).setPositiveButton("OK", null).show(); load() }
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    private fun hintCard(text: String) = content.addView(oLabel(text, 12f, "#8A5F52").apply { setPadding(dp(6), dp(2), dp(6), dp(8)) })

    // ------------------------------------------------------------------ products
    private fun drawProducts() {
        content.addView(oBig("➕  Add a product") { editProduct(null) })
        content.addView(oChip("📥  Upload products from Excel") { chooseExcel("products") })
        hintCard("Excel columns: SKU, Name, Price, Unit (optional). A product with the same SKU is updated.")
        products.forEach { p ->
            val c = oColumn()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(oLabel(p.optString("name"), 15f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(oLabel(OrderFmt.money(p.optDouble("price")), 15f, "#1D4ED8", true))
            c.addView(top)
            c.addView(oLabel(p.optString("sku") + "  ·  " + p.optString("unit") + (if (!p.optBoolean("active", true)) "  ·  NOT ACTIVE" else ""), 12f, if (p.optBoolean("active", true)) "#8A5F52" else "#B91C1C"))
            val card = oCard(c); card.isClickable = true; card.setOnClickListener { editProduct(p) }
            content.addView(card)
        }
    }

    private fun field(col: LinearLayout, hint: String, value: String, numeric: Boolean = false): EditText {
        val et = EditText(this).apply {
            this.hint = hint; setText(value)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        }
        col.addView(et); return et
    }

    private fun formColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }

    private fun editProduct(p: JSONObject?) {
        val col = formColumn()
        val sku = field(col, "SKU (product code)", p?.optString("sku") ?: "").apply { isEnabled = p == null }
        val name = field(col, "Product name", p?.optString("name") ?: "")
        val price = field(col, "Price", if (p != null) p.optDouble("price").toString().removeSuffix(".0") else "", numeric = true)
        val unit = field(col, "Unit (pcs, box, kg …)", p?.optString("unit") ?: "pcs")
        val active = CheckBox(this).apply { text = "Active (can be ordered)"; isChecked = p?.optBoolean("active", true) ?: true }
        col.addView(active)
        AlertDialog.Builder(this).setTitle(if (p == null) "New product" else "Edit product").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                adminApi(JSONObject().put("action", "adminProductSave").put("sku", sku.text.toString().trim()).put("name", name.text.toString().trim())
                    .put("price", price.text.toString().trim()).put("unit", unit.text.toString().trim()).put("active", active.isChecked)) { res, err ->
                    if (res == null) oToast(err ?: "Could not save.") else { oToast("Saved."); load() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ customers
    private fun drawCustomers() {
        content.addView(oBig("➕  Add a customer") { editCustomer(null) })
        content.addView(oChip("📥  Upload customers from Excel") { chooseExcel("customers") })
        hintCard("Excel columns: Code, Name, Address, Phone and Username (the sales person who orders for this customer). A customer with the same code is updated.")
        content.addView(oChip("🔎  Search" + (if (custQuery.isEmpty()) "" else ": $custQuery")) {
            askText("Search customers", "Code, name, person or address", custQuery) { custQuery = it; load() }
        })
        val id = ++loadId
        val status = oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        content.addView(status)
        api(JSONObject().put("action", "adminCustomers").put("q", custQuery)) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            content.addView(oLabel("${res.optInt("total")} customer(s)" + (if (res.optInt("total") > 100) " — first 100 shown, use Search" else ""), 12f, "#8A5F52").apply { setPadding(dp(6), 0, 0, dp(6)) })
            res.optJSONArray("customers").toObjects().forEach { c ->
                val col = oColumn()
                col.addView(oLabel(c.optString("name"), 15f, "#3E1D16", true))
                col.addView(oLabel(c.optString("code") + "  ·  " + (if (c.optString("owner").isEmpty()) "⚠ no sales person" else "👤 " + c.optString("owner")) + (if (!c.optBoolean("active", true)) "  ·  NOT ACTIVE" else ""), 12f, if (c.optString("owner").isEmpty() || !c.optBoolean("active", true)) "#B91C1C" else "#8A5F52"))
                if (c.optString("address").isNotEmpty()) col.addView(oLabel(c.optString("address"), 12f, "#8A5F52"))
                val card = oCard(col); card.isClickable = true; card.setOnClickListener { editCustomer(c) }
                content.addView(card)
            }
        }
    }

    private fun editCustomer(c: JSONObject?) {
        val col = formColumn()
        val code = field(col, "Customer code", c?.optString("code") ?: "").apply { isEnabled = c == null }
        val name = field(col, "Customer name", c?.optString("name") ?: "")
        val address = field(col, "Address", c?.optString("address") ?: "")
        val phone = field(col, "Phone", c?.optString("phone") ?: "")
        val owner = field(col, "Sales person's username", c?.optString("owner") ?: "")
        val active = CheckBox(this).apply { text = "Active"; isChecked = c?.optBoolean("active", true) ?: true }
        col.addView(active)
        AlertDialog.Builder(this).setTitle(if (c == null) "New customer" else "Edit customer").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                adminApi(JSONObject().put("action", "adminCustomerSave").put("code", code.text.toString().trim()).put("name", name.text.toString().trim())
                    .put("address", address.text.toString().trim()).put("phone", phone.text.toString().trim()).put("owner", owner.text.toString().trim()).put("active", active.isChecked)) { res, err ->
                    if (res == null) oToast(err ?: "Could not save.") else { oToast("Saved."); load() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ trade offers
    private fun nameOf(sku: String) = products.firstOrNull { it.optString("sku").equals(sku, true) }?.optString("name") ?: sku

    private fun drawOffers() {
        content.addView(oBig("➕  New trade offer") { editOffer(null) })
        hintCard("Buy a quantity of a product and get free items. Leave the customer empty to give the offer to every customer. A customer-specific offer wins over an all-customer offer.")
        if (offers.isEmpty()) content.addView(oLabel("No offers yet.", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        offers.forEach { o ->
            val c = oColumn()
            val free = if (o.optString("freeSku").isEmpty() || o.optString("freeSku").equals(o.optString("sku"), true)) "free" else "free " + nameOf(o.optString("freeSku"))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(oLabel(nameOf(o.optString("sku")), 15f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(oPill(if (o.optBoolean("active", true)) "Active" else "Off", if (o.optBoolean("active", true)) "#166534" else "#B91C1C", if (o.optBoolean("active", true)) "#DCFCE7" else "#FEE2E2"))
            c.addView(top)
            c.addView(oLabel("Buy ${o.optInt("buy")} → ${o.optInt("free")} $free", 14f, "#166534", true))
            c.addView(oLabel(o.optString("id") + "  ·  " + (if (o.optString("customer").isEmpty()) "All customers" else "Customer " + o.optString("customer")) +
                (if (o.optString("from").isNotEmpty() || o.optString("to").isNotEmpty()) "  ·  " + o.optString("from").ifEmpty { "…" } + " to " + o.optString("to").ifEmpty { "…" } else "") +
                (if (o.optString("note").isNotEmpty()) "\n" + o.optString("note") else ""), 12f, "#8A5F52"))
            val card = oCard(c); card.isClickable = true; card.setOnClickListener { editOffer(o) }
            content.addView(card)
        }
    }

    private fun editOffer(o: JSONObject?) {
        if (products.isEmpty()) { oToast("Add products first."); return }
        var sku = o?.optString("sku") ?: ""
        var freeSku = o?.optString("freeSku") ?: ""
        val col = formColumn()
        val pickProduct = oChip(if (sku.isEmpty()) "Choose the product" else "Product: " + nameOf(sku)) {}
        pickProduct.setOnClickListener {
            pickFromList("Product", products.map { it.optString("sku") to (it.optString("name") + "  (" + it.optString("sku") + ")") }) { k -> sku = k; pickProduct.text = "Product: " + nameOf(k) }
        }
        col.addView(pickProduct)
        val customer = field(col, "Customer code (empty = all customers)", o?.optString("customer") ?: "")
        val buy = field(col, "Buy quantity", o?.optInt("buy")?.toString() ?: "", numeric = true)
        val free = field(col, "Free quantity", o?.optInt("free")?.toString() ?: "", numeric = true)
        val pickFree = oChip(if (freeSku.isEmpty()) "Free product: same product" else "Free product: " + nameOf(freeSku)) {}
        pickFree.setOnClickListener {
            pickFromList("Free product", listOf("" to "Same product") + products.map { it.optString("sku") to (it.optString("name") + "  (" + it.optString("sku") + ")") }) { k ->
                freeSku = k; pickFree.text = if (k.isEmpty()) "Free product: same product" else "Free product: " + nameOf(k)
            }
        }
        col.addView(pickFree)
        val from = field(col, "From date (yyyy-mm-dd, optional)", o?.optString("from") ?: "")
        val to = field(col, "To date (yyyy-mm-dd, optional)", o?.optString("to") ?: "")
        val note = field(col, "Note (optional)", o?.optString("note") ?: "")
        val active = CheckBox(this).apply { text = "Active"; isChecked = o?.optBoolean("active", true) ?: true }
        col.addView(active)
        val b = AlertDialog.Builder(this).setTitle(if (o == null) "New trade offer" else "Edit offer " + o.optString("id")).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val body = JSONObject().put("action", "adminOfferSave").put("sku", sku).put("customer", customer.text.toString().trim())
                    .put("buy", buy.text.toString().trim()).put("free", free.text.toString().trim()).put("freeSku", freeSku)
                    .put("from", from.text.toString().trim()).put("to", to.text.toString().trim()).put("note", note.text.toString().trim()).put("active", active.isChecked)
                if (o != null) body.put("id", o.optString("id"))
                adminApi(body) { res, err -> if (res == null) oToast(err ?: "Could not save.") else { oToast("Saved."); load() } }
            }.setNegativeButton("Cancel", null)
        if (o != null) b.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setTitle("Delete this offer?").setMessage("Orders already made keep their free items.")
                .setPositiveButton("Delete") { _, _ ->
                    adminApi(JSONObject().put("action", "adminOfferDelete").put("id", o.optString("id"))) { res, err -> if (res == null) oToast(err ?: "Could not delete.") else { oToast("Deleted."); load() } }
                }.setNegativeButton("Keep", null).show()
        }
        b.show()
    }
}
