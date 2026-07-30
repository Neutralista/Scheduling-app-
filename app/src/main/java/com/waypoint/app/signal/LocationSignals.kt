package com.waypoint.app.signal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface LocationSignals {
    fun hasPermission(): Boolean
    val cachedLatitude: Double
    val cachedLongitude: Double
    val cachedAccuracy: Float
    suspend fun refreshCache()
    /** Haversine distance in metres between two coordinates. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
}

class RealLocationSignals(private val context: Context) : LocationSignals {

    @Volatile private var _latitude:  Double = 0.0
    @Volatile private var _longitude: Double = 0.0
    @Volatile private var _accuracy:  Float  = Float.MAX_VALUE

    override val cachedLatitude:  Double get() = _latitude
    override val cachedLongitude: Double get() = _longitude
    override val cachedAccuracy:  Float  get() = _accuracy

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun refreshCache() = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
         .minByOrNull { it.accuracy }
        if (best != null) {
            _latitude  = best.latitude
            _longitude = best.longitude
            _accuracy  = best.accuracy
        }
    }

    override fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
