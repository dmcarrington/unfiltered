package com.nostr.unfiltered.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One-shot location fetcher. Wraps Android's [LocationManager] (no Google
 * Play Services dependency) and returns a single fix or null if the
 * device can't produce one within [timeoutMs].
 *
 * Designed to be called on-demand (e.g. when the user opens the Nearby
 * feed). It does NOT subscribe to ongoing location updates and does NOT
 * wake the device in the background.
 *
 * Permission handling is the caller's responsibility: callers should
 * check [hasLocationPermission] before invoking [getCurrentLocation],
 * and trigger the system permission flow if not.
 */
@Singleton
class LocationProvider @Inject constructor(
    private val context: Context
) {

    /**
     * True if the coarse location permission has been granted.
     *
     * We intentionally check COARSE only — the Nearby feed uses ~5 km
     * geohash cells and never needs precise GPS. Asking for fine
     * location would surface an unnecessarily alarming permission
     * dialog to the user.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the device's current location as a one-shot.
     *
     * Strategy:
     *  1. Check the last-known fix from GPS, NETWORK, and PASSIVE providers
     *     — if any is fresh enough, return it immediately (zero-cost path).
     *  2. Otherwise request a single live update from GPS (preferred) or
     *     NETWORK (fallback) using [LocationManager.getCurrentLocation] on
     *     API 30+, or [LocationManager.requestSingleUpdate] below.
     *
     * Returns null if location is disabled at the OS level, no permission
     * has been granted, or no fix arrives before [timeoutMs].
     */
    suspend fun getCurrentLocation(timeoutMs: Long = 5000L): Location? {
        if (!hasLocationPermission()) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // 1. Try last-known fix first (zero-cost path).
        val lastKnown = bestLastKnown(lm)
        if (lastKnown != null && isFresh(lastKnown, timeoutMs)) {
            return lastKnown
        }

        // 2. Request a single live update.
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnown // return stale one if we have it
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getCurrentLocationModern(lm, provider)
        } else {
            getCurrentLocationLegacy(lm, provider)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationModern(
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        val cancellationSignal = CancellationSignal()
        try {
            lm.getCurrentLocation(
                provider,
                cancellationSignal,
                ContextCompat.getMainExecutor(context),
                Consumer { location ->
                    if (cont.isActive) cont.resume(location)
                }
            )
        } catch (e: SecurityException) {
            // Permission revoked between check and call.
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: IllegalArgumentException) {
            // Provider doesn't exist on this device.
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { cancellationSignal.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationLegacy(
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (cont.isActive) cont.resume(location)
                runCatching { lm.removeUpdates(this) }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { /* no-op */ }
            override fun onProviderEnabled(provider: String) { /* no-op */ }
            override fun onProviderDisabled(provider: String) {
                if (cont.isActive) cont.resume(null)
                runCatching { lm.removeUpdates(this) }
            }
        }

        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * "Fresh" here means received within roughly [timeoutMs * 4] of now.
     * Last-known fixes from hours ago are too stale to trust for a
     * Nearby feed.
     */
    private fun isFresh(loc: Location, timeoutMs: Long): Boolean {
        val ageMs = System.currentTimeMillis() - loc.time
        return ageMs in 0..(timeoutMs * 4)
    }
}