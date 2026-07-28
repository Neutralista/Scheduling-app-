package com.waypoint.app.signal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse

enum class HealthConnectAvailability {
    AVAILABLE,
    NOT_INSTALLED,
    NOT_SUPPORTED
}

/**
 * Exposes Health Connect data to widgets. A widget that reads step counts,
 * workouts, sleep, etc. calls readRecords() from its own coroutine scope
 * after checking hasPermission for the specific health permission it needs.
 *
 * Availability and permissions are checked lazily — the framework does not
 * require Health Connect to be present.
 */
interface HealthConnectSignals {
    val availability: HealthConnectAvailability
    suspend fun hasPermission(permission: String): Boolean
    suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T>
}

class RealHealthConnectSignals(private val context: Context) : HealthConnectSignals {

    override val availability: HealthConnectAvailability
        get() = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE ->
                HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.NOT_INSTALLED
            else ->
                HealthConnectAvailability.NOT_SUPPORTED
        }

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    override suspend fun hasPermission(permission: String): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions().contains(permission)
    }

    override suspend fun <T : Record> readRecords(
        request: ReadRecordsRequest<T>
    ): ReadRecordsResponse<T> {
        val c = client ?: error("Health Connect is not available on this device")
        return c.readRecords(request)
    }
}
