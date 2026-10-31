package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.location.Geocoder
import android.location.Location
import android.media.ExifInterface
import android.os.Bundle
import android.util.Size
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AttendanceActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var previewView: PreviewView
    private lateinit var imgSelfie: ImageView
    private lateinit var tvLive: TextView
    private lateinit var tvSummary: TextView
    private lateinit var cardSummary: MaterialCardView
    private lateinit var btnCapture: Button
    private lateinit var btnSubmit: Button

    private var imageCapture: ImageCapture? = null
    // Live check (an admin can switch it off: Admin Panel > Entry): when on, the Capture Selfie button only appears after a face in view
    // has blinked / turned / smiled (see Liveness.kt). The last setting the server sent is remembered so the camera starts the right way.
    private lateinit var tvLiveness: TextView
    private var liveness: Liveness? = null
    private var liveOk = false
    private var liveRequired = true
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var photoFile: File? = null
    private var captureTime: Date? = null
    private var location: Location? = null
    private var address: String = "Address unavailable"
    private var entryHidden = false           // today is already Present / Late / Absent: no camera, no Submit
    private var forDate: String? = null          // set when an admin opened a past day for this person (manual entry)

    // Driver (Admin Panel > Users > Menu access > Extra rights > Driver): after the selfie, the BACK camera takes a picture of the car meter.
    // The phone reads the km number from it (MeterCheck.kt); a driver's Submit button only shows after that. The server insists on the picture too.
    private lateinit var cardMeter: MaterialCardView
    private lateinit var previewMeter: PreviewView
    private lateinit var imgMeter: ImageView
    private lateinit var tvMeterIdle: TextView
    private lateinit var tvMeterHint: TextView
    private lateinit var btnTorch: TextView
    private lateinit var btnMeter: Button
    private var isDriver = false                 // the admin gave this person the Driver right (the server tells on every Home / Attendance load)
    private var meterCheckOn = true              // the phone must read a km number from the meter picture (the admin can switch that off: Admin Panel > Entry)
    private var meterCapture: ImageCapture? = null
    private var meterCamera: Camera? = null
    private var meterFile: File? = null          // the car meter picture on the phone
    private var meterOk = false                  // the picture is taken AND verified: only then does a driver's Submit button show
    private var meterKm = ""                     // the km number the phone read from it ("" when none could be read)
    private var meterBusy = false                // taking or checking the picture right now
    private var meterLive = false                // the back camera is on and shows live in the meter card
    private var frontOn = false                  // the front camera (the selfie) is the one switched on; false while the back camera (car meter) is on, or none
    private var torchOn = false
    private var torchAvailable = false
    private val watchHandler = Handler(Looper.getMainLooper())
    private val meterIdleDefault = "🚗  Next: a picture of the car meter\n\nIt opens as soon as your selfie is taken."
    private var meterNote: String? = null        // what the meter card says while it has no live picture ("opening ...", or why the camera could not start)
    private var meterStarting = false            // the back camera is being switched on right now

    // Driver, DAY END: once today's attendance is marked, the same meter card works as the Day End card. The driver opens the back camera, takes a new picture of
    // the car meter, the phone reads the km number from it (same check as in the morning), and only then the Submit Day End button shows. Once a day.
    private lateinit var btnDayEnd: Button
    private var attendedToday = false            // today's attendance is marked (from the server's answer)
    private var dayEndDone = false               // today's day end is submitted already
    private var dayEndSending = false            // the day end is being sent right now
    private var morningKm = ""                   // the km number of this morning's car meter picture ("" when none was read)
    private val meterEndIdle = "🏁  Day end\n\nWhen your work day is over, tap Start Day End and take a picture of the car meter."

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.values.all { granted -> granted }) startCamera()
        else toast("Camera & location permissions are required") }

    // The day end only needs the camera (no location): its own permission question, which opens the back camera for the car meter.
    private val endPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMeterCamera() else toast("The camera permission is required for the day end") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_attendance)
        previewView = findViewById(R.id.previewView)
        imgSelfie = findViewById(R.id.imgSelfie)
        tvLiveness = findViewById(R.id.tvLiveness)
        tvLive = findViewById(R.id.tvLive)
        liveRequired = getSharedPreferences("live", MODE_PRIVATE).getBoolean("required", true)
        tvSummary = findViewById(R.id.tvSummary)
        cardSummary = findViewById(R.id.cardSummary)
        forDate = intent.getStringExtra(EXTRA_FOR_DATE)?.takeIf { it.isNotBlank() }
        setupHeader(if (forDate != null) "Manual Attendance" else "Mark Attendance")
        setupBottomNav(Tab.ATTENDANCE)
        enforceAccess("attendance")
        btnCapture = findViewById(R.id.btnCapture)
        btnSubmit = findViewById(R.id.btnSubmit)
        cardMeter = findViewById(R.id.cardMeter)
        previewMeter = findViewById(R.id.previewMeter)
        imgMeter = findViewById(R.id.imgMeter)
        tvMeterIdle = findViewById(R.id.tvMeterIdle)
        tvMeterHint = findViewById(R.id.tvMeterHint)
        btnTorch = findViewById(R.id.btnTorch)
        btnMeter = findViewById(R.id.btnMeter)
        btnDayEnd = findViewById(R.id.btnDayEnd)
        // the last answer of the server about the Driver right: the screen starts the right way, the fresh answer corrects it a moment later
        val livePrefs = getSharedPreferences("live", MODE_PRIVATE)
        isDriver = livePrefs.getBoolean("driver_${session.username}", false)
        meterCheckOn = livePrefs.getBoolean("meterCheck", true)

        applyCaptureVisibility()                      // hidden until the live check is passed (when the check is on)
        btnCapture.setOnClickListener {
            if (photoFile != null) retake() else captureSelfie()
        }
        btnSubmit.setOnClickListener { submit() }
        btnMeter.setOnClickListener {
            when {
                meterFile != null -> retakeMeter()
                meterLive -> captureMeter()
                isEndMode() -> openEndCamera()                // the day end: the camera opens when the driver asks for it
                else -> captureMeter()
            }
        }
        btnDayEnd.setOnClickListener { submitDayEnd() }
        btnTorch.setOnClickListener { toggleTorch() }
        setupMeterFocus()
        applyMeterUi()
        applySubmitVisibility()
        if (forDate == null) loadTodayStatus() else findViewById<View>(R.id.atStatusCard).visibility = View.GONE   // a manual day is not "today"

        forDate?.let { d ->
            cardSummary.visibility = View.VISIBLE
            cardSummary.setCardBackgroundColor(Pal.c("#FFF6C4"))
            tvSummary.setTextColor(Pal.c("#6B352A"))
            tvSummary.text = "📝 Manual attendance for $d\nAn admin opened this day for you. Take your selfie as usual."
        }
        if (!entryHidden) ensureCamera()
    }

    /** Starts the camera, asking for the camera and location permissions first when needed. */
    private fun ensureCamera() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera() else permLauncher.launch(perms)
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (entryHidden) { if (!meterLive) { provider.unbindAll(); frontOn = false }; return@addListener }      // nothing to capture today (a driver's day end camera stays on)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            frontOn = true                                        // the front camera takes over (a driver's car meter step had switched to the back one)
            meterLive = false
            if (!liveRequired) {                                  // an admin switched the live check off
                provider.bindToLifecycle(this, selector, preview, imageCapture)
                liveOk = true
                tvLiveness.visibility = View.GONE
                applyCaptureVisibility()
                return@addListener
            }
            val check = liveness ?: Liveness(
                onPrompt = { text -> tvLiveness.text = text },
                onPassed = {
                    liveOk = true
                    tvLiveness.text = "✅ Live check passed.\nNow capture your selfie."
                    applyCaptureVisibility()
                    // the pass is good for 30 seconds: after that, if no selfie was taken, the check starts again
                    uiHandler.postDelayed({ if (liveOk && photoFile == null && liveRequired && liveness?.unavailable != true) beginLiveness() }, 30000)
                },
                onUnavailable = {
                    liveOk = true
                    tvLiveness.text = "ℹ️ The live check is not available on this phone.\nYou can capture your selfie."
                    applyCaptureVisibility()
                }
            ).also { liveness = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(480, 640))
                .build().also { it.setAnalyzer(analysisExecutor, check) }
            try {
                provider.bindToLifecycle(this, selector, preview, imageCapture, analysis)
                beginLiveness()
            } catch (e: Exception) {
                // this phone cannot run the picture check next to the photo: take the selfie without it, as before
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, imageCapture)
                liveOk = true
                tvLiveness.visibility = View.GONE
                applyCaptureVisibility()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** (Re)starts the live check: the Capture button hides and the instructions show on the camera picture. */
    private fun beginLiveness() {
        if (!liveRequired) { liveOk = true; tvLiveness.visibility = View.GONE; applyCaptureVisibility(); return }
        uiHandler.removeCallbacksAndMessages(null)
        liveOk = false
        tvLiveness.visibility = View.VISIBLE
        applyCaptureVisibility()
        liveness?.restart()
    }

    /** The server tells every time whether the admin has the live check on. Switching it off takes effect at once; switching it on, the next time the screen opens. */
    private fun applyLiveSetting(on: Boolean) {
        getSharedPreferences("live", MODE_PRIVATE).edit().putBoolean("required", on).apply()
        if (on == liveRequired) return
        liveRequired = on
        if (!on) {
            uiHandler.removeCallbacksAndMessages(null)
            liveOk = true
            tvLiveness.visibility = View.GONE
            applyCaptureVisibility()
        }
    }

    /** The Capture / Retake button shows only while attendance can be marked AND the live check is passed (or switched off, or a photo is already taken). */
    private fun applyCaptureVisibility() {
        btnCapture.visibility = if (!entryHidden && (liveOk || photoFile != null)) View.VISIBLE else View.GONE
    }

    private fun captureSelfie() {
        val capture = imageCapture ?: return
        if (!liveOk) return
        if (liveRequired && liveness?.faceRecent() == false) { toast("Look at the camera, then tap Capture again."); return }
        btnCapture.isEnabled = false
        captureTime = Date()
        val file = File(cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    photoFile = file
                    tvLiveness.visibility = View.GONE
                    btnCapture.text = "🔄  Retake"
                    btnCapture.isEnabled = true
                    fetchLocation()                       // starts right away, in parallel with the preview below
                    Thread {
                        val preview = try { loadScaled(file.absolutePath, 800) } catch (e: Exception) { null }
                        runOnUiThread {
                            if (preview != null && photoFile == file) {
                                imgSelfie.setImageBitmap(preview)
                                imgSelfie.visibility = View.VISIBLE
                            }
                            if (photoFile == file && isDriver) enterMeterStage()       // a driver goes on to the car meter (back camera)
                        }
                    }.start()
                }
                override fun onError(e: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    /** Loads a photo, applies EXIF rotation and shrinks it so uploads stay small. */
    private fun loadScaled(path: String, max: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val m = Matrix()
        if (deg != 0) m.postRotate(deg.toFloat())
        val sc = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        m.postScale(sc, sc)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun retake() {
        photoFile = null
        imgSelfie.visibility = View.GONE
        btnCapture.text = "📸  Capture Selfie"
        meterNote = null                             // (the meter card goes back to "next: the car meter" until the new selfie is taken)
        if (!frontOn) {
            // the back camera (car meter step) was on, or the camera was switched off after it: the front camera comes back for the new selfie (its live check starts with it)
            liveOk = false
            applyCaptureVisibility()
            startCamera()
        } else beginLiveness()                       // a new selfie needs a new live check (when it is on)
        applyMeterUi()
        btnSubmit.isEnabled = false
        if (forDate == null) cardSummary.visibility = View.GONE
        tvLive.text = "Date: -\nTime: -\nLocation: -"
        locHandle?.cancel()
        location = null
    }

    private var locHandle: LiveLocation.Handle? = null

    override fun onDestroy() {
        locHandle?.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        watchHandler.removeCallbacksAndMessages(null)
        liveness?.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun fetchLocation() {
        locHandle?.cancel()
        location = null
        tvLive.text = "Getting location..."
        locHandle = LiveLocation.start(
            this,
            onProgress = { sec -> if (location == null) tvLive.text = LiveLocation.waitingText(sec) },
            onFail = { tvLive.text = "⚠️ $it\nThen tap Retake and capture again." },
            onFix = { loc ->
                if (isMockLocation(loc)) {
                    tvLive.text = "⚠️ A fake / mock location was detected. Turn off any fake-GPS app, then tap Retake and capture again."
                } else {
                    location = loc
                    Thread {
                        address = try {
                            @Suppress("DEPRECATION")
                            Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                                ?.firstOrNull()?.getAddressLine(0) ?: "Address unavailable"
                        } catch (e: Exception) { "Address unavailable" }
                        runOnUiThread { if (location === loc) { showLive(); btnSubmit.isEnabled = true } }
                    }.start()
                }
            }
        )
    }

    private fun showLive() {
        val t = captureTime ?: return
        val l = location ?: return
        tvLive.text = "Date: ${dateFmt.format(t)}\nTime: ${timeFmt.format(t)}\n" +
            "Location: ${String.format(Locale.US, "%.5f, %.5f", l.latitude, l.longitude)}\n$address"
    }

    private fun submit() {
        val t = captureTime ?: return
        val l = location ?: return
        val file = photoFile ?: return
        // A driver sends the car meter picture too, and only after the phone verified it.
        if (isDriver && (!meterOk || meterFile == null)) return
        val mFile: File? = if (isDriver) meterFile else null
        val mKm = meterKm

        btnSubmit.isEnabled = false
        btnCapture.isEnabled = false
        btnMeter.isEnabled = false
        cardSummary.visibility = View.VISIBLE
        cardSummary.setCardBackgroundColor(Pal.c("#E3F2FD"))
        tvSummary.setTextColor(Pal.c("#0D47A1"))
        tvSummary.text = "Submitting..."

        val selfieTimeText = "${dateFmt.format(t)} ${timeFmt.format(t)}"
        val username = session.username
        val addr = address

        // Shrinking and encoding the photo is heavy, so it runs off the screen thread (no freezing).
        // 640px at quality 65 is plenty to recognise a face and uploads much faster on slow mobile data.
        Thread {
            val body: JSONObject = try {
                val out = ByteArrayOutputStream()
                loadScaled(file.absolutePath, 640).compress(Bitmap.CompressFormat.JPEG, 65, out)
                val photo = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                // The car meter picture (drivers only): 1280px at quality 70 keeps the digits readable for a manager and is far below the server's 1.5 MB limit.
                val meterPhoto: String? = if (mFile != null) {
                    val mo = ByteArrayOutputStream()
                    loadScaled(mFile.absolutePath, 1280).compress(Bitmap.CompressFormat.JPEG, 70, mo)
                    Base64.encodeToString(mo.toByteArray(), Base64.NO_WRAP)
                } else null
                // Face check (only when the face model is in the app): the face print is made here on the phone and only the numbers are sent.
                val face = if (FaceId.available(this)) FaceId.embed(this, loadScaled(file.absolutePath, 1024)) else null
                JSONObject()
                    .put("action", "submit")
                    .put("username", username)
                    .put("selfieTime", selfieTimeText)
                    .put("lat", l.latitude).put("lng", l.longitude).put("mock", isMockLocation(l))
                    .put("address", addr)
                    .put("photo", photo)
                    .also { if (forDate != null) it.put("forDate", forDate) }
                    .also { if (meterPhoto != null) it.put("meter", meterPhoto).put("meterKm", mKm) }
                    .also { if (face?.print != null) it.put("face", face.print) else if (face?.noFace == true) it.put("faceState", "noface") }
            } catch (e: Exception) {
                runOnUiThread {
                    btnSubmit.isEnabled = true; btnCapture.isEnabled = true; btnMeter.isEnabled = !meterBusy
                    cardSummary.setCardBackgroundColor(Pal.c("#FFEBEE"))
                    tvSummary.setTextColor(Pal.c("#B71C1C"))
                    tvSummary.text = if (mFile != null) "❌ Couldn't prepare the photos. Please retake the selfie and the car meter picture."
                        else "❌ Couldn't prepare the photo. Please retake the selfie."
                }
                return@Thread
            }
            runOnUiThread { sendAttendance(body, t, l) }
        }.start()
    }

    private fun sendAttendance(body: JSONObject, t: Date, l: Location) {
        api(body) { res, err ->
            btnCapture.isEnabled = true
            if (res != null) {
                // Saved copies of Home and Calendar are now out of date.
                ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                Reminders.clearSelf(this)   // the "mark your attendance" reminder is no longer needed
                photoFile?.delete()   // the selfie now lives on the server; don't keep a copy on the phone
                meterFile?.delete()   // so does the car meter picture
                val meterLine = if (res.optBoolean("meter"))
                    "\nCar meter: " + (body.optString("meterKm").let { if (it.isNotEmpty()) "$it km" else "picture saved" }) else ""
                val late = res.optBoolean("late")
                cardSummary.setCardBackgroundColor(Pal.c(if (late) "#FFEDD5" else "#DCFCE7"))
                tvSummary.setTextColor(Pal.c(if (late) "#9A3412" else "#166534"))
                tvSummary.text = "${if (late) "⏰ Late Present" else "✅ Attendance Submitted"}${if (res.optBoolean("manual")) " (manual)" else ""}\n\nDate: ${res.optString("date")}\nTime: ${res.optString("time")}\n" +
                    "Selfie time: ${dateFmt.format(t)} ${timeFmt.format(t)}\n" +
                    "Location: ${String.format(Locale.US, "%.5f, %.5f", l.latitude, l.longitude)}\n$address$meterLine"
                loadTodayStatus()      // the card on top now shows the new attendance
                if (late && forDate == null) {
                    LateReason.require(this) { loadTodayStatus() }          // compulsory: the reason must be written before going on
                } else popup(if (late) "⏰ Late Present" else "✅ Attendance submitted",
                    (if (late) "Your attendance was recorded as late.\n\n" else "Your attendance was submitted successfully.\n\n") +
                    "Date: ${res.optString("date")}\nTime: ${res.optString("time")}")
            } else {
                val already = err?.contains("already", ignoreCase = true) == true
                btnSubmit.isEnabled = !already          // a second submit for the same day can never work
                btnMeter.isEnabled = !meterBusy
                if (already) {                           // the saved copy of Home was out of date: refresh the status card, which then hides the form
                    ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                    loadTodayStatus()
                }
                // The server says the car meter picture is missing: this phone did not know yet that the person is a driver. The meter step opens now.
                val needMeter = !already && !isDriver && err?.contains("car meter", ignoreCase = true) == true
                if (needMeter) applyDriverSetting(true, meterCheckOn)
                cardSummary.setCardBackgroundColor(Pal.c(if (already) "#E3F2FD" else if (needMeter) "#FFF6C4" else "#FFEBEE"))
                tvSummary.setTextColor(Pal.c(if (already) "#0D47A1" else if (needMeter) "#6B352A" else "#B71C1C"))
                tvSummary.text = if (already) "ℹ️ $err"
                    else if (needMeter) "🚗 You are a driver: take the picture of the car meter below (back camera), then submit."
                    else "❌ $err"
                if (already) popup("ℹ️ Already submitted", err ?: "Your attendance for today is already submitted.")
            }
        }
    }

    /** Today's Status (same card and rules as on Home): status, present/absent count of the 26th–25th period, selfie and location. */
    private fun loadTodayStatus() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        val today = fmt.format(now.time)
        val isFridayToday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18          // matches Code.gs ABSENT_AFTER_HOUR
        val periodStart = (now.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_MONTH) < 26) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 26)
        }
        val periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 25) }
        val startKey = fmt.format(periodStart.time)
        val endKey = fmt.format(periodEnd.time)
        val tvStatus = findViewById<TextView>(R.id.atStatus)
        val tvMonth = findViewById<TextView>(R.id.atMonth)

        val render: (JSONObject) -> Unit = render@{ res ->
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            var todayRecord: JSONObject? = null
            val presentDays = HashSet<String>()
            if (res.has("liveCheck")) applyLiveSetting(res.optBoolean("liveCheck", true))
            if (res.has("driver")) applyDriverSetting(res.optBoolean("driver", false), res.optBoolean("meterCheck", true))
            val recs = res.optJSONArray("records") ?: return@render
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late"); todayRecord = r }
                if (d >= startKey && d <= endKey) { present++; presentDays.add(d) }
            }
            val leaves = res.optJSONArray("leaves")
            fun leaveOn(dateKey: String): String? {
                if (leaves != null) for (i in 0 until leaves.length()) {
                    val l = leaves.getJSONObject(i)
                    if (l.optString("status") == "Approved" && dateKey >= l.getString("from") && dateKey <= l.getString("to")) return l.optString("type")
                }
                return null
            }
            val holidays = HashMap<String, String>()
            res.optJSONArray("holidays")?.let { h -> for (i in 0 until h.length()) { val o = h.getJSONObject(i); holidays[o.optString("date")] = o.optString("name") } }
            var absent = 0
            val cursor = periodStart.clone() as Calendar
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            while (!cursor.after(yesterday)) {
                val k = fmt.format(cursor.time)
                if (cursor.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY && !holidays.containsKey(k) && !presentDays.contains(k) && leaveOn(k) == null) absent++
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }
            val leaveType = leaveOn(today)
            val holidayToday = holidays[today]
            when {
                todayTime != null && todayLate -> { tvStatus.text = "⏰ Late Present · $todayTime"; tvStatus.setTextColor(Pal.c("#D97706")) }
                todayTime != null -> { tvStatus.text = "✅ Present · $todayTime"; tvStatus.setTextColor(Pal.c("#16A34A")) }
                leaveType != null -> { tvStatus.text = "🌴 On $leaveType"; tvStatus.setTextColor(Pal.c("#D97706")) }
                holidayToday != null -> { tvStatus.text = "🎉 Holiday · $holidayToday"; tvStatus.setTextColor(Pal.c("#7C3AED")) }
                isFridayToday -> { tvStatus.text = "🗓️ Weekly Off (Friday)"; tvStatus.setTextColor(Pal.c("#8A5F52")) }
                isAfterCutoff -> { tvStatus.text = "❌ Absent today"; tvStatus.setTextColor(Pal.c("#B91C1C")) }
                else -> { tvStatus.text = "⏳ Not marked yet"; tvStatus.setTextColor(Pal.c("#DC2626")) }
            }
            tvMonth.text = "Present: $present day(s) (26th–25th)" + (if (absent > 0) "  ·  Absent: $absent" else "")
            showTodayDetail(todayRecord)
            LateReason.bind(this, findViewById(R.id.atLateReason), todayRecord)
            if (todayLate && todayRecord?.optString("lateReason")?.isEmpty() == true && forDate == null)
                LateReason.require(this) { loadTodayStatus() }          // still no reason for today's late attendance
            // A driver who has marked attendance and has not sent the day end yet gets the Day End step (see isEndMode()).
            attendedToday = todayTime != null
            dayEndDone = todayRecord?.optString("dayEnd")?.isNotEmpty() == true
            morningKm = todayRecord?.optString("meterKm") ?: ""
            // Already submitted (Present / Late), or marked Absent: there is nothing left to capture or submit today.
            val absentToday = todayTime == null && leaveType == null && holidayToday == null && !isFridayToday && isAfterCutoff
            setEntryVisible(!(todayTime != null || absentToday))
        }

        apiCached(
            cacheKey = ResponseCache.homeKey(session.username),
            body = JSONObject().put("action", "history").put("username", session.username)
                .put("from", startKey).put("nights", true)
                .put("appVersion", AppUpdate.installedName(this)).put("appCode", AppUpdate.installedCode(this)),
            maxAgeMs = 24 * HOUR_MS,
            onData = { res -> render(res) },
            onError = { err, hadSaved -> if (!hadSaved) { tvStatus.text = "Offline"; tvMonth.text = err ?: "" } }
        )
    }

    /** Camera, Capture Selfie, the live date/time/location card and Submit are only shown while attendance can still be marked. */
    private fun setEntryVisible(show: Boolean) {
        if (forDate != null) return                       // a manual day opened by an admin always shows the form
        val wasHidden = entryHidden
        entryHidden = !show
        if (show && wasHidden) ensureCamera()              // a saved copy said "done", the fresh answer says it is not
        if (!show && !wasHidden) discardWork()             // the attendance is done (just submitted, or marked elsewhere): the selfie and the morning meter picture are no longer needed
        val v = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardCamera).visibility = v
        findViewById<View>(R.id.cardLive).visibility = v
        applyCaptureVisibility()
        applySubmitVisibility()                            // a driver's Submit button also waits for the verified car meter picture
        applyMeterUi()
        if (!show && !meterLive) {                         // (a live back camera of the day end stays on)
            try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
            frontOn = false
        }
    }

    /** Small round selfie, address and coordinates of today's attendance; back to the pin until it is marked. */
    private fun showTodayDetail(rec: JSONObject?) {
        val box = findViewById<View>(R.id.atDetail)
        val mini = findViewById<ImageView>(R.id.atMini)
        val pin = findViewById<View>(R.id.atPin)
        val miniCard = findViewById<MaterialCardView>(R.id.atMiniCard)
        miniCard.clipToOutline = true
        if (rec == null) {
            box.visibility = View.GONE
            mini.visibility = View.GONE; pin.visibility = View.VISIBLE
            miniCard.setOnClickListener(null); miniCard.isClickable = false
            return
        }
        box.visibility = View.VISIBLE
        val selfie = rec.optString("selfie")
        if (selfie.isNotEmpty()) {
            pin.visibility = View.GONE; mini.visibility = View.VISIBLE
            PhotoLoader.load(this, session, selfie, mini, 200)
            miniCard.isClickable = true
            miniCard.setOnClickListener {
                val big = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt())
                }
                PhotoLoader.load(this, session, selfie, big, 640)
                androidx.appcompat.app.AlertDialog.Builder(this).setView(pictureFrame(big, (320 * resources.displayMetrics.density).toInt())).setPositiveButton("Close", null).show()
            }
        } else { mini.visibility = View.GONE; pin.visibility = View.VISIBLE }
        val addr = rec.optString("address")
        val coords = "${rec.optString("lat")}, ${rec.optString("lng")}"
        findViewById<TextView>(R.id.atLoc).text = "📍 " + (if (addr.isNotEmpty() && addr != "Address unavailable") "$addr\n$coords" else coords)
        // A driver's car meter picture (and the km the phone read from it): tap the line to see the picture.
        val tvMeter = findViewById<TextView>(R.id.atMeter)
        val meterId = rec.optString("meter")
        if (meterId.isNotEmpty()) {
            val km = rec.optString("meterKm")
            tvMeter.text = "🚗 Car meter" + (if (km.isNotEmpty()) " · $km km" else "") + " · tap to see the picture"
            tvMeter.visibility = View.VISIBLE
            tvMeter.setOnClickListener { showPhotoDialog(session, meterId) }
        } else {
            tvMeter.visibility = View.GONE
            tvMeter.setOnClickListener(null)
        }
        // A driver's day end: the time, the km on the second picture and the km driven since the morning picture. Tap the line to see the picture.
        val tvEnd = findViewById<TextView>(R.id.atDayEnd)
        val endTime = rec.optString("dayEnd")
        if (endTime.isNotEmpty()) {
            val endKm = rec.optString("dayEndKm")
            val driven = rec.optInt("driven", -1)
            val endId = rec.optString("dayEndMeter")
            tvEnd.text = "🏁 Day end · $endTime" + (if (endKm.isNotEmpty()) " · $endKm km" else "") +
                (if (driven >= 0) " · $driven km driven" else "") + (if (endId.isNotEmpty()) " · tap to see the picture" else "")
            tvEnd.visibility = View.VISIBLE
            if (endId.isNotEmpty()) tvEnd.setOnClickListener { showPhotoDialog(session, endId) } else tvEnd.setOnClickListener(null)
        } else {
            tvEnd.visibility = View.GONE
            tvEnd.setOnClickListener(null)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Driver: the car meter picture. The order is: selfie (front camera) -> picture of the car meter (back camera) -> the phone reads the
    // km number from it -> only then the Submit button shows. A person who is not a driver never sees any of this.

    /** The Driver right and the number-check setting, as the server says them. A change shows at once and is remembered for the next start. */
    private fun applyDriverSetting(driver: Boolean, check: Boolean) {
        getSharedPreferences("live", MODE_PRIVATE).edit()
            .putBoolean("driver_${session.username}", driver).putBoolean("meterCheck", check).apply()
        meterCheckOn = check
        if (driver == isDriver) return
        isDriver = driver
        applyMeterUi()
        applySubmitVisibility()
        if (driver && photoFile != null && meterFile == null) enterMeterStage()      // the selfie was taken before the answer came
    }

    /** Submit shows when attendance can be marked AND (for a driver) the car meter picture is verified. */
    private fun applySubmitVisibility() {
        btnSubmit.visibility = if (entryHidden || (isDriver && !meterOk)) View.GONE else View.VISIBLE
    }

    /**
     * The meter card, for a driver while attendance can be marked. Three looks: waiting for the selfie (a note), live (back camera, Capture button, a light
     * button), taken (the picture with its result on it, Retake button).
     */
    private fun applyMeterUi() {
        val endMode = isEndMode()                            // the same card is the Day End card once the attendance is marked
        val show = (isDriver && !entryHidden) || endMode
        cardMeter.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            meterLive = false
            btnMeter.visibility = View.GONE
            btnDayEnd.visibility = View.GONE
            return
        }
        val taken = meterFile != null
        val camOn = meterLive && (endMode || photoFile != null)   // the back camera is on (it stays on for a moment after the picture is taken, until the picture is shown)
        val live = camOn && !taken
        previewMeter.visibility = if (camOn) View.VISIBLE else View.GONE
        imgMeter.visibility = if (taken && !camOn) View.VISIBLE else View.GONE
        tvMeterIdle.text = meterNote ?: (if (endMode) meterEndIdle else meterIdleDefault)
        tvMeterIdle.visibility = if (taken || camOn) View.GONE else View.VISIBLE
        tvMeterHint.visibility = if (taken || camOn) View.VISIBLE else View.GONE
        btnTorch.visibility = if (live && torchAvailable) View.VISIBLE else View.GONE
        btnMeter.visibility = if (taken || camOn || endMode) View.VISIBLE else View.GONE
        btnMeter.text = if (taken) "🔄  Retake car meter" else if (endMode && !camOn) "📷  Start Day End" else "📷  Capture car meter"
        btnMeter.isEnabled = !meterBusy && !dayEndSending
        btnDayEnd.visibility = if (endMode && taken && meterOk) View.VISIBLE else View.GONE      // only after the new car meter picture is verified
        btnDayEnd.text = if (dayEndSending) "Sending…" else "🏁  Submit Day End"
        btnDayEnd.isEnabled = !meterBusy && !dayEndSending
    }

    /** The line over the meter picture. [tone]: "info" (dark), "good" (green) or "bad" (red). */
    private fun showMeterHint(text: String, tone: String) {
        tvMeterHint.text = text
        tvMeterHint.setBackgroundColor(Color.parseColor(when (tone) { "good" -> "#E6166534"; "bad" -> "#E6B71C1C"; else -> "#B3000000" }))
    }

    /** The selfie is taken: a driver goes on to the car meter. The back camera opens in the meter card and the page scrolls down to it. */
    private fun enterMeterStage() {
        applyMeterUi()
        if (meterFile == null) { startMeterCamera(); scrollToView(cardMeter) }
        else if (meterOk) scrollToEnd()                                     // a new selfie after a verified meter picture: only Submit is left
    }

    /** Switches from the front camera to the back one and shows it live in the meter card. */
    private fun startMeterCamera() {
        if (!meterStepOpen()) return
        meterNote = "📷  Opening the back camera…"
        meterStarting = true
        applyMeterUi()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            meterStarting = false
            // things can change while the camera starts (a retake, an attendance that was just marked)
            if (!meterStepOpen() || isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                previewMeter.visibility = View.VISIBLE
                // best picture quality first; a phone that cannot do that next to the live picture gets the plain setting
                val cam = try { bindMeterCamera(provider, ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) }
                          catch (e: Exception) { bindMeterCamera(provider, ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) }
                meterCamera = cam
                frontOn = false
                meterLive = true
                meterNote = null
                torchOn = false
                btnTorch.alpha = 0.7f
                torchAvailable = try { cam.cameraInfo.hasFlashUnit() } catch (e: Exception) { false }
                showMeterHint("Hold the phone close to the car meter so the km number is sharp and fills the picture. Tap the picture to focus.", "info")
            } catch (e: Exception) {
                meterLive = false
                meterCamera = null
                meterCapture = null
                meterNote = if (isEndMode()) "⚠️ The back camera could not start.\n\nTap Start Day End to try again."
                            else "⚠️ The back camera could not start.\n\nClose this screen and open it again."
            }
            applyMeterUi()
        }, ContextCompat.getMainExecutor(this))
    }

    /** The back camera may be used for the car meter: in the morning after the selfie, or at the day end. */
    private fun meterStepOpen() = isDriver && meterFile == null && (isEndMode() || (!entryHidden && photoFile != null))

    /** Switches the back camera on with its live picture in the meter card and an ImageCapture for the car meter picture. Throws when the phone cannot. */
    private fun bindMeterCamera(provider: ProcessCameraProvider, captureMode: Int): Camera {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewMeter.surfaceProvider) }
        val capture = ImageCapture.Builder().setCaptureMode(captureMode).build()
        provider.unbindAll()
        val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        meterCapture = capture
        return cam
    }

    private fun stopMeterCamera() {
        meterLive = false
        meterCamera = null
        meterCapture = null
        frontOn = false
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
    }

    private fun captureMeter() {
        val capture = meterCapture ?: return
        if (meterBusy || meterFile != null || !meterLive) return
        meterBusy = true
        applyMeterUi()
        showMeterHint("📸 Taking the picture…", "info")
        val file = File(cacheDir, "meter_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    if (isFinishing || isDestroyed) { file.delete(); return }
                    meterFile = file
                    meterOk = false
                    meterKm = ""
                    verifyMeter(file)
                }
                override fun onError(e: ImageCaptureException) {
                    meterBusy = false
                    showMeterHint("❌ The picture could not be taken: ${e.message}\nTap Capture car meter again.", "bad")
                    applyMeterUi()
                }
            })
    }

    /** The picture is taken: show it, switch the back camera off, and let the phone read the km number from it. */
    private fun verifyMeter(file: File) {
        meterBusy = true
        applyMeterUi()
        showMeterHint("🔎 Checking the picture…", "info")
        Thread {
            // 1600px is plenty for the number reader; the picture that is sent is made from the file again later (1280px)
            val bmp = try { loadScaled(file.absolutePath, 1600) } catch (e: Exception) { null }
            runOnUiThread {
                if (meterFile != file) return@runOnUiThread                  // retaken in the meantime
                if (bmp == null) {                                           // the picture cannot be opened: take it again (the camera is still on)
                    meterFile = null
                    file.delete()
                    meterBusy = false
                    showMeterHint("❌ The picture could not be read. Tap Capture car meter again.", "bad")
                    applyMeterUi()
                    return@runOnUiThread
                }
                imgMeter.setImageBitmap(bmp)
                stopMeterCamera()
                applyMeterUi()
                if (!meterCheckOn) { meterChecked(file, null); return@runOnUiThread }       // the admin switched the number check off: the picture is enough
                watchHandler.removeCallbacksAndMessages(null)
                // if the reader never answers, the picture is accepted after 25 seconds (a phone problem must not block a real attendance)
                watchHandler.postDelayed({ meterChecked(file, MeterCheck.Reading(false, "")) }, 25000)
                MeterCheck.check(bmp) { r -> meterChecked(file, r) }
            }
        }.start()
    }

    /**
     * The number reader answered ([r] is null when the admin switched the number check off). Verified = a km number was read in the picture.
     * When the phone cannot run the reader at all, the picture is accepted without a number.
     */
    private fun meterChecked(file: File, r: MeterCheck.Reading?) {
        if (!meterBusy || meterFile != file) return
        watchHandler.removeCallbacksAndMessages(null)
        meterBusy = false
        val next = if (isEndMode()) "\nNow tap Submit Day End." else ""        // (the day end: what comes after the check)
        when {
            r == null -> {
                meterOk = true; meterKm = ""
                showMeterHint("✅ Car meter picture taken.$next", "good")
            }
            !r.ran -> {
                meterOk = true; meterKm = ""
                showMeterHint("✅ Car meter picture taken.\n(The number check could not run on this phone, so the picture is accepted.)$next", "good")
            }
            r.km.isNotEmpty() -> {
                meterOk = true; meterKm = r.km
                showMeterHint("✅ Car meter verified · ${r.km} km${if (isEndMode()) drivenNote(r.km) else ""}", "good")
            }
            else -> {
                meterOk = false; meterKm = ""
                showMeterHint("❌ No km number could be read in this picture.\nTap Retake car meter, hold the phone steady and closer so the digits fill the picture and do not shine or blur (🔦 gives light).", "bad")
            }
        }
        applyMeterUi()
        applySubmitVisibility()
        if (meterOk) scrollToEnd()
    }

    private fun retakeMeter() {
        if (meterBusy) return
        watchHandler.removeCallbacksAndMessages(null)
        meterFile?.delete()
        meterFile = null
        meterOk = false
        meterKm = ""
        imgMeter.setImageDrawable(null)
        applySubmitVisibility()                      // the Submit button hides again until a new picture is verified
        applyMeterUi()
        startMeterCamera()                           // (does nothing while the selfie is being retaken: the live picture opens after it)
    }

    private fun toggleTorch() {
        val cam = meterCamera ?: return
        torchOn = !torchOn
        try { cam.cameraControl.enableTorch(torchOn) } catch (e: Exception) { torchOn = false }
        btnTorch.alpha = if (torchOn) 1f else 0.7f
    }

    /** Tap the live car meter picture to focus there (small digits need a sharp picture). */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMeterFocus() {
        previewMeter.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val cam = meterCamera
                if (cam != null) {
                    try {
                        val point = previewMeter.meteringPointFactory.createPoint(ev.x, ev.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    } catch (e: Exception) { }
                }
                v.performClick()
            }
            true
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // Driver: the DAY END. After the attendance is marked, the driver takes a new picture of the car meter (back camera); the phone reads the km number
    // from it like in the morning; only then Submit Day End shows. The server keeps it with today's attendance (once a day) and works out the km driven.

    /** A driver whose attendance is marked today and who has not sent the day end yet (never for a manual day). */
    private fun isEndMode() = forDate == null && isDriver && attendedToday && !dayEndDone

    /** "Start Day End": opens the back camera (asking for the camera permission first when it is missing). */
    private fun openEndCamera() {
        if (!isEndMode() || meterStarting || meterLive || meterFile != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startMeterCamera()
        else endPermLauncher.launch(Manifest.permission.CAMERA)
    }

    /** Throws away the selfie and the car meter picture taken for an attendance that was just submitted (or turned out to be marked already), or the day end picture after it was sent. */
    private fun discardWork() {
        watchHandler.removeCallbacksAndMessages(null)
        meterFile?.delete(); meterFile = null
        photoFile?.delete(); photoFile = null
        meterOk = false; meterKm = ""; meterBusy = false; meterNote = null
        meterLive = false; meterCamera = null; meterCapture = null
        imgMeter.setImageDrawable(null)
        imgSelfie.visibility = View.GONE                       // (the selfie card is hidden anyway once the attendance is done; this keeps it tidy if it ever shows again)
        btnCapture.text = "📸  Capture Selfie"
    }

    /** Submit Day End: sends the verified car meter picture and its km number. */
    private fun submitDayEnd() {
        if (!isEndMode() || dayEndSending) return
        val file = meterFile ?: return
        if (!meterOk) return
        val km = meterKm
        dayEndSending = true
        applyMeterUi()
        showEndSummary("Submitting the day end…", "info")
        // Shrinking and encoding the picture is heavy, so it runs off the screen thread (the same size as the morning picture: 1280px, quality 70).
        Thread {
            val body: JSONObject? = try {
                val out = ByteArrayOutputStream()
                loadScaled(file.absolutePath, 1280).compress(Bitmap.CompressFormat.JPEG, 70, out)
                JSONObject().put("action", "dayEnd").put("username", session.username)
                    .put("meter", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).put("meterKm", km)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (body == null) {
                    dayEndSending = false
                    applyMeterUi()
                    showEndSummary("❌ Couldn't prepare the picture. Please retake the car meter picture.", "bad")
                } else sendDayEnd(body)
            }
        }.start()
    }

    private fun sendDayEnd(body: JSONObject) {
        api(body) { res, err ->
            dayEndSending = false
            if (res != null) {
                dayEndDone = true
                discardWork()                                  // the picture now lives on the server
                ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                val km = res.optString("km")
                val driven = res.optInt("driven", -1)
                val lines = "Time: ${res.optString("time")}" + (if (km.isNotEmpty()) "\nCar meter: $km km" else "") +
                    (if (driven >= 0) "\nDriven today: $driven km" else "")
                applyMeterUi()
                showEndSummary("🏁 Day end submitted\n\n$lines", "good")
                loadTodayStatus()                              // the card on top now shows the day end
                popup("🏁 Day end submitted", lines)
            } else {
                val already = err?.contains("already", ignoreCase = true) == true
                if (already) {                                 // a second day end for the same day can never work: show it as done
                    dayEndDone = true
                    discardWork()
                    ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                    loadTodayStatus()
                }
                applyMeterUi()
                showEndSummary(if (already) "ℹ️ $err" else "❌ $err", if (already) "info" else "bad")
            }
        }
    }

    /** The result card at the bottom of the page. [tone]: "info" (blue), "good" (green) or "bad" (red). */
    private fun showEndSummary(text: String, tone: String) {
        val (bg, fg) = when (tone) { "good" -> "#DCFCE7" to "#166534"; "bad" -> "#FFEBEE" to "#B71C1C"; else -> "#E3F2FD" to "#0D47A1" }
        cardSummary.visibility = View.VISIBLE
        cardSummary.setCardBackgroundColor(Pal.c(bg))
        tvSummary.setTextColor(Pal.c(fg))
        tvSummary.text = text
        if (tone != "good") scrollToEnd()
    }

    /** End km minus the morning km (both digit text of 4 to 9 digits), or null when either is missing or the end reading is lower. */
    private fun drivenKm(from: String, to: String): Long? {
        if (from.length !in 4..9 || to.length !in 4..9 || !from.all { it in '0'..'9' } || !to.all { it in '0'..'9' }) return null
        val d = to.toLong() - from.toLong()
        return if (d >= 0) d else null
    }

    /** Under the verified day end picture: how far the car went since this morning's picture, or a warning when the new number is lower. */
    private fun drivenNote(km: String): String {
        val d = drivenKm(morningKm, km)
        return when {
            d != null -> "\n$d km since this morning.\nNow tap Submit Day End."
            morningKm.isNotEmpty() -> "\n⚠️ Lower than this morning's $morningKm km. If the picture was misread, retake it; otherwise tap Submit Day End."
            else -> "\nNow tap Submit Day End."
        }
    }

    private fun scrollToView(v: View) {
        val sv = findViewById<ScrollView>(R.id.atScroll) ?: return
        sv.postDelayed({ sv.smoothScrollTo(0, maxOf(0, v.top - (8 * resources.displayMetrics.density).toInt())) }, 200)
    }

    private fun scrollToEnd() {
        val sv = findViewById<ScrollView>(R.id.atScroll) ?: return
        sv.postDelayed({ sv.fullScroll(View.FOCUS_DOWN) }, 250)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** A small pop-up after submitting: success, or "already submitted". */
    private fun popup(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
        // Closes by itself after 5 seconds (OK closes it sooner).
        val closer = Runnable { if (dialog.isShowing && !isFinishing && !isDestroyed) dialog.dismiss() }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(closer, 5000)
        dialog.setOnDismissListener { handler.removeCallbacks(closer) }
    }
}
