package com.waypoint.app.signal

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.Duration
import java.util.Calendar

/**
 * Exposes per-app foreground usage time for today via UsageStatsManager.
 *
 * Requires the PACKAGE_USAGE_STATS special permission, which the user must
 * grant manually via Settings → Apps → Special app access → Usage access.
 * hasPermission can be checked before reading data, and the app should
 * redirect to the permission rationale screen if it returns false.
 */
interface AppUsageSignals {
    val hasPermission: Boolean
    fun usageToday(packageName: String): Duration
    fun allUsageToday(): Map<String, Duration>
}

class RealAppUsageSignals(private val context: Context) : AppUsageSignals {

    override val hasPermission: Boolean
        get() {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

    override fun usageToday(packageName: String): Duration =
        allUsageToday()[packageName] ?: Duration.ZERO

    override fun allUsageToday(): Map<String, Duration> {
        if (!hasPermission) return emptyMap()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            .orEmpty()
            .associate { stats ->
                stats.packageName to Duration.ofMillis(stats.totalTimeInForeground)
            }
    }
}
