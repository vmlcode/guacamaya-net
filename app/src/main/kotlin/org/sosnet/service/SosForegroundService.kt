package org.sosnet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.sosnet.R
import org.sosnet.ble.BleLifecycleCoordinator
import org.sosnet.ble.Broadcaster
import org.sosnet.ble.Observer
import org.sosnet.crypto.Identity
import org.sosnet.mesh.FloodRouter
import org.sosnet.mesh.SOSNetDatabase
import org.sosnet.proto.Flags
import org.sosnet.proto.Payload
import org.sosnet.proto.SosType
import org.sosnet.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps BLE alive while backgrounded.
 *
 * ACTION_START — broadcast SOS + observe (full mesh relay).
 * ACTION_STOP — stop broadcasting (keep observing if active).
 * ACTION_OBSERVE_ON / ACTION_OBSERVE_OFF — listen-only relay node.
 * ACTION_RESTORE — resume persisted state after boot or process restart.
 */
class SosForegroundService : Service() {

    private val tag = "sosnet.service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var broadcaster: Broadcaster? = null
    private var observer: Observer? = null
    private var router: FloodRouter? = null
    private var identity: Identity? = null
    private var btCoordinator: BleLifecycleCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isBroadcasting = false
    private var isObserving = false
    private var appInForeground = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        btCoordinator = BleLifecycleCoordinator(this) { restoreBleIfNeeded() }.also { it.register() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        BleServiceState.update(serviceRunning = true)

        when (intent?.action) {
            ACTION_START -> {
                isBroadcasting = true
                isObserving = true
                persistAndSync()
                startBroadcasting()
                startObserving()
            }
            ACTION_STOP -> {
                isBroadcasting = false
                persistAndSync()
                stopBroadcasting()
                releaseWakeLock()
            }
            ACTION_OBSERVE_ON -> {
                isObserving = true
                persistAndSync()
                startObserving()
            }
            ACTION_OBSERVE_OFF -> {
                isObserving = false
                if (!isBroadcasting) {
                    stopObserving()
                    maybeStopSelf()
                } else {
                    persistAndSync()
                }
            }
            ACTION_RESTORE -> {
                val (bcast, obs) = ServicePrefs.load(this)
                isBroadcasting = bcast
                isObserving = obs
                persistAndSync()
                if (isBroadcasting) startBroadcasting()
                if (isObserving) startObserving()
                if (!isBroadcasting && !isObserving) maybeStopSelf()
            }
            ACTION_SET_FOREGROUND -> {
                appInForeground = intent.getBooleanExtra(EXTRA_FOREGROUND, true)
                observer?.setForegroundMode(appInForeground)
                updateNotification()
            }
            ACTION_STOP_SOS_FROM_NOTIF -> {
                isBroadcasting = false
                persistAndSync()
                stopBroadcasting()
                releaseWakeLock()
                updateNotification()
            }
            ACTION_STOP_OBSERVE_FROM_NOTIF -> {
                isObserving = false
                persistAndSync()
                stopObserving()
                if (!isBroadcasting) maybeStopSelf()
                else updateNotification()
            }
            else -> {
                val (bcast, obs) = ServicePrefs.load(this)
                if (bcast || obs) {
                    isBroadcasting = bcast
                    isObserving = obs
                    persistAndSync()
                    if (isBroadcasting) startBroadcasting()
                    if (isObserving) startObserving()
                }
            }
        }
        return START_STICKY
    }

    private fun restoreBleIfNeeded() {
        if (isBroadcasting) startBroadcasting()
        if (isObserving) startObserving()
    }

    private fun persistAndSync() {
        ServicePrefs.save(this, isBroadcasting, isObserving)
        BleServiceState.update(broadcasting = isBroadcasting, observing = isObserving)
        updateNotification()
    }

