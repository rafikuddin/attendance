package com.example.attendance

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Orders. Everybody sees their own orders and can start a new one; the other tabs show up for the people who have a part in the flow:
 * line managers and admins approve, the accounts person enters the received amount, the delivery person moves the order to
 * Ready to Ship > Shipped > Delivered. Returns of delivered products are handled here too. The server checks every step.
 */
class OrdersActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var tab = "mine"
    private var roles = JSONObject()
    private var queue: JSONObject = JSONObject()
    private var loadId = 0
    private var detailNo: String? = null
    private var allStatus = ""
    private var allQuery = ""

    companion object { const val EXTRA_ORDER = "order" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Orders") { reload() }
        setupBottomNav(null)
        enforceAccess("orders")
        content = findViewById(R.id.noticeList)
        val back = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailNo != null) { detailNo = null; reload() } else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
        onBackPressedDispatcher.addCallback(this, back)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        intent.getStringExtra(EXTRA_ORDER)?.takeIf { it.isNotBlank() }?.let { detailNo = it }
    }

    override fun onResume() {
        super.onResume()
        if (session.loggedIn) { val d = detailNo; if (d != null) showDetail(d) else reload() }
    }

    private fun canApprove() = roles.optBoolean("manager") || roles.optBoolean("admin")

    /** Loads what is waiting for me (this also tells which tabs to show) and draws the current tab. */
    private fun reload() {
        val id = ++loadId
        detailNo = null
        content.removeAllViews()
        content.addView(oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        api(JSONObject().put("action", "orderPending")) { res, err ->
            if (id != loadId) return@api
            content.removeAllViews()
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            roles = res.optJSONObject("roles") ?: JSONObject()
            queue = res
            drawTab()
        }
    }

    private fun drawTab() {
        content.removeAllViews()
        val tabs = ArrayList<Pair<String, String>>()
        tabs.add("mine" to "🛒 My orders")
        if (canApprove()) tabs.add("approve" to "✅ Approve" + countOf("approve"))
        if (roles.optBoolean("accounts")) tabs.add("accounts" to "💰 Accounts" + countOf("accounts"))
        if (roles.optBoolean("delivery")) tabs.add("delivery" to "🚚 Delivery" + countOf("delivery"))
        tabs.add("returns" to "↩ Returns" + (if (roles.optBoolean("admin") || roles.optBoolean("delivery")) countOf("returns") else ""))
        if (roles.optBoolean("admin")) tabs.add("all" to "📋 All orders")
        if (tabs.none { it.first == tab }) tab = "mine"
        content.addView(oTabs(tabs, tab) { tab = it; drawTab() })
        if (roles.optBoolean("admin")) content.addView(oChip("⚙️  Setup: products, customers, offers") { startActivity(Intent(this, OrderSetupActivity::class.java)) })
        when (tab) {
            "mine" -> drawMine()
            "approve" -> drawQueue("approve", "Nothing is waiting for your approval.")
            "accounts" -> drawQueue("accounts", "No order is waiting for accounts.")
            "delivery" -> drawQueue("delivery", "No order is waiting for delivery.")
            "returns" -> drawReturns()
            "all" -> drawAll()
        }
    }

    private fun countOf(k: String): String { val n = queue.optJSONArray(k)?.length() ?: 0; return if (n > 0) " ($n)" else "" }

    private fun orderCard(o: JSONObject, showPerson: Boolean): View {
        val c = oColumn()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(oLabel(o.optString("orderNo"), 16f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(oStatus(o.optString("status")))
        c.addView(top)
        c.addView(oLabel(o.optString("customerName") + "  (" + o.optString("customerCode") + ")", 14f, "#6B352A", true).apply { setPadding(0, dp(4), 0, 0) })
        c.addView(oLabel((if (showPerson) o.optString("name") + " · " else "") + o.optString("date") + "  ·  Total " + OrderFmt.money(o.optDouble("total")) +
            (if (o.optDouble("received") > 0) "  ·  Received " + OrderFmt.money(o.optDouble("received")) else ""), 13f, "#8A5F52"))
        val card = oCard(c)
        card.isClickable = true; card.isFocusable = true
        card.setOnClickListener { showDetail(o.optString("orderNo")) }
        return card
    }

    private fun drawMine() {
        content.addView(oBig("➕  New order") { startActivity(Intent(this, OrderNewActivity::class.java)) })
        val id = ++loadId
        val status = oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        content.addView(status)
        api(JSONObject().put("action", "orderList")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            val list = res.optJSONArray("orders").toObjects()
            if (list.isEmpty()) content.addView(oLabel("You have no orders yet. Tap “New order”.", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
            list.forEach { content.addView(orderCard(it, false)) }
        }
    }

    private fun drawQueue(key: String, empty: String) {
        val list = queue.optJSONArray(key).toObjects()
        if (list.isEmpty()) content.addView(oLabel(empty, 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        list.forEach { content.addView(orderCard(it, true)) }
    }

    private fun drawAll() {
        val chips = listOf("" to "All", "Pending Manager" to "Manager", "Pending Admin" to "Admin", "Pending Accounts" to "Accounts", "With Delivery" to "Delivery", "Ready to Ship" to "Ready", "Shipped" to "Shipped", "Delivered" to "Delivered", "Rejected" to "Rejected")
        content.addView(oTabs(chips, allStatus) { allStatus = it; drawTab() })
        content.addView(oChip("🔎  Search" + (if (allQuery.isEmpty()) "" else ": $allQuery")) {
            askText("Search orders", "Order no, customer or person", allQuery) { allQuery = it; drawTab() }
        })
        val id = ++loadId
        val status = oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        content.addView(status)
        api(JSONObject().put("action", "adminOrders").put("status", allStatus).put("q", allQuery)) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            content.addView(oLabel("${res.optInt("total")} order(s)" + (if (res.optInt("total") > 200) " (newest 200 shown)" else ""), 12f, "#8A5F52").apply { setPadding(dp(6), 0, 0, dp(6)) })
            res.optJSONArray("orders").toObjects().forEach { content.addView(orderCard(it, true)) }
        }
    }

    // ------------------------------------------------------------------ returns
    private fun returnCard(r: JSONObject, actions: Boolean): View {
        val c = oColumn()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(oLabel(r.optString("returnNo") + "  ·  " + r.optString("orderNo"), 15f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(oStatus(r.optString("status")))
        c.addView(top)
        c.addView(oLabel(r.optInt("qty").toString() + " × " + r.optString("product") + "  (" + r.optString("sku") + ")", 14f, "#6B352A", true).apply { setPadding(0, dp(4), 0, 0) })
        c.addView(oLabel(r.optString("customerName") + " · " + r.optString("name") + " · " + r.optString("date") + "\nReason: " + r.optString("reason") +
            (if (r.optString("decidedBy").isNotEmpty()) "\nBy " + r.optString("decidedBy") else ""), 13f, "#8A5F52"))
        if (actions) {
            val st = r.optString("status")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            fun act(text: String, decision: String, needNote: Boolean) {
                row.addView(MaterialButton(this).apply {
                    this.text = text
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                    setOnClickListener {
                        val send = { note: String ->
                            api(JSONObject().put("action", "returnDecide").put("returnNo", r.optString("returnNo")).put("decision", decision).put("note", note)) { res, err ->
                                if (res == null) oToast(err ?: "Could not save.") else { oToast("Saved: " + res.optString("status")); reload() }
                            }
                        }
                        if (needNote) askText("Reject this return?", "Reason (required)", multiline = true) { send(it) } else send("")
                    }
                })
            }
            if (st == "Pending") { act("✅ Approve", "approve", false); act("❌ Reject", "reject", true) }
            if (st == "Approved") act("📦 Mark received", "receive", false)
            if (row.childCount > 0) c.addView(row)
        }
        return oCard(c)
    }

    private fun drawReturns() {
        val manage = roles.optBoolean("admin") || roles.optBoolean("delivery")
        if (manage) {
            val list = queue.optJSONArray("returns").toObjects()
            content.addView(oLabel("NEEDS ACTION", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(dp(6), dp(6), 0, dp(6)) })
            if (list.isEmpty()) content.addView(oLabel("No returns are waiting.", 14f).apply { setPadding(dp(4), dp(4), dp(4), dp(8)) })
            list.forEach { content.addView(returnCard(it, true)) }
            content.addView(oLabel("MY RETURNS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(dp(6), dp(14), 0, dp(6)) })
        }
        val id = ++loadId
        val status = oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        content.addView(status)
        api(JSONObject().put("action", "returnList")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            val list = res.optJSONArray("returns").toObjects()
            if (list.isEmpty()) content.addView(oLabel("You have not returned anything. Open a delivered order to return products.", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
            list.forEach { content.addView(returnCard(it, false)) }
        }
    }

    // ------------------------------------------------------------------ one order
    private fun showDetail(orderNo: String) {
        detailNo = orderNo
        val id = ++loadId
        content.removeAllViews()
        content.addView(oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        api(JSONObject().put("action", "orderGet").put("orderNo", orderNo)) { res, err ->
            if (id != loadId) return@api
            content.removeAllViews()
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); content.addView(oChip("← Back") { reload() }); return@api }
            roles = res.optJSONObject("roles") ?: roles
            drawDetail(res)
        }
    }

    private fun drawDetail(res: JSONObject) {
        val o = res.getJSONObject("order")
        val status = o.optString("status")
        val head = oColumn()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(oLabel(o.optString("orderNo"), 20f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(oStatus(status))
        head.addView(top)
        head.addView(oLabel(o.optString("customerName") + "  (" + o.optString("customerCode") + ")", 15f, "#6B352A", true).apply { setPadding(0, dp(6), 0, 0) })
        head.addView(oLabel("By " + o.optString("name") + " (" + o.optString("username") + ")  ·  " + o.optString("date") + (if (o.optString("note").isNotEmpty()) "\nNote: " + o.optString("note") else ""), 13f, "#8A5F52"))
        head.addView(oLabel("Total " + OrderFmt.money(o.optDouble("total")) + "\nReceived " + OrderFmt.money(o.optDouble("received")) + "   ·   Due " + OrderFmt.money(o.optDouble("due")), 14f, "#3E1D16", true).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(oCard(head))

        val items = oColumn()
        items.addView(oLabel("PRODUCTS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        res.optJSONArray("lines").toObjects().forEach { l ->
            items.addView(oLabel(l.optString("name") + "  (" + l.optString("sku") + ")", 14f, "#3E1D16", true).apply { setPadding(0, dp(8), 0, 0) })
            items.addView(oLabel(l.optInt("qty").toString() + " " + l.optString("unit") + " × " + OrderFmt.money(l.optDouble("price")) + " = " + OrderFmt.money(l.optDouble("amount")), 13f, "#6B352A"))
            if (l.optInt("freeQty") > 0) items.addView(oLabel("🎁 Free: " + l.optInt("freeQty") + " × " + l.optString("freeName").ifEmpty { l.optString("freeSku") }, 13f, "#166534", true))
        }
        content.addView(oCard(items))

        // documents
        if (o.optBoolean("docs")) {
            val d = oColumn()
            d.addView(oLabel("DOCUMENTS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            listOf("invoice" to "📄  Invoice", "challan" to "🚚  Challan").forEachIndexed { i, (kind, text) ->
                row.addView(MaterialButton(this).apply {
                    this.text = text
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) marginEnd = dp(6) else marginStart = dp(6) }
                    setOnClickListener { openDocument(o.optString("orderNo"), kind) }
                })
            }
            d.addView(row)
            d.addView(oLabel("PDF: " + o.optString("invoiceNo") + " and " + o.optString("challanNo"), 12f, "#8A5F52"))
            content.addView(oCard(d))
        }

        // what I can do now
        val act = oColumn()
        var any = false
        val mine = o.optString("username").equals(session.username, ignoreCase = true)
        if ((status == "Pending Manager" && canApprove() && !mine) || (status == "Pending Admin" && roles.optBoolean("admin") && !mine)) {
            any = true
            act.addView(oLabel(if (status == "Pending Manager") "Waiting for the line manager" else "Waiting for the final approval (admin)", 13f, "#B45309", true))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            row.addView(MaterialButton(this).apply {
                text = "✅  Approve"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                setOnClickListener { decide(o.optString("orderNo"), "approve", "") }
            })
            row.addView(MaterialButton(this).apply {
                text = "❌  Reject"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
                setOnClickListener { askText("Reject this order?", "Reason (required)", multiline = true) { decide(o.optString("orderNo"), "reject", it) } }
            })
            act.addView(row)
        }
        if (status == "Pending Accounts" && roles.optBoolean("accounts")) {
            any = true
            act.addView(oLabel("Accounts: enter the amount received", 13f, "#B45309", true))
            val amount = EditText(this).apply {
                hint = "Received amount (0 if none)  ·  total " + OrderFmt.money(o.optDouble("total"))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            act.addView(amount)
            act.addView(oBig("💰  Save and send to delivery") {
                val v = amount.text.toString().trim()
                if (v.isEmpty()) { oToast("Type the received amount (0 if nothing was received)."); return@oBig }
                askText("Payment note", "Cash / bank / bKash … (optional)", message = "Received $v for ${o.optString("orderNo")}.") { note ->
                    api(JSONObject().put("action", "orderReceive").put("orderNo", o.optString("orderNo")).put("received", v).put("note", note)) { r, e ->
                        if (r == null) oToast(e ?: "Could not save.") else { oToast("Saved. The order is now with delivery."); showDetail(o.optString("orderNo")) }
                    }
                }
            })
            act.addView(oChip("❌  Reject this order") {
                askText("Reject this order?", "Reason (required)", multiline = true) {
                    api(JSONObject().put("action", "orderReceive").put("orderNo", o.optString("orderNo")).put("decision", "reject").put("note", it)) { r, e ->
                        if (r == null) oToast(e ?: "Could not save.") else showDetail(o.optString("orderNo"))
                    }
                }
            })
        }
        val next = mapOf("With Delivery" to "Ready to Ship", "Ready to Ship" to "Shipped", "Shipped" to "Delivered")[status]
        if (next != null && roles.optBoolean("delivery")) {
            any = true
            act.addView(oLabel("Delivery", 13f, "#1D4ED8", true))
            act.addView(oBig("🚚  Mark as: $next") {
                askText("Mark as $next?", "Note (optional)", message = o.optString("orderNo") + " · " + o.optString("customerName")) { note ->
                    api(JSONObject().put("action", "orderShip").put("orderNo", o.optString("orderNo")).put("to", next).put("note", note)) { r, e ->
                        if (r == null) oToast(e ?: "Could not save.") else showDetail(o.optString("orderNo"))
                    }
                }
            })
        }
        if (res.optBoolean("canCancel")) {
            any = true
            act.addView(oChip("🚫  Cancel this order") {
                AlertDialog.Builder(this).setTitle("Cancel this order?").setMessage("It has not been fully approved yet, so you can still cancel it.")
                    .setPositiveButton("Cancel order") { _, _ ->
                        api(JSONObject().put("action", "orderCancel").put("orderNo", o.optString("orderNo"))) { r, e ->
                            if (r == null) oToast(e ?: "Could not cancel.") else showDetail(o.optString("orderNo"))
                        }
                    }.setNegativeButton("Keep", null).show()
            })
        }
        val returnable = res.optJSONArray("returnable").toObjects()
        if (returnable.isNotEmpty()) {
            any = true
            act.addView(oChip("↩  Return products") { showReturnForm(o.optString("orderNo"), returnable) })
        }
        if (any) content.addView(oCard(act))

        val rets = res.optJSONArray("returns").toObjects()
        if (rets.isNotEmpty()) {
            val c = oColumn()
            c.addView(oLabel("RETURNS OF THIS ORDER", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
            rets.forEach { r -> c.addView(oLabel(r.optString("returnNo") + ": " + r.optInt("qty") + " × " + r.optString("product") + " — " + r.optString("status"), 13f, "#6B352A").apply { setPadding(0, dp(4), 0, 0) }) }
            content.addView(oCard(c))
        }

        val log = oColumn()
        log.addView(oLabel("HISTORY", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        res.optJSONArray("log").toObjects().reversed().forEach { l ->
            log.addView(oLabel(l.optString("action") + (if (l.optString("note").isNotEmpty()) " — " + l.optString("note") else ""), 14f, "#3E1D16", true).apply { setPadding(0, dp(8), 0, 0) })
            log.addView(oLabel(l.optString("when") + " · " + l.optString("by"), 12f, "#8A5F52"))
        }
        content.addView(oCard(log))
        content.addView(oChip("←  Back to the list") { reload() })
    }

    private fun decide(orderNo: String, decision: String, note: String) {
        api(JSONObject().put("action", "orderDecide").put("orderNo", orderNo).put("decision", decision).put("note", note)) { r, e ->
            if (r == null) oToast(e ?: "Could not save.") else { oToast(if (decision == "approve") "Approved." else "Rejected."); showDetail(orderNo) }
        }
    }

    private fun showReturnForm(orderNo: String, returnable: List<JSONObject>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        col.addView(oLabel("Type how many of each product you are returning (leave 0 for the others).", 13f, "#8A5F52"))
        val boxes = ArrayList<Pair<String, EditText>>()
        returnable.forEach { r ->
            col.addView(oLabel(r.optString("name") + "  (you can return up to " + r.optInt("qty") + ")", 14f, "#3E1D16", true).apply { setPadding(0, dp(10), 0, 0) })
            val et = EditText(this).apply { hint = "0"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6)) }
            col.addView(et); boxes.add(r.optString("sku") to et)
        }
        val reason = EditText(this).apply { hint = "Reason (required)"; minLines = 2; maxLines = 4; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        col.addView(reason)
        AlertDialog.Builder(this).setTitle("Return from $orderNo").setView(android.widget.ScrollView(this).apply { addView(col) })
            .setPositiveButton("Submit return") { _, _ ->
                val items = JSONArray()
                boxes.forEach { (sku, et) -> val q = et.text.toString().trim().toIntOrNull() ?: 0; if (q > 0) items.put(JSONObject().put("sku", sku).put("qty", q)) }
                if (items.length() == 0) { oToast("Type a quantity for at least one product."); return@setPositiveButton }
                api(JSONObject().put("action", "returnCreate").put("orderNo", orderNo).put("items", items).put("reason", reason.text.toString().trim())) { r, e ->
                    if (r == null) oToast(e ?: "Could not send the return.") else { oToast("Return " + r.optString("returnNo") + " sent."); showDetail(orderNo) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ invoice / challan
    private fun openDocument(orderNo: String, kind: String) {
        oToast("Preparing the PDF…")
        api(JSONObject().put("action", "orderDoc").put("orderNo", orderNo).put("kind", kind)) { res, err ->
            if (res == null) { oToast(err ?: "Could not make the document."); return@api }
            try {
                val dir = File(cacheDir, "reports").apply { mkdirs() }
                val f = File(dir, res.optString("name", "$kind.pdf"))
                f.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(view) } catch (e: Exception) {
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Open or share the PDF"))
                }
            } catch (e: Exception) { oToast("Could not open the PDF.") }
        }
    }
}
