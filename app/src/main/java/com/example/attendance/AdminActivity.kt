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
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
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

    // The APK the admin picks to publish as the new version.
    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) prepareApk(uri)
    }

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
        tabs = listOf(findViewById(R.id.tabUsers), findViewById(R.id.tabNotices), findViewById(R.id.tabHolidays), findViewById(R.id.tabApp))
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
            2 -> loadHolidays()
            else -> loadApp()
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
        val viewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        viewRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "👤 Profile"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                startActivity(android.content.Intent(this@AdminActivity, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_USER, username).putExtra(ProfileActivity.EXTRA_NAME, o.optString("name")))
            }
        })
        viewRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📅 Calendar"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            setOnClickListener {
                startActivity(android.content.Intent(this@AdminActivity, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_USER, username).putExtra(CalendarActivity.EXTRA_NAME, o.optString("name")))
            }
        })
        col.addView(viewRow)
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
            // A pick-one list with a search box: type part of a name or username to narrow it down.
            val none = "— No manager —"
            val labels = ArrayList<String>()
            labels.add(none)
            users.forEach { labels.add("${it.optString("name")}  (${it.optString("username")})") }
            PickerDialog.show(this, "Choose the manager", labels) { picked ->
                val which = labels.indexOf(picked)
                manager = if (which <= 0) "" else users[which - 1].optString("username")
                btnManager.text = if (which <= 0) "Manager: none  ▾" else "Manager: $manager  ▾"
            }
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

    // ---------- APP (publish a new version for everyone) ----------

    private fun loadApp() {
        val id = ++loadId
        content.removeAllViews()
        content.addView(heading("THIS PHONE"))
        content.addView(cardOf(column().apply {
            addView(label("Installed: ${AppUpdate.installedName(this@AdminActivity)}  (build ${AppUpdate.installedCode(this@AdminActivity)})", 15f, "#111827", true))
        }))
        content.addView(heading("OFFERED TO EVERYONE"))
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(holder)
        holder.addView(note("Loading…"))
        content.addView(bigButton("⬆️  Upload new version (APK)") { pickApk.launch("*/*") })
        content.addView(note("How it works: build the APK on GitHub and download it to this phone, then upload it here. Everyone signed in sees an Update button on Home and installs it from inside the app. The file must be signed with the same key as the installed app."))
        api(JSONObject().put("action", "appInfo")) { res, err ->
            if (id != loadId) return@api
            holder.removeAllViews()
            if (res == null) { holder.addView(note(err ?: "Could not load the published version.")); return@api }
            val u = res.optJSONObject("update")
            if (u == null) { holder.addView(note("Nothing is published yet.")); return@api }
            val col = column()
            col.addView(label("Version ${u.optString("versionName")}  (build ${u.optLong("versionCode")})", 16f, "#111827", true))
            col.addView(label("%.1f MB · uploaded ${u.optString("uploadedOn")}".format(u.optLong("size") / 1048576.0), 13f, "#6B7280", false))
            if (u.optString("notes").isNotEmpty()) col.addView(label(u.optString("notes"), 13f, "#374151", false).apply { setPadding(0, dp(6), 0, 0) })
            col.addView(chipButton("Stop offering this update") { removeUpdateDialog() })
            holder.addView(cardOf(col))
        }
    }

    /** Copies the chosen file into the app's cache while computing its fingerprint, then reads the version out of the APK itself. */
    private fun prepareApk(uri: android.net.Uri) {
        toast("Reading the file…")
        val app = applicationContext
        Thread {
            var problem: String? = null
            var sha = ""
            var size = 0L
            var name = ""
            var code = 0L
            try {
                AppUpdate.cleanUp(app)
                val f = AppUpdate.uploadFile(app)
                val digest = MessageDigest.getInstance("SHA-256")
                val limit = 40L * 1024 * 1024
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > limit) { problem = "That file is too big to be the app."; break }
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                } ?: run { problem = "That file could not be opened." }
                if (problem == null) {
                    @Suppress("DEPRECATION")
                    val pi = packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                    when {
                        pi == null -> problem = "That file is not an Android app (APK)."
                        pi.packageName != packageName -> problem = "That APK is not the SAM BOND Attendance app."
                        else -> { name = pi.versionName ?: ""; code = AppUpdate.codeOf(pi); sha = AppUpdate.hex(digest.digest()) }
                    }
                }
            } catch (e: Exception) {
                problem = "That file could not be read."
            }
            val p = problem
            runOnUiThread {
                if (p != null) { AppUpdate.cleanUp(app); AlertDialog.Builder(this).setTitle("Cannot use this file").setMessage(p).setPositiveButton("OK", null).show() }
                else confirmUpload(name, code, size, sha)
            }
        }.start()
    }

    private fun confirmUpload(name: String, code: Long, size: Long, sha: String) {
        val installed = AppUpdate.installedCode(this)
        val warn = when {
            code < installed -> "\n\n⚠️ This is OLDER than the version on this phone (build $installed). Phones that already have a newer build will not be offered it."
            code == installed -> "\n\n⚠️ This is the same build as the one on this phone, so nobody will be offered an update unless their phone has an older build."
            else -> ""
        }
        val info = label("Version $name (build $code)\n%.1f MB".format(size / 1048576.0) + "\n\nEveryone on an older build will be offered this update." + warn, 14f, "#374151", false)
        val etNotes = field("What's new? (optional, shown to users)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val etPw = field("Your own password (to confirm)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this).setTitle("Publish this version?")
            .setView(scrollable(info, etNotes, etPw, error))
            .setPositiveButton("Publish", null)
            .setNegativeButton("Cancel") { _, _ -> AppUpdate.cleanUp(applicationContext) }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                dialog.dismiss()
                upload(name, code, size, sha, etNotes.text.toString().trim(), pw)
            }
        }
        dialog.show()
    }

    /** Uploads the picked APK in 512 KB pieces, then publishes it. Runs in the background with a progress bar. */
    private fun upload(name: String, code: Long, size: Long, sha: String, notes: String, adminPw: String) {
        val chunks = ((size + AppUpdate.PIECE - 1) / AppUpdate.PIECE).toInt()
        var cancelled = false
        val box = AppUpdate.ProgressBox(this, "Publishing update", chunks + 1) { cancelled = true }
        box.set(0, "Starting…")
        val app = applicationContext
        Thread {
            var error: String? = null
            var ended = false
            // One call with up to 3 tries on network trouble. A server answer (even an error) is final.
            fun send(body: JSONObject): JSONObject? {
                for (attempt in 1..3) {
                    try {
                        val r = postJson(app, JSONObject(body.toString()))
                        if (r.optBoolean("auth")) { ended = true; error = "Your session ended. Please sign in again."; return null }
                        if (!r.optBoolean("ok")) { error = r.optString("error", "The server refused the upload."); return null }
                        return r
                    } catch (e: Exception) { /* try again */ }
                }
                error = "Upload failed. Check your internet and try again."
                return null
            }
            try {
                val begin = send(JSONObject().put("action", "adminAppBegin").put("versionCode", code).put("versionName", name)
                    .put("notes", notes).put("size", size).put("chunks", chunks).put("sha256", sha).put("adminPassword", adminPw))
                if (begin != null) {
                    val uploadId = begin.getString("uploadId")
                    FileInputStream(AppUpdate.uploadFile(app)).use { input ->
                        val buf = ByteArray(AppUpdate.PIECE)
                        for (i in 0 until chunks) {
                            if (cancelled) { error = "cancelled"; break }
                            var filled = 0
                            while (filled < buf.size) {
                                val n = input.read(buf, filled, buf.size - filled)
                                if (n < 0) break
                                filled += n
                            }
                            val piece = Base64.encodeToString(buf, 0, filled, Base64.NO_WRAP)
                            if (send(JSONObject().put("action", "adminAppChunk").put("uploadId", uploadId).put("index", i).put("data", piece)) == null) break
                            box.set(i + 1, "Uploading… ${i + 1} of $chunks")
                        }
                    }
                    if (error == null) {
                        box.set(chunks, "Publishing…")
                        send(JSONObject().put("action", "adminAppFinish").put("uploadId", uploadId))
                    }
                }
            } catch (e: Exception) {
                if (error == null) error = "Upload failed. Please try again."
            }
            AppUpdate.cleanUp(app)
            val e = error
            box.close()
            runOnUiThread {
                when {
                    ended -> endSession()
                    e == "cancelled" -> toast("Upload cancelled. Nothing was published.")
                    e != null -> AlertDialog.Builder(this).setTitle("Upload failed").setMessage(e).setPositiveButton("OK", null).show()
                    else -> {
                        AlertDialog.Builder(this).setTitle("Published ✅")
                            .setMessage("Version $name is now offered to everyone. They see an Update button on Home next time they open or refresh the app.")
                            .setPositiveButton("OK", null).show()
                        loadApp()
                    }
                }
            }
        }.start()
    }

    private fun removeUpdateDialog() {
        val etPw = field("Your own password (to confirm)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this).setTitle("Stop offering this update?")
            .setView(scrollable(label("Phones will no longer be offered this version. The uploaded file is deleted.", 14f, "#6C7293", false), etPw, error))
            .setPositiveButton("Stop offering", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                error.text = "Working…"
                api(JSONObject().put("action", "adminAppRemove").put("adminPassword", pw)) { res, err ->
                    if (res != null) { dialog.dismiss(); toast("No update is offered now."); loadApp() } else error.text = err
                }
            }
        }
        dialog.show()
    }
}
