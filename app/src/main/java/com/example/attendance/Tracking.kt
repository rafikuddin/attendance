package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * MOVEMENT TRACKING. An admin ticks "Track movement" for a person. Once that person has submitted attendance, this phone shares its location every
 * few minutes (10 by default, the server says) through a foreground service with a permanent notification, so the person always sees that it is on:
 *   - a normal person until 6 PM (matches Code.gs ABSENT_AFTER_HOUR),
 *   - a driver until the Day End is sent (and 6 AM of the next day at the latest).
 * The points wait in a small list on the phone and go to the server in groups; the server decides what it keeps and tells the phone when to stop.
 * No ACCESS_BACKGROUND_LOCATION is needed: the service is started while the app is open (right after the attendance), which is what lets it
 * keep reading the location in the background. Nothing is ever shared for a person without the tick, before attendance, or after the stop time.
 */
object Tracking {
    const val CHANNEL = "tracking"
    const val NOTIF_ID = 4201
    private const val PREFS = "tracking"
    private const val MAX_BUFFER = 300
    const val BATCH = 40
    private const val STOP_HOUR = 18             // matches Code.gs ABSENT_AFTER_HOUR
    private const val DRIVER_UNTIL_HOUR = 6      // matches Code.gs TRACK_DRIVER_UNTIL_HOUR

