package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Night hold: an employee staying away overnight reports where. Submit stays disabled until a real
 * GPS fix, a district, a thana and a real hotel name are all present (the server checks them again).
 */
class NightHoldActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var tvLoc: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var etDistrict: EditText
    private lateinit var etThana: EditText
    private lateinit var etHotel: EditText
    private lateinit var btnSubmit: MaterialButton
    private lateinit var tvMsg: TextView
    private lateinit var cardDone: View
    private lateinit var cardForm: View
    private lateinit var tvDone: TextView
    private lateinit var cardClosed: View
    private lateinit var tvClosed: TextView

    private var location: Location? = null
    private var locAt = 0L
    private var address = ""
    private var district: String? = null
    private var thana: String? = null
    private var submittedToday = false           // tonight's night hold is already in
    private var record: JSONObject? = null         // tonight's night hold, if any
    private var statusKnown = false                // false until the first answer (saved or fresh) has arrived
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    // The night hold hours are set by an admin (default 9:00 PM to 6:00 AM) and arrive with every answer from the server,
    // which also enforces them and is the final judge of the clock.
    private var openHour = 21
    private var closeHour = 6
    private lateinit var tvHours: TextView          // "Open every night from ... to ...": follows the hours the admin set
    private var forDate: String? = null            // set when an admin opened a past night for this person (manual entry)

    private fun showHours() {
        tvHours.text = if (forDate != null) "An admin opened this night for you. Every field and your live location are required."
        else "Open every night from ${hourText(openHour)} to ${hourText(closeHour)}. Every field and your live location are required."
    }

    private fun hourText(h: Int) = "${if (h % 12 == 0) 12 else h % 12}:00 ${if (h < 12) "AM" else "PM"}"

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchLocation()
        else locFailed("Location permission is needed to submit a night hold. Allow it, then tap Retry.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_night)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        forDate = intent.getStringExtra(EXTRA_FOR_DATE)?.takeIf { it.isNotBlank() }
        setupHeader(if (forDate != null) "Manual Night Hold" else "Night Hold") { loadStatus(); if (cardForm.visibility == View.VISIBLE) startLocation() }
        setupBottomNav(Tab.NIGHT)

        tvLoc = findViewById(R.id.tvLoc)
        btnRetry = findViewById(R.id.btnRetryLoc)
        etDistrict = findViewById(R.id.etDistrict)
        etThana = findViewById(R.id.etThana)
        etHotel = findViewById(R.id.etHotel)
        btnSubmit = findViewById(R.id.btnNightSubmit)
        tvMsg = findViewById(R.id.tvNightMsg)
        cardDone = findViewById(R.id.cardDone)
        cardForm = findViewById(R.id.cardForm)
        tvDone = findViewById(R.id.tvDone)
        cardClosed = findViewById(R.id.cardClosed); tvClosed = findViewById(R.id.tvClosed)
        tvHours = findViewById(R.id.tvNightHours)
        showHours()

        etDistrict.setOnClickListener {
            PickerDialog.show(this, "Select district", BdLocations.districts(this)) { picked ->
                if (picked != district) { district = picked; thana = null; etThana.setText("") }
                etDistrict.setText(picked)
                updateButton()
            }
        }
        etThana.setOnClickListener {
            val d = district
            if (d == null) {
                Toast.makeText(this, "Choose a district first", Toast.LENGTH_SHORT).show()
            } else {
                PickerDialog.show(this, "Select thana · $d", BdLocations.thanas(this, d)) { picked ->
                    thana = picked
                    etThana.setText(picked)
                    updateButton()
                }
            }
        }
        etHotel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateButton() }
        })
        btnRetry.setOnClickListener { startLocation() }
        btnSubmit.setOnClickListener { submit() }

        if (forDate != null) { statusKnown = true; renderState(); msg("📝 Manual night hold for $forDate. An admin opened this night for you.", true) }
        else loadStatus()      // shows the right card (form / submitted / closed) from saved data at once, then refreshes it
    }

    override fun onResume() {
        super.onResume()
        if (statusKnown) renderState()
        clock.postDelayed(object : Runnable {            // notice 9:00 PM / 6:00 AM arriving while the screen is open
            override fun run() { if (statusKnown) renderState(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    override fun onPause() {
        super.onPause()
        clock.removeCallbacksAndMessages(null)
    }

    // ---- which night is it, and is the window open? ----

    private fun isoDay(c: Calendar) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)

    private fun windowOpen(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (openHour > closeHour) (h >= openHour || h < closeHour) else (h >= openHour && h < closeHour)
    }

    /** A night is named by the date its evening started: after midnight (before 6 AM) it is still last evening's night. */
    private fun nightKey(): String {
        val c = Calendar.getInstance()
        if (openHour > closeHour && c.get(Calendar.HOUR_OF_DAY) < closeHour) c.add(Calendar.DAY_OF_MONTH, -1)
        return isoDay(c)
    }

    // ---- today's status ----

    private fun loadStatus() {
        if (forDate != null) return
        val yesterday = isoDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })   // after midnight tonight's record is dated yesterday
        apiCached(
            cacheKey = ResponseCache.nightKey(session.username),
            body = JSONObject().put("action", "nights").put("username", session.username)
                .put("from", yesterday),
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> applyStatus(res) },
            onError = { err, hadSaved -> if (!hadSaved) msg("Couldn't check tonight's status: ${err ?: "no connection"}", false) }
        )
    }

    private fun applyStatus(res: JSONObject) {
        res.optJSONObject("window")?.let { openHour = it.optInt("openHour", 21); closeHour = it.optInt("closeHour", 6) }
        showHours()
        val tonight = nightKey()
        val arr = res.optJSONArray("nights")
        var found: JSONObject? = null
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("date") == tonight) found = o
            }
        }
        record = found
        statusKnown = true
        renderState()
    }

    /** Decides which card to show: submitted, the form (window open) or the closed notice. */
    private fun renderState() {
        val rec = record
        when {
            rec != null -> showDone(rec)
            forDate != null || windowOpen() -> showForm()
            else -> showClosed()
        }
    }

    private fun showClosed() {
        submittedToday = false
        cardForm.visibility = View.GONE
        cardDone.visibility = View.GONE
        cardClosed.visibility = View.VISIBLE
        tvClosed.text = "Night hold can be submitted every night from ${hourText(openHour)} to ${hourText(closeHour)}.\n\nCome back at ${hourText(openHour)}."
    }

    private fun showDone(rec: JSONObject) {
        submittedToday = true
        cardForm.visibility = View.GONE
        cardClosed.visibility = View.GONE
        cardDone.visibility = View.VISIBLE
        val addr = rec.optString("address")
        tvDone.text = "🌙 Night hold submitted for ${forDate ?: "tonight"}\n\n🏨 ${rec.optString("hotel")}\n" +
            "📍 ${rec.optString("thana")}, ${rec.optString("district")}\n🕒 ${rec.optString("time")}" +
            (if (addr.isNotEmpty()) "\n$addr" else "") +
            "\n\nOnly one night hold can be submitted per night."
    }

    private fun showForm() {
        val wasForm = cardForm.visibility == View.VISIBLE
        submittedToday = false
        cardDone.visibility = View.GONE
        cardClosed.visibility = View.GONE
        cardForm.visibility = View.VISIBLE
        updateButton()
        if (!wasForm && location == null) startLocation()        // the window just opened (or the screen just appeared): get the GPS fix
    }

    // ---- live location ----

    private fun startLocation() {
        if (submittedToday) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fetchLocation()
        else
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        location = null
        address = ""
        tvLoc.text = "📍 Getting your location…"
        btnRetry.visibility = View.GONE
        updateButton()
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) { locFailed("Location unavailable. Turn on GPS, then tap Retry."); return@addOnSuccessListener }
                if (isMock(loc)) {
                    locFailed("A fake / mock location was detected. Turn off any fake-GPS app, then tap Retry.")
                    return@addOnSuccessListener
                }
                location = loc
                locAt = System.currentTimeMillis()
                val coords = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
                tvLoc.text = "📍 Location locked\n$coords\nFinding address…"
                updateButton()
                Thread {
                    val found = try {
                        @Suppress("DEPRECATION")
                        Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                            ?.firstOrNull()?.getAddressLine(0)
                    } catch (e: Exception) {
                        null
                    }
                    runOnUiThread {
                        if (location === loc) {
                            address = found ?: ""
                            tvLoc.text = "📍 Location locked\n$coords" + (if (address.isNotEmpty()) "\n$address" else "")
                        }
                    }
                }.start()
            }
            .addOnFailureListener { locFailed("Location failed: ${it.message}") }
    }

    private fun locFailed(message: String) {
        location = null
        tvLoc.text = "⚠️ $message"
        btnRetry.visibility = View.VISIBLE
        updateButton()
    }

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider

    // ---- validation + submit ----

    private fun validHotel(raw: String): Boolean {
        val h = raw.trim()
        return h.length >= 3 && h.any { it.isLetter() }
    }

    private fun updateButton() {
        btnSubmit.isEnabled = !submittedToday && location != null && district != null && thana != null &&
            validHotel(etHotel.text.toString())
    }

    private fun msg(text: String, ok: Boolean) {
        tvMsg.text = text
        tvMsg.setTextColor(Color.parseColor(if (ok) "#166534" else "#DC2626"))
    }

    private fun submit() {
        val loc = location
        val d = district
        val t = thana
        val hotel = etHotel.text.toString().trim().replace(Regex("\\s+"), " ")
        if (loc == null) { msg("Waiting for your live location.", false); return }
        if (d == null) { msg("Please select a district.", false); return }
        if (t == null) { msg("Please select a thana.", false); return }
        if (!validHotel(hotel)) { msg("Please enter the actual hotel name.", false); return }
        if (System.currentTimeMillis() - locAt > 10 * 60 * 1000L) {
            msg("Your location is out of date, so it is being refreshed. Tap Submit again in a moment.", false)
            startLocation()
            return
        }

        btnSubmit.isEnabled = false
        msg("Submitting…", true)
        val body = JSONObject().put("action", "nightSubmit")
            .put("username", session.username)
            .put("lat", loc.latitude).put("lng", loc.longitude).put("mock", isMock(loc)).put("address", address)
            .put("district", d).put("thana", t).put("hotel", hotel)
        forDate?.let { body.put("forDate", it) }

        api(body) { res, err ->
            if (res != null) {
                // Saved copies of the Calendar and this screen are now out of date.
                ResponseCache.remove(this, ResponseCache.calKey(session.username), ResponseCache.nightKey(session.username), ResponseCache.homeKey(session.username))
                msg("", true)
                val saved = JSONObject().put("date", res.optString("date")).put("time", res.optString("time"))
                        .put("hotel", hotel).put("thana", t).put("district", d).put("address", address)
                record = saved
                showDone(saved)
            } else {
                msg("❌ $err", false)
                updateButton()
                if (res == null && err?.contains("only between") == true) { record = null; statusKnown = true; renderState() }   // the server says the window is closed
            }
        }
    }
}
