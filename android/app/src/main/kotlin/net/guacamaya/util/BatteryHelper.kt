package net.guacamaya.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Battery / MIUI background limits. Never auto-launch system screens from adb
 * service intents — that hijacks automation on sweet (PowerDetailActivity).
 */
object BatteryHelper {
    private const val TAG = "guacamaya.battery"
    private const val PREFS = "guacamaya_battery"
    private const val KEY_DISMISSED = "hint_dismissed"

    fun isExempt(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun isHintDismissed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISMISSED, false)

    fun dismissHint(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()
    }

    fun shouldShowHint(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isExempt(ctx) && !isHintDismissed(ctx)

    /** User tapped — open the least intrusive settings screen available. */
    fun openSettings(ctx: Context) {
        if (isExempt(ctx)) return
        if (isXiaomi()) {
            if (tryStart(ctx, miuiAutostartIntent())) return
            if (tryStart(ctx, miuiBatteryIntent())) return
        }
        if (tryStart(ctx, ignoreOptimizationsIntent(ctx))) return
        tryStart(ctx, appDetailsIntent(ctx))
    }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m == "xiaomi" || m == "redmi" || m == "poco"
    }

    private fun ignoreOptimizationsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    private fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /** MIUI autostart — required for BLE FGS on sweet class devices. */
    private fun miuiAutostartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    }

    private fun miuiBatteryIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.powercenter.powersaver.PowerMainActivity",
        )
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "cannot open ${intent.component ?: intent.action}: ${e.message}")
        false
    }
}
