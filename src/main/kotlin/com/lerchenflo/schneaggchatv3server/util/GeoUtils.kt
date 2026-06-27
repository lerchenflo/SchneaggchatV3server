package com.lerchenflo.schneaggchatv3server.util

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geo math shared by anything that needs to measure distance between two coordinates (e.g. the
 * 24h-distance-traveled calculation in `UserLocationService`). There was no existing geo utility in
 * the codebase, so this is the single source of truth for distance math going forward.
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Segments shorter than this are treated as GPS jitter (a stationary phone reporting slightly
     * different coordinates) and don't count towards distance traveled.
     */
    const val MIN_SEGMENT_METERS = 5.0

    /** Great-circle distance between [a] and [b] in meters (haversine formula). */
    fun haversineMeters(a: LatLong, b: LatLong): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val deltaLat = Math.toRadians(b.lat - a.lat)
        val deltaLong = Math.toRadians(b.long - a.long)

        val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1) * cos(lat2) * sin(deltaLong / 2) * sin(deltaLong / 2)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))

        return EARTH_RADIUS_METERS * c
    }
}
