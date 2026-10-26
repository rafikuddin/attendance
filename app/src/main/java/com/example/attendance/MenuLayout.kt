package com.example.attendance

import android.content.Context
import org.json.JSONObject

/** One tab of the Admin Panel: its key (the same words the server uses), icon, name and the id of its tab button. */
class AdminTab(val key: String, val icon: String, val name: String, val viewId: Int)

/** The Admin Panel tabs in the standard order. */
object AdminTabs {
    val all = listOf(
        AdminTab("users", "👥", "Users", R.id.tabUsers),
        AdminTab("notices", "📢", "Notices", R.id.tabNotices),
        AdminTab("holiday", "🎉", "Holiday", R.id.tabHolidays),
        AdminTab("entry", "📝", "Entry", R.id.tabEntry),
        AdminTab("report", "📊", "Report", R.id.tabReport),
        AdminTab("targets", "🎯", "Targets", R.id.tabTarget),
        AdminTab("sms", "📱", "SMS", R.id.tabSms),
        AdminTab("leave", "🌴", "Leave", R.id.tabLeave),
        AdminTab("menu", "🧭", "Menu", R.id.tabMenu),
        AdminTab("app", "📲", "App", R.id.tabApp)
    )
    fun find(key: String) = all.firstOrNull { it.key == key }
}

/** A menu an admin added (Admin Panel > Menu): it opens a web link or shows a text. [where] = "menu", "home" or "both". */
class CustomMenu(val id: String, val icon: String, val label: String, val kind: String, val value: String, val where: String) {
    val key get() = "c:$id"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("icon", icon).put("label", label).put("kind", kind).put("value", value).put("where", where)
    companion object {
        fun from(o: JSONObject) = CustomMenu(o.optString("id"), o.optString("icon").ifEmpty { "⭐" }, o.optString("label"), o.optString("kind"), o.optString("value"), o.optString("where").ifEmpty { "menu" })
    }
}

/** The Home screen sections in the standard order, with the names the Admin Panel shows. */
object HomeBlocks {
    val names = linkedMapOf(
        "status" to "📍  Today's status", "shortcuts" to "📸  Attendance / Leave buttons", "notices" to "🔔  Notices",
        "actions" to "⚡  Quick Actions (Night Hold, Calendar, Reports)", "target" to "🎯  Target vs Collection", "survey" to "📋  Surveys",
        "balance" to "🌴  Leave balance", "night" to "🌙  Night hold this month", "custom" to "⭐  Your own tiles (added menus)"
    )
}

/**
 * The order an admin chose for the side menu ("menu"), its Settings group ("settings") and the Admin Panel tabs ("admin").
 * The server sends it with Home; the phone keeps the last copy so the menu is in the right order even before Home has loaded.
 * Anything the saved order does not mention goes to the end in the standard order, so a new entry never disappears.
 */
object MenuLayout {
    val standard: Map<String, List<String>> = mapOf(
        "menu" to listOf("attendance", "leave", "night", "calendar", "team", "members", "notices", "reports", "target", "survey", "orders"),
        "settings" to listOf("profile", "password", "reminders", "manual", "update", "admin"),
        "admin" to AdminTabs.all.map { it.key },
        "home" to HomeBlocks.names.keys.toList()
    )
    private const val PREFS = "menu_layout"
    private const val KEY = "layout"

    fun save(ctx: Context, layout: JSONObject?) {
        if (layout == null) return
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layout.toString()).apply() } catch (e: Exception) { }
    }

    private fun saved(ctx: Context): JSONObject? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    /** The menus the admin added. */
    fun custom(ctx: Context): List<CustomMenu> {
        val arr = saved(ctx)?.optJSONArray("custom") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> try { CustomMenu.from(arr.getJSONObject(i)).takeIf { it.id.isNotEmpty() && it.label.isNotEmpty() } } catch (e: Exception) { null } }
    }

    /** Is this entry switched off? [key] is "menu:survey", "settings:reminders" or "home:balance". */
    fun hidden(ctx: Context, key: String): Boolean {
        val arr = saved(ctx)?.optJSONArray("hidden") ?: return false
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return true
        return false
    }

    /** The order for one group: saved keys first (known ones only, once each), then whatever is missing. */
    fun order(ctx: Context, group: String): List<String> {
        val std = (standard[group] ?: return emptyList()).let { base ->
            if (group == "menu") base + custom(ctx).filter { it.where != "home" }.map { it.key } else base
        }
        val out = ArrayList<String>()
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            val arr = if (raw == null) null else JSONObject(raw).optJSONArray(group)
            if (arr != null) for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (k in std && k !in out) out.add(k)
            }
        } catch (e: Exception) { out.clear() }
        std.forEach { if (it !in out) out.add(it) }
        return out
    }

    /** Puts the side-menu rows in the chosen order inside each heading; Home stays first and the headings keep their place. */
    fun sort(ctx: Context, items: List<SideItem>): List<SideItem> {
        val orders = mapOf("Menu" to order(ctx, "menu"), "Settings" to order(ctx, "settings"))
        val sections = ArrayList<String>()
        items.forEach { if (it.section !in sections) sections.add(it.section) }
        val out = ArrayList<SideItem>()
        sections.forEach { sec ->
            val rows = items.filter { it.section == sec }
            val o = orders[sec]
            if (o == null) out.addAll(rows)
            else {
                val prefix = if (sec == "Menu") "menu" else "settings"
                val shown = rows.filter { it.key.isEmpty() || !hidden(ctx, "$prefix:${it.key}") }
                out.addAll(shown.withIndex().sortedWith(compareBy({ if (it.value.key.isEmpty()) -1 else o.indexOf(it.value.key).let { i -> if (i < 0) 999 else i } }, { it.index })).map { it.value })
            }
        }
        return out
    }
}
