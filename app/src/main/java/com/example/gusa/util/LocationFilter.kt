package com.example.gusa.util

import android.location.Location
import kotlin.math.*

/**
 * Professional GPS Data Pipeline for GUSA
 * Implements 2D Kalman Filtering, Speed Validation, and Jitter Reduction.
 */
class LocationFilter(private val minDistance: Float = 3.0f) {

    private var lastLocation: Location? = null
    
    // Kalman Filter Parameters
    private var variance: Double = -1.0 // P: Process covariance
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var timeStamp: Long = 0
    private val qMetresPerSecond = 3.0 // Process noise

    fun process(newLoc: Location): Location? {
        // 1. Accuracy Validation
        if (newLoc.accuracy > 50) return null

        val currentTime = newLoc.time
        
        // Initial state
        if (variance < 0) {
            variance = (newLoc.accuracy * newLoc.accuracy).toDouble()
            lat = newLoc.latitude
            lng = newLoc.longitude
            timeStamp = currentTime
            lastLocation = newLoc
            return newLoc
        }

        // 2. Kalman Filter - Time Step
        val timeDelta = (currentTime - timeStamp) / 1000.0
        if (timeDelta > 0) {
            variance += timeDelta * qMetresPerSecond * qMetresPerSecond
            timeStamp = currentTime
        }

        // 3. Kalman Filter - Measurement Update
        val k = variance / (variance + (newLoc.accuracy * newLoc.accuracy))
        lat += k * (newLoc.latitude - lat)
        lng += k * (newLoc.longitude - lng)
        variance *= (1.0 - k)

        val filteredLoc = Location(newLoc.provider).apply {
            latitude = lat
            longitude = lng
            accuracy = sqrt(variance).toFloat()
            time = currentTime
            bearing = newLoc.bearing
            speed = newLoc.speed
        }

        // 4. Speed & Distance Validation
        val prev = lastLocation ?: return filteredLoc
        val distance = filteredLoc.distanceTo(prev)
        
        // Reject impossible speed (> 150 km/h)
        if (timeDelta > 0) {
            val speedKmh = (distance / timeDelta) * 3.6
            if (speedKmh > 150.0) return null
        }

        // 5. Distance Threshold (3 Meters)
        if (distance < minDistance) {
            return null
        }

        lastLocation = filteredLoc
        return filteredLoc
    }
}
