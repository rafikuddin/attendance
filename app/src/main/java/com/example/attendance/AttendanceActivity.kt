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
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    private var photoFile: File? = null
    private var captureTime: Date? = null
    private var location: Location? = null
    private var address: String = "Address unavailable"

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.values.all { granted -> granted }) startCamera()
        else toast("Camera & location permissions are required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_attendance)
        previewView = findViewById(R.id.previewView)
        imgSelfie = findViewById(R.id.imgSelfie)
        tvLive = findViewById(R.id.tvLive)
        tvSummary = findViewById(R.id.tvSummary)
        cardSummary = findViewById(R.id.cardSummary)
        setupHeader("Mark Attendance")
        btnCapture = findViewById(R.id.btnCapture)
        btnSubmit = findViewById(R.id.btnSubmit)

        btnCapture.setOnClickListener {
            if (imgSelfie.visibility == View.VISIBLE) retake() else captureSelfie()
        }
        btnSubmit.setOnClickListener { submit() }

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
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureSelfie() {
        val capture = imageCapture ?: return
        btnCapture.isEnabled = false
        captureTime = Date()
        val file = File(cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    photoFile = file
                    imgSelfie.setImageBitmap(loadScaled(file.absolutePath, 1000))
                    imgSelfie.visibility = View.VISIBLE
                    btnCapture.text = "🔄  Retake"
                    btnCapture.isEnabled = true
                    fetchLocation()
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
        imgSelfie.visibility = View.GONE
        btnCapture.text = "📸  Capture Selfie"
        btnSubmit.isEnabled = false
        cardSummary.visibility = View.GONE
        tvLive.text = "Date: -\nTime: -\nLocation: -"
        location = null
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        tvLive.text = "Getting location..."
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) { tvLive.text = "Location unavailable. Turn on GPS and retake."; return@addOnSuccessListener }
                location = loc
                Thread {
                    address = try {
                        @Suppress("DEPRECATION")
                        Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                            ?.firstOrNull()?.getAddressLine(0) ?: "Address unavailable"
                    } catch (e: Exception) { "Address unavailable" }
                    runOnUiThread { showLive(); btnSubmit.isEnabled = true }
                }.start()
            }
            .addOnFailureListener {
                tvLive.text = "⚠️ Location failed: ${it.message}\nTurn on GPS, then tap Retake and capture again."
            }
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
        val out = ByteArrayOutputStream()
        loadScaled(file.absolutePath, 800).compress(Bitmap.CompressFormat.JPEG, 70, out)
        val photo = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        btnSubmit.isEnabled = false
        btnCapture.isEnabled = false
        cardSummary.visibility = View.VISIBLE
        cardSummary.setCardBackgroundColor(Color.parseColor("#E3F2FD"))
        tvSummary.setTextColor(Color.parseColor("#0D47A1"))
        tvSummary.text = "Submitting..."

        val body = JSONObject()
            .put("action", "submit")
            .put("username", session.username)
            .put("password", session.password)
            .put("selfieTime", "${dateFmt.format(t)} ${timeFmt.format(t)}")
            .put("lat", l.latitude).put("lng", l.longitude)
            .put("address", address)
            .put("photo", photo)

        api(body) { res, err ->
            btnCapture.isEnabled = true
            if (res != null) {
                val late = res.optBoolean("late")
                cardSummary.setCardBackgroundColor(Color.parseColor(if (late) "#FFEDD5" else "#DCFCE7"))
                tvSummary.setTextColor(Color.parseColor(if (late) "#9A3412" else "#166534"))
                tvSummary.text = "${if (late) "⏰ Late Present" else "✅ Attendance Submitted"}\n\nDate: ${res.optString("date")}\nTime: ${res.optString("time")}\n" +
                    "Selfie time: ${dateFmt.format(t)} ${timeFmt.format(t)}\n" +
                    "Location: ${String.format(Locale.US, "%.5f, %.5f", l.latitude, l.longitude)}\n$address"
            } else {
                btnSubmit.isEnabled = true
                cardSummary.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
                tvSummary.setTextColor(Color.parseColor("#B71C1C"))
                tvSummary.text = "❌ $err"
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
