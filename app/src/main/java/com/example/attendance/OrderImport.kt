package com.example.attendance

import org.json.JSONObject

/** Turns the rows of the Product and Customer Excel files into what the server needs. Pure Kotlin. */
object OrderImport {
    class Result(val rows: List<JSONObject>, val skipped: Int)

    private fun key(h: String) = h.lowercase().filter { it.isLetterOrDigit() }

    private fun colOf(keys: List<String>, names: List<String>): Int = names.map { keys.indexOf(it) }.firstOrNull { it >= 0 } ?: -1

    /** The header row (one of the first 15 rows) that has every required column; returns its row index and normalised names. */
    private fun header(table: List<List<String>>, required: List<List<String>>, what: String): Pair<Int, List<String>> {
        for (i in 0 until minOf(15, table.size)) {
            val keys = table[i].map { key(it) }
            if (required.all { colOf(keys, it) >= 0 }) return Pair(i, keys)
        }
        throw IllegalArgumentException("This does not look like the $what file. The first row must have the columns: " +
            required.joinToString(", ") { it[0] } + ".")
    }

    private fun clean(s: String): String {
        val t = s.trim()
        return if (Regex("^-?\\d+\\.0+$").matches(t)) t.substringBefore('.') else t
    }

    private val skuNames = listOf("sku", "skucode", "productcode", "itemcode", "code")
    private val nameNames = listOf("name", "productname", "itemname", "description", "product")
    private val priceNames = listOf("price", "unitprice", "rate", "tp", "tradeprice", "mrp")
    private val unitNames = listOf("unit", "uom", "packsize")

    fun parseProducts(table: List<List<String>>): Result {
        val (h, keys) = header(table, listOf(skuNames, nameNames, priceNames), "Product")
        val c = colOf(keys, skuNames); val n = colOf(keys, nameNames); val p = colOf(keys, priceNames); val u = colOf(keys, unitNames)
        val out = ArrayList<JSONObject>(); var skipped = 0
        for (i in h + 1 until table.size) {
            val r = table[i]
            if (r.all { it.isBlank() }) continue
            val sku = clean(r.getOrElse(c) { "" }); val name = r.getOrElse(n) { "" }.trim()
            val price = TargetImport.amount(r.getOrElse(p) { "" })
            if (sku.isEmpty() || name.isEmpty() || price == null || price < 0) { skipped++; continue }
            out.add(JSONObject().put("sku", sku).put("name", name).put("price", price).put("unit", if (u >= 0) r.getOrElse(u) { "" }.trim() else ""))
        }
        if (out.isEmpty()) throw IllegalArgumentException("No usable rows found (each row needs a SKU, a Name and a Price).")
        return Result(out, skipped)
    }

    private val codeNames = listOf("code", "customercode", "dealercode", "customerid")
    private val custNameNames = listOf("name", "customername", "dealername", "customer")
    private val addressNames = listOf("address", "customeraddress", "location")
    private val phoneNames = listOf("phone", "mobile", "contact", "phonenumber", "mobilenumber")
    private val ownerNames = listOf("username", "employeeid", "eid", "salesperson", "owner", "user", "userid", "assignedto")

    fun parseCustomers(table: List<List<String>>): Result {
        val (h, keys) = header(table, listOf(codeNames, custNameNames), "Customer")
        val c = colOf(keys, codeNames); val n = colOf(keys, custNameNames)
        val a = colOf(keys, addressNames); val ph = colOf(keys, phoneNames); val o = colOf(keys, ownerNames)
        val out = ArrayList<JSONObject>(); var skipped = 0
        for (i in h + 1 until table.size) {
            val r = table[i]
            if (r.all { it.isBlank() }) continue
            val code = clean(r.getOrElse(c) { "" }); val name = r.getOrElse(n) { "" }.trim()
            if (code.isEmpty() || name.isEmpty()) { skipped++; continue }
            out.add(JSONObject().put("code", code).put("name", name)
                .put("address", if (a >= 0) r.getOrElse(a) { "" }.trim() else "")
                .put("phone", if (ph >= 0) clean(r.getOrElse(ph) { "" }) else "")
                .put("owner", if (o >= 0) clean(r.getOrElse(o) { "" }) else ""))
        }
        if (out.isEmpty()) throw IllegalArgumentException("No usable rows found (each row needs a Code and a Name).")
        return Result(out, skipped)
    }
}
