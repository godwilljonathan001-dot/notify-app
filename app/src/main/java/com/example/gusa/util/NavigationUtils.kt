package com.example.gusa.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * Professional Navigation Mathematics for GUSA.
 */
object NavigationUtils {

    /**
     * Snaps a LatLng to the nearest point on a polyline.
     */
    fun snapToPolyline(point: LatLng, polyline: List<LatLng>): LatLng {
        if (polyline.isEmpty()) return point
        if (polyline.size == 1) return polyline[0]

        var minDistance = Double.MAX_VALUE
        var snappedPoint = point

        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i + 1]
            val currentSnap = findNearestPointOnSegment(point, p1, p2)
            val distance = calculateDistance(point, currentSnap)
            if (distance < minDistance) {
                minDistance = distance
                snappedPoint = currentSnap
            }
        }

        // Only snap if we are within a reasonable distance (e.g., 50 meters)
        return if (minDistance < 50.0) snappedPoint else point
    }

    private fun findNearestPointOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val latP = p.latitude
        val lngP = p.longitude
        val latA = a.latitude
        val lngA = a.longitude
        val latB = b.latitude
        val lngB = b.longitude

        val l2 = (latB - latA).pow(2) + (lngB - lngA).pow(2)
        if (l2 == 0.0) return a

        var t = ((latP - latA) * (latB - latA) + (lngP - lngA) * (lngB - lngA)) / l2
        t = max(0.0, min(1.0, t))

        return LatLng(latA + t * (latB - latA), lngA + t * (lngB - lngA))
    }

    private fun calculateDistance(p1: LatLng, p2: LatLng): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLng = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Smoothly interpolates between two bearings.
     */
    fun interpolateBearing(start: Float, end: Float, fraction: Float): Float {
        var diff = end - start
        while (diff < -180) diff += 360
        while (diff >= 180) diff -= 360
        return start + fraction * diff
    }
}
