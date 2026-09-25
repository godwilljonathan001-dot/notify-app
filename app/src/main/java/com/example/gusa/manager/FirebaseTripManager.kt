package com.example.gusa.manager

import android.location.Location
import android.util.Log
import com.example.gusa.model.Stop
import com.google.firebase.Timestamp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class FirebaseTripManager {
    private val db = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance().getReference("activeTrips")

    private var lastFirestoreUpdate = 0L

    suspend fun startTrip(busId: String, driverId: String, routeId: String, direction: String, location: Location, stops: List<Stop>) {
        val timestamp = System.currentTimeMillis()
        val authUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        
        lastFirestoreUpdate = timestamp

        // Firestore: Static trip metadata
        val tripData = hashMapOf(
            "driverId" to driverId,
            "driverUid" to authUid,
            "busId" to busId,
            "routeId" to routeId,
            "startTime" to Timestamp.now(),
            "status" to "Running",
            "direction" to direction,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "currentLocation" to GeoPoint(location.latitude, location.longitude),
            "nextStop" to (stops.getOrNull(0)?.name ?: "End"),
            "passengerCount" to 0,
            "lastUpdated" to Timestamp.now()
        )
        db.collection("activeTrips").document(busId).set(tripData).await()

        // RTDB: Live dynamic data
        val liveData = hashMapOf(
            "driverUid" to authUid,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "speed" to 0.0,
            "heading" to 0.0,
            "lastUpdated" to timestamp,
            "nextStop" to (stops.getOrNull(0)?.name ?: "End"),
            "status" to "Running",
            "busId" to busId,
            "routeId" to routeId,
            "direction" to direction
        )
        rtdb.child(busId).setValue(liveData as Map<String, Any>).await()
    }

    fun updateLiveLocation(busId: String, driverDocId: String, location: Location, nextStop: Stop?, distToNext: Float) {
        val timestamp = System.currentTimeMillis()
        
        // Requirement #20: Realistic ETA calculation
        val speedKmh = location.speed * 3.6
        val effectiveSpeed = if (speedKmh > 5) speedKmh else 25.0 // Fallback to 25km/h
        val timeMinutes = ((distToNext / 1000) / effectiveSpeed * 60).roundToInt().coerceAtLeast(1)
        
        // Update Realtime Database (High frequency)
        val liveUpdates = hashMapOf<String, Any>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "speed" to location.speed,
            "heading" to location.bearing,
            "lastUpdated" to timestamp,
            "nextStop" to (nextStop?.name ?: "End of Route"),
            "remainingDistance" to "${"%.2f".format(distToNext / 1000)} km",
            "ETA" to "$timeMinutes mins",
            "status" to "Running"
        )
        rtdb.child(busId).updateChildren(liveUpdates)

        // Firestore: Periodic metadata/coarse location sync (Every 10 seconds)
        if (timestamp - lastFirestoreUpdate >= 10000L) {
            lastFirestoreUpdate = timestamp
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            val firestoreUpdates = hashMapOf<String, Any>(
                "currentLocation" to geoPoint,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "lastUpdated" to Timestamp.now(),
                "nextStop" to (nextStop?.name ?: "End of Route"),
                "remainingDistance" to "${"%.2f".format(distToNext / 1000)} km"
            )
            db.collection("activeTrips").document(busId).update(firestoreUpdates)
            
            if (driverDocId.isNotEmpty()) {
                db.collection("drivers").document(driverDocId).update(
                    "latitude", location.latitude,
                    "longitude", location.longitude,
                    "geoPoint", geoPoint,
                    "lastUpdated", Timestamp.now()
                )
            }
        }
    }

    suspend fun endTrip(busId: String, driverDocId: String) {
        try {
            val doc = db.collection("activeTrips").document(busId).get().await()
            if (doc.exists()) {
                val data = doc.data?.toMutableMap() ?: mutableMapOf()
                data["status"] = "Completed"
                data["endTime"] = Timestamp.now()
                db.collection("tripHistory").add(data).await()
                db.collection("activeTrips").document(busId).delete().await()
            }
            
            // Cleanup RTDB
            rtdb.child(busId).removeValue().await()

            if (driverDocId.isNotEmpty()) {
                db.collection("drivers").document(driverDocId).update("status", "Offline").await()
            }
            
            if (busId.isNotEmpty()) {
                db.collection("buses").document(busId).update(
                    "status", "Parked",
                    "passengerCount", 0,
                    "availableSeats", 30 // Assuming default capacity, could be fetched first
                ).await()
            }
        } catch (e: Exception) {
            Log.e("FirebaseTripManager", "Error ending trip", e)
        }
    }
}
