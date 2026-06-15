package atlas.netatlas.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import atlas.collect.DeviceInfo
import atlas.collect.LocationFix
import atlas.collect.SignalMapper
import atlas.netatlas.collect.db.CollectorDb
import atlas.netatlas.collect.db.ReadingEntity
import atlas.netatlas.collect.location.LocationReader
import atlas.netatlas.collect.telephony.TelephonyReader
import atlas.netatlas.collect.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground [Service] (type `location`) that drives the collection loop: every
 * ~[SAMPLE_INTERVAL_MS] it reads a GPS fix and, if the device has moved at least
 * [MIN_DISTANCE_M] since the last persisted fix, captures a telephony sample,
 * builds a [atlas.model.SignalReading] via [SignalMapper], queues it in Room, and
 * periodically kicks the [UploadWorker].
 *
 * The signal/location/telephony plumbing is delegated to readers; this class owns
 * the lifecycle, notification, sampling cadence, and distance gate.
 */
class CollectorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var db: CollectorDb
    private lateinit var locationReader: LocationReader
    private lateinit var telephonyReader: TelephonyReader

    private var lastFix: Location? = null
    private var ticksSinceUpload = 0
    private var loopStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(applicationContext, CollectorDb::class.java, UploadWorker.DB_NAME).build()
        locationReader = LocationReader(applicationContext)
        telephonyReader = TelephonyReader(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        // Idempotent: redundant start commands (e.g. START_STICKY re-delivery) must
        // not spawn a second sampling loop on the same service instance.
        if (!loopStarted) {
            loopStarted = true
            startLoop()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("netatlas is mapping coverage")
            .setContentText("Recording signal readings in the background.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Coverage mapping",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Ongoing collection of cellular coverage readings." },
                )
            }
        }
    }

    private fun startLoop() {
        scope.launch {
            val deviceId = stableDeviceId(applicationContext)
            val device = currentDeviceInfo()
            while (isActive) {
                runCatching { tick(deviceId, device) }
                    .onFailure { Log.w(TAG, "tick failed", it) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    @Suppress("MissingPermission") // Permissions are requested by the host UI before start.
    private suspend fun tick(deviceId: String, device: DeviceInfo) {
        val location = locationReader.current()
        if (location == null) {
            Log.d(TAG, "tick: no location fix, skipping")
            return
        }
        if (!movedEnough(location)) {
            Log.d(TAG, "tick: under min-distance gate, skipping")
            return
        }
        val sample = telephonyReader.read()
        if (sample == null) {
            Log.d(TAG, "tick: telephony returned null (expected on emulator), skipping")
            return
        }
        val carrier = telephonyReader.carrierId()
        if (carrier == null) {
            Log.d(TAG, "tick: no carrier id (no SIM?), skipping")
            return
        }
        val reading = SignalMapper.toReading(
            deviceId = deviceId,
            tsEpochMs = System.currentTimeMillis(),
            sample = sample,
            location = location,
            carrier = carrier,
            device = device,
        )
        db.readingDao().insertAll(listOf(ReadingEntity.fromSignalReading(reading)))
        lastFix = location.toAndroidLocation()
        Log.d(TAG, "tick: inserted reading dbm=${reading.signalDbm} net=${reading.networkType} unsent=${db.readingDao().unsentCount()}")

        if (++ticksSinceUpload >= UPLOAD_EVERY_N_TICKS) {
            ticksSinceUpload = 0
            UploadWorker.enqueue(applicationContext)
        }
    }

    /** True if there is no prior fix, or the new fix is >= [MIN_DISTANCE_M] from it. */
    private fun movedEnough(fix: LocationFix): Boolean {
        val prev = lastFix ?: return true
        val results = FloatArray(1)
        Location.distanceBetween(prev.latitude, prev.longitude, fix.lat, fix.lng, results)
        return results[0] >= MIN_DISTANCE_M
    }

    private fun LocationFix.toAndroidLocation(): Location =
        Location("collector").also {
            it.latitude = lat
            it.longitude = lng
        }

    private fun currentDeviceInfo(): DeviceInfo = DeviceInfo(
        phoneMake = Build.MANUFACTURER ?: "unknown",
        phoneModel = Build.MODEL ?: "unknown",
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        appVersion = APP_VERSION,
    )

    override fun onDestroy() {
        scope.cancel()
        if (::db.isInitialized) db.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "netatlas"
        const val CHANNEL_ID = "netatlas-collection"
        private const val NOTIFICATION_ID = 1001
        private const val APP_VERSION = "0.1.0"

        /** Sampling cadence. */
        const val SAMPLE_INTERVAL_MS = 5_000L

        /** Minimum movement (metres) between persisted readings. */
        const val MIN_DISTANCE_M = 25f

        /** Trigger an upload attempt every N persisted readings. */
        private const val UPLOAD_EVERY_N_TICKS = 5

        private const val PREFS = "netatlas_collector"
        private const val KEY_DEVICE_ID = "device_id"

        /** A stable, random per-install device id (UUID), generated once. */
        fun stableDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

        fun start(context: Context) {
            val intent = Intent(context, CollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CollectorService::class.java))
        }
    }
}
