package com.example.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    suspend fun getLocationData(
        fresh: Boolean = true,
        geocode: Boolean = true
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext mapOf(
                "success" to false,
                "error" to "Location permission not granted. Please grant Location permission in Ghost settings.",
                "permissionGranted" to false
            )
        }

        val lm = locationManager
        if (lm == null || !isLocationEnabled()) {
            return@withContext mapOf(
                "success" to false,
                "error" to "Device location/GPS is turned off. Please enable Location services on the device.",
                "locationEnabled" to false
            )
        }

        var location: Location? = null

        // If fresh location is requested, attempt to get a direct high-accuracy fix with a 5-second timeout
        if (fresh) {
            location = withTimeoutOrNull(5000L) {
                requestFreshLocation()
            }
        }

        // Fallback to best last known location if fresh fix timed out or was null
        if (location == null) {
            location = getBestLastKnownLocation()
        }

        if (location == null) {
            return@withContext mapOf(
                "success" to false,
                "error" to "Unable to obtain location fix. Ensure GPS or Wi-Fi is active."
            )
        }

        val lat = location.latitude
        val lng = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0
        val provider = location.provider ?: "unknown"
        val timestamp = location.time
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

        val resultMap = mutableMapOf<String, Any>(
            "success" to true,
            "latitude" to lat,
            "longitude" to lng,
            "accuracy" to accuracy,
            "altitude" to altitude,
            "speed" to speed,
            "bearing" to bearing,
            "provider" to provider,
            "timestamp" to timestamp,
            "mapsUrl" to mapsUrl
        )

        if (geocode) {
            val addressMap = resolveAddress(lat, lng)
            if (addressMap != null) {
                resultMap["address"] = addressMap
            }
        }

        resultMap
    }

    private suspend fun requestFreshLocation(): Location? {
        val lm = locationManager ?: return null

        // Android R (API 30+) modern getCurrentLocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> LocationManager.PASSIVE_PROVIDER
                }

                return suspendCancellableCoroutine { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation {
                        cancellationSignal.cancel()
                    }

                    try {
                        lm.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            context.mainExecutor
                        ) { loc ->
                            continuation.resume(loc)
                        }
                    } catch (e: SecurityException) {
                        Log.e("LocationHelper", "SecurityException requesting location", e)
                        continuation.resume(null)
                    } catch (e: Exception) {
                        Log.e("LocationHelper", "Error in getCurrentLocation", e)
                        continuation.resume(null)
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationHelper", "Failed modern location request", e)
            }
        }

        // Legacy / Fallback single location request listener
        return suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    try {
                        lm.removeUpdates(this)
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (continuation.isActive) {
                        continuation.resume(loc)
                    }
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            continuation.invokeOnCancellation {
                try {
                    lm.removeUpdates(listener)
                } catch (e: Exception) {
                    // ignore
                }
            }

            try {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.NETWORK_PROVIDER
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.e("LocationHelper", "Failed requestSingleUpdate", e)
                continuation.resume(null)
            }
        }
    }

    private fun getBestLastKnownLocation(): Location? {
        val lm = locationManager ?: return null
        val providers = lm.getProviders(true)
        var bestLocation: Location? = null

        for (provider in providers) {
            try {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy || l.time > bestLocation.time) {
                    bestLocation = l
                }
            } catch (e: SecurityException) {
                Log.e("LocationHelper", "SecurityException getting last known location from $provider", e)
            } catch (e: Exception) {
                Log.e("LocationHelper", "Error getting last known location from $provider", e)
            }
        }

        return bestLocation
    }

    private fun resolveAddress(lat: Double, lng: Double): Map<String, String>? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val fullAddress = (0..addr.maxAddressLineIndex)
                    .mapNotNull { addr.getAddressLine(it) }
                    .joinToString(", ")

                mapOf(
                    "fullAddress" to (if (fullAddress.isNotBlank()) fullAddress else "${addr.locality ?: ""}, ${addr.countryName ?: ""}"),
                    "street" to (addr.thoroughfare ?: ""),
                    "subThoroughfare" to (addr.subThoroughfare ?: ""),
                    "city" to (addr.locality ?: addr.subAdminArea ?: ""),
                    "state" to (addr.adminArea ?: ""),
                    "country" to (addr.countryName ?: ""),
                    "postalCode" to (addr.postalCode ?: "")
                )
            } else null
        } catch (e: Exception) {
            Log.e("LocationHelper", "Geocoding failed", e)
            null
        }
    }
}
