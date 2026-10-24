package com.offline.pocketagent.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.offline.pocketagent.ModelStatus
import java.io.File
import java.time.Instant

/** Private app-only diagnostics. There is no logcat access, upload, or model-operation lock. */
object AppDiagnostics {
    @Volatile private var store: BoundedDiagnosticStore? = null

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        // An isolated PDF worker has a different UID and must not open host files.
        if (android.os.Process.myUid() != context.applicationInfo.uid) return
        runCatching { store = BoundedDiagnosticStore(File(context.applicationContext.filesDir, "diagnostics")) }
    }

    fun event(stage: String, fields: Map<String, String> = emptyMap()) {
        runCatching { store?.append("${Instant.now()} ${DiagnosticSanitizer.event(stage, fields)}") }
    }

    fun failure(stage: String, error: Throwable) {
        runCatching { store?.append("${Instant.now()} ${DiagnosticSanitizer.failure(stage, error)}") }
    }

    /** Safe to call during model loading: this only reads technical state and bounded log files. */
    fun collectReport(context: Context, status: ModelStatus? = null): String {
        initialize(context)
        event("diagnostics_collected")
        val app = context.applicationContext
        return buildString {
            append("Pocket Agent diagnostic report\n")
            append("Generated UTC: ").append(Instant.now()).append('\n')
            append("Privacy: document text, images, chat messages, filenames, file paths, URIs, and raw error messages are omitted.\n\n")
            append("App package: ").append(technical(app.packageName)).append('\n')
            runCatching {
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                append("App version: ").append(technical(info.versionName.orEmpty())).append('\n')
                append("App version code: ").append(info.longVersionCode).append('\n')
            }
            append("Android: ").append(technical(Build.VERSION.RELEASE)).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Device: ").append(technical(Build.MANUFACTURER)).append(' ').append(technical(Build.MODEL)).append('\n')
            append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.take(4).joinToString(", ") { technical(it) }).append('\n')
            append("Inference runtime: GenieX 0.9.3; local CPU\n")
            val runtime = Runtime.getRuntime()
            append("\nCurrent device snapshot (not tracked throughout the request)\n")
            append("Available processor count: ").append(runtime.availableProcessors()).append('\n')
            append("Java heap MB max/allocated/free: ").append(runtime.maxMemory() / MIB).append('/')
                .append(runtime.totalMemory() / MIB).append('/').append(runtime.freeMemory() / MIB).append('\n')
            runCatching {
                val memory = ActivityManager.MemoryInfo()
                app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                append("Device memory MB total/available: ").append(memory.totalMem / MIB).append('/').append(memory.availMem / MIB).append('\n')
                append("Low-memory flag: ").append(memory.lowMemory).append('\n')
            }
            runCatching { append("Private-storage free MB: ").append(app.filesDir.usableSpace / MIB).append('\n') }
            runCatching {
                val power = app.getSystemService(PowerManager::class.java)
                append("Power-save mode now: ").append(power.isPowerSaveMode).append('\n')
                append("Thermal status now: ").append(thermalStatus(power.currentThermalStatus)).append('\n')
            }
            runCatching {
                val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) append("Battery now: ").append((level.toLong() * 100 / scale).coerceIn(0, 100)).append("%\n")
                val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                if (state >= 0 || plugged >= 0) append("Charging/plugged now: ")
                    .append(state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL || plugged > 0).append('\n')
            }
            if (status != null) {
                append("\nModel state\n")
                append("Language model imported/loaded/loading: ").append(status.modelImported).append('/')
                    .append(status.modelLoaded).append('/').append(status.modelLoading).append('\n')
                append("Language readiness: ").append(status.languageState.name).append('\n')
                append("Vision file imported: ").append(status.projectorImported).append('\n')
                append("Native vision ready: ").append(status.visionLoaded).append('\n')
                append("Vision readiness: ").append(status.visionState.name).append('\n')
                append("Language/vision issue present: ").append(status.modelIssue != null).append('/')
                    .append(status.visionIssue != null).append('\n')
            }
            append("\nRecent own-app process exits\n")
            val exits = runCatching {
                app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 3)
            }.getOrNull()
            if (exits.isNullOrEmpty()) append("No recent exit metadata available.\n")
            else exits.forEach { exit ->
                append("UTC=").append(Instant.ofEpochMilli(exit.timestamp)).append(" reason=").append(exit.reason)
                    .append(" status=").append(exit.status).append(" importance=").append(exit.importance)
                    .append(" pss_kb=").append(exit.pss).append(" rss_kb=").append(exit.rss).append('\n')
            }
            val events = runCatching { store?.snapshot() }.getOrNull().orEmpty()
            append('\n').append(PerformanceSummary.render(events))
            append("\nRecent technical events (oldest first)\n")
            append(events.ifBlank { "No recorded events are available.\n" })
            append("\nEnd of diagnostic report.\n")
        }
    }

    fun clear() {
        runCatching { store?.clear() }
        event("diagnostics_cleared")
    }

    private fun technical(value: String): String = value.take(100).replace(Regex("[^A-Za-z0-9 ._+()-]"), "_")
    private fun thermalStatus(value: Int): String = when (value) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unavailable"
    }
    private const val MIB = 1024L * 1024
}