    private fun ensureForeground() {
        createChannelsIfNeeded()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannelsIfNeeded() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.channel_desc) }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_SOS_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SOS_ID, getString(R.string.channel_sos_name), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = getString(R.string.channel_sos_desc) }
            )
        }
    }

    private fun buildNotification(): Notification {
        val channelId = if (isBroadcasting) CHANNEL_SOS_ID else CHANNEL_ID
        val contentText = when {
            isBroadcasting && isObserving -> getString(R.string.status_mesh_active)
            isBroadcasting -> getString(R.string.status_broadcasting)
            isObserving -> getString(R.string.status_listening)
            else -> getString(R.string.service_running)
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .setOngoing(true)

        if (isBroadcasting) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.sos_stop),
                    pendingServiceAction(ACTION_STOP_SOS_FROM_NOTIF, 1),
                ).build()
            )
        }
        if (isObserving) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.observe_stop),
                    pendingServiceAction(ACTION_STOP_OBSERVE_FROM_NOTIF, 2),
                ).build()
            )
        }
        return builder.build()
    }

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SosForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun ensureObserverAndRouter() {
        if (observer != null && router != null) return
        val obs = Observer.create(this) ?: run {
            Log.e(tag, "Observer.create failed")
            return
        }
        val dao = SOSNetDatabase.get(this).messageDao()
        val bcast = broadcaster ?: Broadcaster.create(this)
        val r = FloodRouter(dao = dao, broadcaster = bcast, scope = scope)
        obs.setListener { p22, pub32, sig64, rssi ->
            BleServiceState.recordRssi(rssi)
            r.onFrame(p22, pub32, sig64, rssi)
        }
        observer = obs
        router = r
        broadcaster = bcast
    }

    private fun startBroadcasting() {
        acquireWakeLock()
        scope.launch {
            val id = identity ?: Identity.loadOrCreate(this@SosForegroundService).also { identity = it }
            val payload = Payload(
                latE7 = 0,
                lonE7 = 0,
                tsUnix = System.currentTimeMillis() / 1000,
                nodeId = id.nodeId,
                flags = Flags.origin(hasHeavy = false, critical = true, batteryBucket = 3),
                sosType = SosType.DISTRESS,
                msgId = (System.currentTimeMillis() and 0xFFFF).toInt(),
            ).encode()
            val sig = id.sign(payload)
            val bcast = broadcaster ?: Broadcaster.create(this@SosForegroundService)?.also { broadcaster = it }
            if (bcast == null) {
                Log.e(tag, "Broadcaster.create failed")
                isBroadcasting = false
                persistAndSync()
            } else {
                bcast.start(payload, id.publicKey, sig)
            }
        }
    }

    private fun stopBroadcasting() {
        broadcaster?.stop()
    }

    private fun startObserving() {
        ensureObserverAndRouter()
        observer?.start(appInForeground)
    }

    private fun stopObserving() {
        observer?.stop()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sosnet:sos_broadcast").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun maybeStopSelf() {
        if (!isBroadcasting && !isObserving) {
            BleServiceState.update(broadcasting = false, observing = false, serviceRunning = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        btCoordinator?.unregister()
        observer?.stop()
        broadcaster?.stop()
        releaseWakeLock()
        scope.cancel()
        BleServiceState.reset()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.sosnet.action.START"
        const val ACTION_STOP = "org.sosnet.action.STOP"
        const val ACTION_OBSERVE_ON = "org.sosnet.action.OBSERVE_ON"
        const val ACTION_OBSERVE_OFF = "org.sosnet.action.OBSERVE_OFF"
        const val ACTION_RESTORE = "org.sosnet.action.RESTORE"
        const val ACTION_SET_FOREGROUND = "org.sosnet.action.SET_FOREGROUND"
        const val ACTION_STOP_SOS_FROM_NOTIF = "org.sosnet.action.STOP_SOS_NOTIF"
        const val ACTION_STOP_OBSERVE_FROM_NOTIF = "org.sosnet.action.STOP_OBSERVE_NOTIF"
        const val EXTRA_FOREGROUND = "foreground"

        private const val NOTIF_ID = 0x5050
        private const val CHANNEL_ID = "sosnet.service"
        private const val CHANNEL_SOS_ID = "sosnet.sos"

        fun setAppForeground(context: Context, foreground: Boolean) {
            val intent = Intent(context, SosForegroundService::class.java).apply {
                action = ACTION_SET_FOREGROUND
                putExtra(EXTRA_FOREGROUND, foreground)
            }
            context.startService(intent)
        }
    }
}
