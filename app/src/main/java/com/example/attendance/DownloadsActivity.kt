package com.example.attendance

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Downloads: PDF files that an admin uploaded. Everybody can 👁 View (opens in the phone's PDF viewer) or ⬇ Download (saved where
 * they choose). Admins also get ➕ Upload a PDF and ✏️ Edit (title, description, hide, delete).
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var isAdmin = false
    private var loadId = 0
    private var pendingSave: JSONObject? = null

    private val pickPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) startUpload(uri) }
    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingSave; pendingSave = null
        if (uri != null && f != null) fetchPdf(f) { file, err ->
            if (file == null) { oToast(err ?: "Could not download the file."); return@fetchPdf }
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException()
                oToast("Saved.")
            } catch (e: Exception) { oToast("Could not save the file there.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Downloads") { load() }
        setupBottomNav(null)
        enforceAccess("downloads")
        content = findViewById(R.id.noticeList)
        isAdmin = AdminFlag.get(this)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        load()
    }

    private fun load() {
        val id = ++loadId
        content.removeAllViews()
        if (isAdmin) content.addView(oBig("➕  Upload a PDF") { pickPdf.launch("application/pdf") })
        val status = oLabel("Loading…", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        content.addView(status)
        api(JSONObject().put("action", if (isAdmin) "adminDownloadList" else "downloadList")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(oLabel(err ?: "Could not load.", 14f, "#DC2626")); return@api }
            val files = res.optJSONArray("files").toObjects()
            if (files.isEmpty()) content.addView(oLabel(if (isAdmin) "No files yet. Tap “Upload a PDF”." else "There are no files to download yet.", 14f).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
            files.forEach { content.addView(fileCard(it)) }
        }
    }

    private fun sizeText(bytes: Int): String = if (bytes >= 1048576) String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0) else Math.max(1, bytes / 1024).toString() + " KB"

    private fun fileCard(f: JSONObject): android.view.View {
        val c = oColumn()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(oLabel("📄  " + f.optString("title"), 16f, "#3E1D16", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!f.optBoolean("active", true)) top.addView(oPill("Hidden", "#B91C1C", "#FEE2E2"))
        c.addView(top)
        if (f.optString("description").isNotEmpty()) c.addView(oLabel(f.optString("description"), 13f, "#6B352A").apply { setPadding(0, dp(4), 0, 0) })
        c.addView(oLabel(f.optString("fileName") + "  ·  " + sizeText(f.optInt("size")) + "  ·  " + f.optString("uploadedOn").take(10) +
            (if (isAdmin && f.optString("uploadedBy").isNotEmpty()) "  ·  by " + f.optString("uploadedBy") else ""), 12f, "#8A5F52").apply { setPadding(0, dp(4), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun button(text: String, onClick: () -> Unit) = row.addView(MaterialButton(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        })
        button("👁  View") { viewPdf(f) }
        button("⬇  Download") { pendingSave = f; saveAs.launch(f.optString("fileName", "file.pdf")) }
        if (isAdmin) button("✏️  Edit") { editFile(f) }
        c.addView(row)
        return oCard(c)
    }

    // ------------------------------------------------------------------ getting a file (piece by piece; kept on the phone for next time)
    private fun fetchPdf(f: JSONObject, done: (File?, String?) -> Unit) {
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val safe = f.optString("fileName", "file.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, f.optString("id") + "_" + safe)
        val total = f.optInt("size")
        if (target.exists() && target.length() == total.toLong()) { done(target, null); return }
        val chunks = f.optInt("chunks")
        val out = ByteArrayOutputStream()
        val dialog = AlertDialog.Builder(this).setTitle("Downloading…").setMessage("0%").setCancelable(false).create()
        dialog.show()
        fun next(i: Int) {
            if (i >= chunks) {
                dialog.dismiss()
                try {
                    if (out.size() != total) throw IllegalStateException()
                    target.writeBytes(out.toByteArray()); done(target, null)
                } catch (e: Exception) { target.delete(); done(null, "The file did not download completely. Please try again.") }
                return
            }
            api(JSONObject().put("action", "downloadPiece").put("id", f.optString("id")).put("index", i)) { res, err ->
                if (res == null) { dialog.dismiss(); done(null, err ?: "Could not download the file."); return@api }
                try { out.write(Base64.decode(res.getString("data"), Base64.DEFAULT)) } catch (e: Exception) { dialog.dismiss(); done(null, "Bad data received."); return@api }
                dialog.setMessage(((i + 1) * 100 / chunks).toString() + "%")
                next(i + 1)
            }
        }
        next(0)
    }

    private fun viewPdf(f: JSONObject) {
        fetchPdf(f) { file, err ->
            if (file == null) { oToast(err ?: "Could not open the file."); return@fetchPdf }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(view) } catch (e: Exception) {
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Open or share the PDF"))
                }
            } catch (e: Exception) { oToast("Could not open the PDF.") }
        }
    }

    // ------------------------------------------------------------------ admin: upload
    private fun startUpload(uri: Uri) {
        var name = "file.pdf"
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } } catch (e: Exception) { }
        oToast("Reading the file…")
        Thread {
            var bytes: ByteArray? = null
            var problem: String? = null
            try {
                val o = ByteArrayOutputStream(); val buf = ByteArray(65536)
                contentResolver.openInputStream(uri)?.use { ins ->
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        o.write(buf, 0, n)
                        if (o.size() > 15 * 1024 * 1024) { problem = "This PDF is bigger than 15 MB."; break }
                    }
                } ?: run { problem = "Could not open the file." }
                if (problem == null) {
                    val b = o.toByteArray()
                    if (b.size < 100 || b[0] != 0x25.toByte() || b[1] != 0x50.toByte() || b[2] != 0x44.toByte() || b[3] != 0x46.toByte()) problem = "This is not a PDF file."
                    else bytes = b
                }
            } catch (e: Exception) { problem = "Could not read the file." }
            runOnUiThread {
                val b = bytes
                if (isFinishing) return@runOnUiThread
                if (problem != null || b == null) { AlertDialog.Builder(this).setTitle("Cannot use this file").setMessage(problem ?: "Could not read the file.").setPositiveButton("OK", null).show(); return@runOnUiThread }
                askText("Title", "Name shown to everyone", name.removeSuffix(".pdf").removeSuffix(".PDF")) { title ->
                    if (title.length < 2) { oToast("Please write a title."); return@askText }
                    askText("Description (optional)", "A few words about the file", multiline = true) { desc -> beginUpload(name, title, desc, b) }
                }
            }
        }.start()
    }

    private fun beginUpload(fileName: String, title: String, desc: String, bytes: ByteArray) {
        val pieceGuess = 524288
        val chunks = (bytes.size + pieceGuess - 1) / pieceGuess
        adminApi(JSONObject().put("action", "adminDownloadBegin").put("title", title).put("description", desc).put("fileName", fileName)
            .put("size", bytes.size).put("chunks", chunks)) { res, err ->
            if (res == null) { oToast(err ?: "Could not start the upload."); return@adminApi }
            val uploadId = res.optString("uploadId"); val piece = res.optInt("pieceBytes", pieceGuess)
            if (piece != pieceGuess) { oToast("The server uses a different piece size. Please update the app."); return@adminApi }
            val dialog = AlertDialog.Builder(this).setTitle("Uploading…").setMessage("0%").setCancelable(false).create()
            dialog.show()
            fun send(i: Int) {
                if (i >= chunks) {
                    api(JSONObject().put("action", "adminDownloadFinish").put("uploadId", uploadId)) { r, e ->
                        dialog.dismiss()
                        if (r == null) oToast(e ?: "Could not finish the upload.") else { oToast("Uploaded."); load() }
                    }
                    return
                }
                val from = i * piece; val to = Math.min(bytes.size, from + piece)
                Thread {
                    val data = Base64.encodeToString(bytes, from, to - from, Base64.NO_WRAP)
                    runOnUiThread {
                        api(JSONObject().put("action", "adminDownloadChunk").put("uploadId", uploadId).put("index", i).put("data", data)) { r, e ->
                            if (r == null) { dialog.dismiss(); oToast(e ?: "The upload failed. Please try again."); return@api }
                            dialog.setMessage(((i + 1) * 100 / chunks).toString() + "%")
                            send(i + 1)
                        }
                    }
                }.start()
            }
            send(0)
        }
    }

    // ------------------------------------------------------------------ admin: edit / hide / delete
    private fun editFile(f: JSONObject) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { hint = "Title"; setText(f.optString("title")); inputType = InputType.TYPE_CLASS_TEXT }
        val desc = EditText(this).apply { hint = "Description (optional)"; setText(f.optString("description")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2 }
        val active = CheckBox(this).apply { text = "Shown to everyone (untick to hide)"; isChecked = f.optBoolean("active", true) }
        col.addView(title); col.addView(desc); col.addView(active)
        AlertDialog.Builder(this).setTitle("Edit file").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                adminApi(JSONObject().put("action", "adminDownloadSave").put("id", f.optString("id")).put("title", title.text.toString().trim())
                    .put("description", desc.text.toString().trim()).put("active", active.isChecked)) { res, err ->
                    if (res == null) oToast(err ?: "Could not save.") else { oToast("Saved."); load() }
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this).setTitle("Delete this file?").setMessage("“" + f.optString("title") + "” is removed for everyone. This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        adminApi(JSONObject().put("action", "adminDownloadDelete").put("id", f.optString("id"))) { res, err ->
                            if (res == null) oToast(err ?: "Could not delete.") else { oToast("Deleted."); load() }
                        }
                    }.setNegativeButton("Keep", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
