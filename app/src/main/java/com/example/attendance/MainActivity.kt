package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var imgSelfie: ImageView
    private lateinit var tvLive: TextView
    private lateinit var tvSummary: TextView
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
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        imgSelfie = findViewById(R.id.imgSelfie)
        tvLive = findViewById(R.id.tvLive)
        tvSummary = findViewById(R.id.tvSummary)
        btnCapture = findViewById(R.id.btnCapture)
        btnSubmit = findViewById(R.id.btnSubmit)

        btnCapture.setOnClickListener {
            if (imgSelfie.visibility == android.view.View.VISIBLE) retake() else captureSelfie()
        }
        btnSubmit.setOnClickListener { submit() }

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera() else permLauncher.launch(perms)
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
        captureTime = Date()                       // timestamp taken at selfie moment
        val file = File(cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    photoFile = file
                    imgSelfie.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    imgSelfie.visibility = android.view.View.VISIBLE
                    btnCapture.text = "Retake"
                    btnCapture.isEnabled = true
                    fetchLocation()
                }
                override fun onError(e: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    private fun retake() {
        imgSelfie.visibility = android.view.View.GONE
        btnCapture.text = "Capture Selfie"
        btnSubmit.isEnabled = false
        tvSummary.visibility = android.view.View.GONE
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
            .addOnFailureListener { tvLive.text = "Location failed: ${it.message}" }
    }

    private fun showLive() {
        val t = captureTime ?: return
        val l = location ?: return
        tvLive.text = "Date: ${dateFmt.format(t)}\nTime: ${timeFmt.format(t)}\n" +
            "Location: %.5f, %.5f\n$address".format(l.latitude, l.longitude)
    }

    private fun submit() {
        val t = captureTime ?: return
        val l = location ?: return
        val summary = "✅ Attendance Submitted\n\nDate: ${dateFmt.format(t)}\nTime: ${timeFmt.format(t)}\n" +
            "Latitude: %.5f\nLongitude: %.5f\nAddress: $address".format(l.latitude, l.longitude)
        tvSummary.text = summary
        tvSummary.visibility = android.view.View.VISIBLE
        btnSubmit.isEnabled = false

        // Save record locally (replace with your server/API upload)
        val keep = File(filesDir, "selfie_${t.time}.jpg")
        photoFile?.copyTo(keep, overwrite = true)
        File(filesDir, "attendance_log.txt").appendText(
            "${dateFmt.format(t)} | ${timeFmt.format(t)} | ${l.latitude},${l.longitude} | $address | ${keep.name}\n")
        toast("Attendance saved")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