    @Volatile var running = false
    private var dialogShowing = false

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Called by the screens with what the server said about this person ([tracked] = the Track movement tick, [attended] = the attendance this tracking
     * belongs to is marked, [closed] = a driver's day end was sent). Starts the service when it should run and is not running, stops it when the time
     * is over. Safe to call as often as you like. It never stops anything because of "not tracked": the server tells the service that itself.
     */
    fun sync(a: AppCompatActivity, tracked: Boolean, everyMinutes: Int, driver: Boolean, attended: Boolean, closed: Boolean) {
        val s = Session(a)
        if (!s.loggedIn || !tracked) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (closed || (!driver && hour >= STOP_HOUR)) { if (running) endAndFlush(a); return }
        if (!attended || running || !hasLocationPermission(a)) return
        if (prefs(a).getString("stopped", "") == today()) return           // the server ended today's tracking (the day is full, or the time is over): not again today
        val every = everyMinutes.coerceIn(1, 60)
        if (!prefs(a).getBoolean("ack_${s.username}", false)) { askThenStart(a, s.username, every, driver); return }
        start(a, s.username, every, driver)
    }

    /** A new attendance was submitted: tracking may start (again), also after the server ended an earlier row's tracking (a driver after midnight). */
    fun noteAttendance(ctx: Context) { prefs(ctx).edit().remove("stopped").apply() }

    /** The same, from the answer of the `history` action (Home and Attendance both load it). [todayRecord] is today's attendance record in it, if any. */
    fun syncFrom(a: AppCompatActivity, res: JSONObject, todayRecord: JSONObject?) {
        val driver = res.optBoolean("driver")
        val de = res.optJSONObject("dayEndFor")                    // a driver's open (or closed) work day: today's, or yesterday's after midnight
        val attended = todayRecord != null || (driver && de != null)
        val closed = driver && (if (de != null) de.optString("dayEnd").isNotEmpty() else todayRecord?.optString("dayEnd").orEmpty().isNotEmpty())
        sync(a, res.optBoolean("track"), res.optInt("trackEvery", 10), driver, attended, closed)
    }

    private fun start(ctx: Context, user: String, every: Int, driver: Boolean) {
        val p = prefs(ctx)
        if (p.getString("user", "") != user) p.edit().remove("buf").remove("last_ms").apply()          // never send one person's points as another's
        if (p.getString("buf_date", "") != today()) p.edit().remove("buf").putString("buf_date", today()).remove("last_ms").apply()   // yesterday's leftovers
        p.edit().putBoolean("on", true).putInt("every", every).putBoolean("driver", driver).putString("date", today()).putString("user", user).apply()
        try {
            ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, TrackService::class.java))
        } catch (e: Exception) { /* the phone refuses a start right now: nothing is shared; the next screen tries again */ }
    }

    /** Stops the service. [clearBuffer]: also throw away points that were not sent yet (sign-out). */
    fun stop(ctx: Context, clearBuffer: Boolean = true) {
        val app = ctx.applicationContext
        val e = prefs(app).edit().putBoolean("on", false)
        if (clearBuffer) e.remove("buf").remove("last_ms")
        e.apply()
        try { app.stopService(Intent(app, TrackService::class.java)) } catch (ex: Exception) { }
        try { NotificationManagerCompat.from(app).cancel(NOTIF_ID) } catch (ex: Exception) { }
        running = false
    }

    /** The time is over (6 PM, or the driver's Day End): the service sends what is left, then switches itself off. */
    fun endAndFlush(ctx: Context) {
        val app = ctx.applicationContext
        try { app.startService(Intent(app, TrackService::class.java).putExtra("end", true)) } catch (e: Exception) { stop(app, clearBuffer = false) }
    }

    /** The first time, the person is told what is happening (once per account). The notification keeps telling them every day. */
    private fun askThenStart(a: AppCompatActivity, user: String, every: Int, driver: Boolean) {
        if (dialogShowing || a.isFinishing || a.isDestroyed) return
        dialogShowing = true
        AlertDialog.Builder(a)
            .setTitle("📍 Movement tracking is on for you")
            .setMessage("Your company has switched on movement tracking for your account.\n\n" +
                "After you submit attendance, this phone shares its location every $every minutes " + (if (driver) "until you send the Day End." else "until 6 PM.") +
                " A notification shows while it is on. Nothing is shared before your attendance or after that time.\n\n" +
                "Keep Location switched on, and set this app's battery use to Unrestricted so the phone does not stop it.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                dialogShowing = false
                prefs(a).edit().putBoolean("ack_$user", true).apply()
                start(a, user, every, driver)
            }
            .show()
    }

    /** Past the stop time (or a new day for a normal person)? */
    fun dueToEnd(ctx: Context): Boolean {
        val p = prefs(ctx)
        val sameDay = (p.getString("date", today()) ?: today()) == today()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (p.getBoolean("driver", false)) (!sameDay && hour >= DRIVER_UNTIL_HOUR) else (!sameDay || hour >= STOP_HOUR)
    }

    fun notification(ctx: Context, driver: Boolean, every: Int): Notification {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Movement tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while this phone shares its location (only for people your admin set up)."
                })
        }
        val open = PendingIntent.getActivity(
            app, NOTIF_ID, Intent(app, TrackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),    // tapping it shows the person's own route today
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("📍 Sharing your location")
            .setContentText("Every $every minutes " + (if (driver) "until you send the Day End" else "until 6 PM"))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ---- the points waiting to be sent (a small list in the app's private storage) ----

    @Synchronized fun addPoint(ctx: Context, ms: Long, lat: Double, lng: Double, acc: Int) {
        val p = prefs(ctx)
        val old = try { JSONArray(p.getString("buf", "[]")) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<JSONObject>()
        for (i in 0 until old.length()) list.add(old.getJSONObject(i))
        list.add(JSONObject().put("ms", ms).put("lat", lat).put("lng", lng).put("acc", acc))
        val keep = if (list.size > MAX_BUFFER) list.subList(list.size - MAX_BUFFER, list.size) else list
        p.edit().putString("buf", JSONArray(keep).toString()).apply()
    }

    @Synchronized fun takeBatch(ctx: Context): JSONArray {
        val old = try { JSONArray(prefs(ctx).getString("buf", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until minOf(old.length(), BATCH)) out.put(old.getJSONObject(i))
        return out
    }

    @Synchronized fun dropSent(ctx: Context, n: Int) {
        val p = prefs(ctx)
        val old = try { JSONArray(p.getString("buf", "[]")) } catch (e: Exception) { JSONArray() }
        val rest = JSONArray()
        for (i in n until old.length()) rest.put(old.getJSONObject(i))
        p.edit().putString("buf", rest.toString()).apply()
    }

    @Synchronized fun pending(ctx: Context): Int = try { JSONArray(prefs(ctx).getString("buf", "[]")).length() } catch (e: Exception) { 0 }

    /** "08:30:20" -> "08:30 AM"; "+00:20:00" (a driver after midnight) -> "12:20 AM · next day"; "" -> "". */
    fun nice(t: String): String {
        if (t.isBlank()) return ""
        val next = t.startsWith("+")
        val p = t.removePrefix("+").split(":")
        if (p.size < 2) return t
        val h = p[0].toIntOrNull() ?: return t
        val m = p[1].toIntOrNull() ?: return t
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%02d:%02d %s", h12, m, if (h < 12) "AM" else "PM") + (if (next) " · next day" else "")
    }
}

/** The foreground service behind [Tracking]: reads the location every few minutes and sends it to the server in groups. */
class TrackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var lastTry = 0L
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Tracking.prefs(this)
        if (intent?.getBooleanExtra("end", false) == true) {                 // the time is over: send what is left, then stop
            if (!Tracking.running) { stopSelf(); return START_NOT_STICKY }
            flush { finish() }
            return START_NOT_STICKY
        }
        val every = p.getInt("every", 10)
        // A service started with startForegroundService must show its notification within seconds, even when it then stops at once.
        try {
            ServiceCompat.startForeground(
                this, Tracking.NOTIF_ID, Tracking.notification(this, p.getBoolean("driver", false), every),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }       // the phone does not allow a start from the background right now
        val s = Session(this)
        if (!p.getBoolean("on", false) || !s.loggedIn || p.getString("user", "") != s.username || !Tracking.hasLocationPermission(this)) { finish(); return START_NOT_STICKY }
        Tracking.running = true
        listen(every)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 60_000)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")          // hasLocationPermission() was checked
    private fun listen(everyMinutes: Int) {
        val c = client ?: LocationServices.getFusedLocationProviderClient(this).also { client = it }
        callback?.let { try { c.removeLocationUpdates(it) } catch (e: Exception) { } }
        val interval = everyMinutes * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval * 8 / 10).setWaitForAccurateLocation(false).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.locations.forEach { onFix(it) } }
        }
        callback = cb
        try { c.requestLocationUpdates(request, cb, Looper.getMainLooper()) } catch (e: SecurityException) { finish(); return }
        // the first point at once, when the phone knows a fresh position
        c.lastLocation.addOnSuccessListener { l -> if (l != null && System.currentTimeMillis() - l.time < 120_000L) onFix(l) }
    }

    private fun onFix(l: Location) {
        if (isMockLocation(l)) return                                        // a fake-GPS app: nothing is saved
        if (l.hasAccuracy() && l.accuracy > 1000f) return                     // too rough to mean anything
        val p = Tracking.prefs(this)
        val interval = p.getInt("every", 10) * 60_000L
        val t = if (l.time > 0) l.time else System.currentTimeMillis()
        if (t - p.getLong("last_ms", 0L) < interval * 7 / 10) return          // another app made the location come sooner: one point per interval is enough
        p.edit().putLong("last_ms", t).apply()
        Tracking.addPoint(this, t, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toInt() else 0)
        flush(null)
    }

    /** Sends the waiting points in groups of [Tracking.BATCH]. [done] runs on the main thread when it is over (also when nothing could be sent). */
    private fun flush(done: (() -> Unit)?) {
        if (busy) { if (done != null) handler.postDelayed({ flush(done) }, 4000); return }
        if (Tracking.pending(this) == 0) { done?.invoke(); return }
        busy = true
        lastTry = SystemClock.elapsedRealtime()
        val app = applicationContext
        Thread {
            var stopNow = false
            try {
                while (true) {
                    val batch = Tracking.takeBatch(app)
                    if (batch.length() == 0) break
                    val r = postJson(app, JSONObject().put("action", "trackPoints").put("points", batch))
                    if (r.optBoolean("ok")) {
                        Tracking.dropSent(app, batch.length())
                        if (r.optBoolean("stop") || !r.optBoolean("tracked", true)) {
                            stopNow = true
                            if (r.optBoolean("tracked", true)) Tracking.prefs(app).edit().putString("stopped", Tracking.today()).apply()
                        }
                        val ev = r.optInt("everyMinutes", 0)
                        if (ev in 1..60) Tracking.prefs(app).edit().putInt("every", ev).apply()     // a new rhythm applies from the next start
                        if (batch.length() < Tracking.BATCH) break
                    } else { if (r.optBoolean("auth")) stopNow = true; break }                     // refused: the points wait; a signed-out phone stops
                }
            } catch (e: Exception) { /* no internet: the points wait for the next try */ }
            busy = false
            handler.post { if (stopNow) finish() else done?.invoke() }
        }.start()
    }

    /** Every minute: past the stop time -> send what is left and end; otherwise retry waiting points every two minutes. */
    private val tick = object : Runnable {
        override fun run() {
            if (Tracking.dueToEnd(this@TrackService)) { flush { finish() }; return }
            if (Tracking.pending(this@TrackService) > 0 && SystemClock.elapsedRealtime() - lastTry > 120_000L) flush(null)
            handler.postDelayed(this, 60_000)
        }
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { client?.removeLocationUpdates(it) } catch (e: Exception) { } }
        callback = null
        Tracking.prefs(this).edit().putBoolean("on", false).apply()
        Tracking.running = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { client?.removeLocationUpdates(it) } catch (e: Exception) { } }
        callback = null
        Tracking.running = false
        super.onDestroy()
    }
}
