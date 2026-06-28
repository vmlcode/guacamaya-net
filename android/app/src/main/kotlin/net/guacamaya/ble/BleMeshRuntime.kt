package net.guacamaya.ble

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.guacamaya.mesh.FloodRouter
import net.guacamaya.mesh.GuacamayaDatabase

/**
 * Shared BLE observer + mesh router. Started from FGS and from MainActivity.onResume
 * so MIUI/API 30 still scans when adb launches the activity briefly in foreground.
 */
object BleMeshRuntime {
    private const val PROBE = "guacamaya.probe"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Observer? = null
    private var router: FloodRouter? = null

    fun ensureObserving(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (observer == null || router == null) {
            val obs = Observer.create(app) ?: run {
                Log.i(PROBE, "observe fail Observer.create=null")
                return false
            }
            val dao = GuacamayaDatabase.get(app).messageDao()
            val bcast = Broadcaster.create(app)
            val r = FloodRouter(dao = dao, broadcaster = bcast, scope = scope)
            obs.setListener { p22, pub32, sig64, ttl, rssi ->
                r.onFrame(p22, pub32, sig64, ttl, rssi)
            }
            observer = obs
            router = r
        }
        val obs = observer ?: return false
        if (obs.isScanning) {
            Log.i(PROBE, "BleMeshRuntime already scanning")
            return true
        }
        obs.start()
        Log.i(PROBE, "BleMeshRuntime scanning=${obs.isScanning} device=${Build.DEVICE}")
        return obs.isScanning
    }

    fun stopObserving() {
        observer?.stop()
        Log.i(PROBE, "BleMeshRuntime stopped")
    }

    fun isScanning(): Boolean = observer?.isScanning == true
}
