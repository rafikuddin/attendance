package com.example.attendance

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Admin screen: add users, reset passwords, post notices and add custom holidays.
 * Only opened for people the server says are admins; the server checks every action again.
 */
class AdminActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private lateinit var tabs: List<TextView>
    private var current = 0
    private var loadId = 0                       // a late answer for a tab the admin already left is ignored
    private var users: List<JSONObject> = emptyList()
    private var pickedHolidayDate: String = ""

    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nice = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn || !AdminFlag.get(this)) { finish(); return }
        setContentView(R.layout.activity_admin)
        setupHeader("Admin") { load() }
        setupBottomNav(null)
        content = findViewById(R.id.adminContent)
        tabs = listOf(findViewById(R.id.tabUsers), findViewById(R.id.tabNotices), findViewById(R.id.tabHolidays))
        tabs.forEachIndexed { i, t -> t.setOnClickListener { select(i) } }
        select(0)
    }

    private fun select(i: Int) {
        current = i
        tabs.forEachIndexed { index, t ->
            val on = index == i
            t.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#6C7293"))
            t.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (on) "#1E2247" else "#E6E9F7"))
            }
        }
        load()
    }

    private fun load() {
        when (current) {
            0 -> loadUsers()
            1 -> loadNotices()
            else -> loadHolidays()
        }
    }

    // ---------- small view helpers ----------

    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun note(text: String) = label(text, 14f, "#6C7293", false).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }

    private fun heading(text: String) = label(text, 13f, "#1E2247", true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), dp(16), dp(6), dp(8))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(bg)) }
    }

    private fun cardOf(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun column(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
    }

    private fun bigButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        inputType = type
    }

    private fun chipButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.parseColor("#1E2247"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E6E9F7")) }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun scrollable(vararg views: View): ScrollView {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            views.forEach { addView(it) }
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    /** Home and Calendar keep a saved copy; after an admin change this phone's copies are out of date. */
    private fun dropSavedCopies() {
        ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
    }

    // ---------- USERS ----------

    private fun loadUsers() {
        val id = ++loadId
        content.removeAllViews()
        content.addView(bigButton("➕  Add new user") { addUserDialog() })
        val status = note("Loading users…")
        content.addView(status)
        api(JSONObject().put("action", "adminUsers")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load users.")); return@api }
            val arr = res.optJSONArray("users")
            val list = ArrayList<JSONObject>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            users = list
            // Search by name, username or manager. The list below is rebuilt on every keystroke.
            val search = EditText(this).apply {
                hint = "🔍  Search name, username or manager"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                maxLines = 1; isSingleLine = true; textSize = 15f
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E6E9F7")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            val countLine = heading("")
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(search); content.addView(countLine); content.addView(results)
            fun show(q: String) {
                val needle = q.trim().lowercase(Locale.getDefault())
                val shown = if (needle.isEmpty()) list else list.filter {
                    it.optString("name").lowercase(Locale.getDefault()).contains(needle) ||
                        it.optString("username").lowercase(Locale.getDefault()).contains(needle) ||
                        it.optString("manager").lowercase(Locale.getDefault()).contains(needle)
                }
                countLine.text = if (needle.isEmpty()) "${list.size} USERS" else "${shown.size} OF ${list.size} USERS"
                results.removeAllViews()
                if (shown.isEmpty()) results.addView(note("No user matches \"${q.trim()}\"."))
                else shown.forEach { results.addView(userCard(it)) }
            }
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { show(e?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            show("")
        }
    }

    private fun userCard(o: JSONObject): View {
        val username = o.optString("username")
        val col = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(label(o.optString("name"), 16f, "#111827", true))
        val mgr = o.optString("manager")
        names.addView(label("@$username" + if (mgr.isNotEmpty()) "  ·  Manager: $mgr" else "", 13f, "#6B7280", false))
        top.addView(names)
        if (o.optBoolean("isAdmin")) top.addView(pill("Admin", "#5B21B6", "#EDE9FE"))
        col.addView(top)
        if (!o.optBoolean("active", true)) col.addView(label("⛔ No password set — this account cannot sign in", 12f, "#B91C1C", false).apply { setPadding(0, dp(6), 0, 0) })
        else if (o.optBoolean("mustChange")) col.addView(label("⏳ Has not chosen their own password yet", 12f, "#B45309", false).apply { setPadding(0, dp(6), 0, 0) })
        if (!username.equals(session.username, ignoreCase = true)) {
            col.addView(MaterialButton(this).apply {
                text = "🔑  Reset password"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                setOnClickListener { resetDialog(o) }
            })
        }
        return cardOf(col)
    }

    private fun addUserDialog() {
        val etUser = field("Username (letters, numbers, . _ -)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val etName = field("Full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        var manager = ""
        val btnManager = chipButton("Manager: none  ▾") {}
        btnManager.setOnClickListener {
            val items = ArrayList<String>()
            items.add("— No manager —")
            users.forEach { items.add("${it.optString("name")}  (${it.optString("username")})") }
            AlertDialog.Builder(this).setTitle("Choose the manager")
                .setItems(items.toTypedArray()) { _, which ->
                    manager = if (which == 0) "" else users[which - 1].optString("username")
                    btnManager.text = if (which == 0) "Manager: none  ▾" else "Manager: $manager  ▾"
                }.show()
        }
        val etPass = field("Temporary password (blank = make one for me)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val cbAdmin = CheckBox(this).apply { text = "Make this person an admin" }
        val etAdminPw = field("Your own password (to confirm)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val error = label("", 14f, "#DC2626", false)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add new user")
            .setView(scrollable(etUser, etName, btnManager, etPass, cbAdmin, etAdminPw, error))
            .setPositiveButton("Create", null)       // set below so errors don't close the dialog
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = etUser.text.toString().trim()
                val n = etName.text.toString().trim()
                val adminPw = etAdminPw.text.toString()
                when {
                    u.isEmpty() -> error.text = "Enter a username"
                    n.isEmpty() -> error.text = "Enter the full name"
                    adminPw.isEmpty() -> error.text = "Enter your own password to confirm"
                    else -> {
                        error.text = "Creating…"
                        val body = JSONObject().put("action", "adminAddUser")
                            .put("newUsername", u).put("name", n).put("manager", manager)
                            .put("password", etPass.text.toString().trim())
                            .put("makeAdmin", cbAdmin.isChecked).put("adminPassword", adminPw)
                        api(body) { res, err ->
                            if (res != null) {
                                dialog.dismiss()
                                val temp = res.optString("tempPassword")
                                if (temp.isNotEmpty()) showTempPassword("User created", res.optString("username"), temp)
                                else toast("User created. They choose their own password when they first sign in.")
                                loadUsers()
                            } else error.text = err
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun resetDialog(user: JSONObject) {
        val target = user.optString("username")
        val info = label("Set a temporary password for ${user.optString("name")} (@$target). They will be signed out everywhere and must choose their own password next time.", 14f, "#6C7293", false)
        val etPass = field("Temporary password (blank = make one for me)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val etAdminPw = field("Your own password (to confirm)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Reset password")
            .setView(scrollable(info, etPass, etAdminPw, error))
            .setPositiveButton("Reset", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val adminPw = etAdminPw.text.toString()
                if (adminPw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                error.text = "Resetting…"
                val body = JSONObject().put("action", "adminResetPassword").put("targetUsername", target)
                    .put("password", etPass.text.toString().trim()).put("adminPassword", adminPw)
                api(body) { res, err ->
                    if (res != null) {
                        dialog.dismiss()
                        val temp = res.optString("tempPassword")
                        if (temp.isNotEmpty()) showTempPassword("Password reset", target, temp)
                        else toast("Password reset for @$target.")
                        loadUsers()
                    } else error.text = err
                }
            }
        }
        dialog.show()
    }

    /** The server shows a made-up password only once, so it is displayed large with a Copy button. */
    private fun showTempPassword(title: String, username: String, password: String) {
        val tv = TextView(this).apply {
            text = password; textSize = 28f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setTextIsSelectable(true); setTextColor(Color.parseColor("#1E2247"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Username: $username\n\nGive the person this temporary password. It is shown only now. They choose their own password when they sign in.")
            .setView(tv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("password", password))
                toast("Copied")
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // ---------- NOTICES ----------

    private fun loadNotices() {
        val id = ++loadId
        content.removeAllViews()

        val etTitle = field("Title", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val etMessage = field("Message", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { minLines = 3 }
        val etDays = field("Show for how many days (default 7)", InputType.TYPE_CLASS_NUMBER)
        val msg = label("", 14f, "#DC2626", false)
        val form = column()
        form.addView(label("New notice", 16f, "#111827", true))
        form.addView(etTitle); form.addView(etMessage); form.addView(etDays)
        form.addView(bigButton("📢  Post to everyone") {
            val t = etTitle.text.toString().trim()
            val m = etMessage.text.toString().trim()
            if (t.length < 2 || m.length < 2) { msg.setTextColor(Color.parseColor("#DC2626")); msg.text = "Enter a title and a message"; return@bigButton }
            msg.setTextColor(Color.parseColor("#6C7293")); msg.text = "Posting…"
            val body = JSONObject().put("action", "adminPostNotice").put("title", t).put("message", m)
                .put("days", etDays.text.toString().trim().toIntOrNull() ?: 7)
            api(body) { res, err ->
                if (res != null) {
                    toast("Notice posted. Everyone sees it on their Home screen.")
                    dropSavedCopies()
                    loadNotices()
                } else { msg.setTextColor(Color.parseColor("#DC2626")); msg.text = err }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))

        val status = note("Loading notices…")
        content.addView(status)
        api(JSONObject().put("action", "notices")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load notices.")); return@api }
            val arr = res.optJSONArray("notices")
            val n = arr?.length() ?: 0
            content.addView(heading("ACTIVE NOTICES ($n)"))
            if (arr == null || n == 0) { content.addView(note("No active notices.")); return@api }
            for (i in 0 until n) content.addView(noticeCard(arr.getJSONObject(i)))
        }
    }

    private fun noticeCard(o: JSONObject): View {
        val col = column()
        col.addView(label(o.optString("title"), 16f, "#111827", true))
        col.addView(label(o.optString("message"), 14f, "#374151", false).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(label("Posted ${o.optString("date")} by ${o.optString("postedBy")}" +
            (if (o.optString("expires").isNotEmpty()) " · visible until ${o.optString("expires")}" else ""), 12f, "#6C7293", false).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(MaterialButton(this).apply {
            text = "🗑  Remove"; setBackgroundColor(Color.parseColor("#DC2626"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener {
                AlertDialog.Builder(this@AdminActivity).setTitle("Remove this notice?")
                    .setMessage(o.optString("title"))
                    .setPositiveButton("Remove") { _, _ ->
                        api(JSONObject().put("action", "adminDeleteNotice").put("id", o.optString("id"))) { res, err ->
                            if (res != null) { dropSavedCopies(); loadNotices() } else toast(err ?: "Could not remove it")
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        return cardOf(col)
    }

    // ---------- HOLIDAYS ----------

    private fun loadHolidays() {
        val id = ++loadId
        content.removeAllViews()
        pickedHolidayDate = ""

        val btnDate = chipButton("📅  Choose the date") {}
        btnDate.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                pickedHolidayDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                val shown = try { nice.format(iso.parse(pickedHolidayDate)!!) } catch (e: Exception) { pickedHolidayDate }
                btnDate.text = "📅  $shown"
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val etName = field("Holiday name (for example Eid Holiday)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val msg = label("", 14f, "#DC2626", false)
        val form = column()
        form.addView(label("Add a holiday", 16f, "#111827", true))
        form.addView(label("A holiday works like Friday: nobody is counted absent that day.", 13f, "#6C7293", false))
        form.addView(btnDate); form.addView(etName)
        form.addView(bigButton("🎉  Add holiday") {
            val name = etName.text.toString().trim()
            if (pickedHolidayDate.isEmpty()) { msg.text = "Choose the date first"; return@bigButton }
            if (name.length < 2) { msg.text = "Enter the holiday name"; return@bigButton }
            msg.setTextColor(Color.parseColor("#6C7293")); msg.text = "Saving…"
            api(JSONObject().put("action", "adminAddHoliday").put("date", pickedHolidayDate).put("name", name)) { res, err ->
                if (res != null) { toast("Holiday added"); dropSavedCopies(); loadHolidays() }
                else { msg.setTextColor(Color.parseColor("#DC2626")); msg.text = err }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))

        val status = note("Loading holidays…")
        content.addView(status)
        api(JSONObject().put("action", "holidays")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load holidays.")); return@api }
            val arr = res.optJSONArray("holidays")
            val n = arr?.length() ?: 0
            content.addView(heading("HOLIDAYS ($n)"))
            if (arr == null || n == 0) { content.addView(note("No custom holidays yet.")); return@api }
            val today = iso.format(Calendar.getInstance().time)
            for (i in n - 1 downTo 0) content.addView(holidayCard(arr.getJSONObject(i), today))   // newest date first
        }
    }

    private fun holidayCard(o: JSONObject, today: String): View {
        val date = o.optString("date")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(label(o.optString("name"), 16f, "#111827", true))
        val shown = try { nice.format(iso.parse(date)!!) } catch (e: Exception) { date }
        text.addView(label(shown + if (date < today) "  ·  past" else "", 13f, "#6B7280", false))
        row.addView(text)
        row.addView(TextView(this).apply {
            this.text = "✕"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#DC2626"))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                AlertDialog.Builder(this@AdminActivity).setTitle("Remove this holiday?")
                    .setMessage("${o.optString("name")} · $shown\n\nThat day will count as a normal working day again.")
                    .setPositiveButton("Remove") { _, _ ->
                        api(JSONObject().put("action", "adminDeleteHoliday").put("date", date)) { res, err ->
                            if (res != null) { dropSavedCopies(); loadHolidays() } else toast(err ?: "Could not remove it")
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        return cardOf(row)
    }
}
