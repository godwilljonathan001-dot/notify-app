package com.example.gusa.manager

import android.util.Log
import com.example.gusa.model.Stop
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class RouteManager {
    private val db = FirebaseFirestore.getInstance()

    suspend fun getOrderedStops(routeId: String, universityId: String, direction: String): List<Stop> {
        return try {
            val routeDoc = db.collection("routes").document(routeId).get().await()
            if (!routeDoc.exists()) {
                Log.e("RouteManager", "Route document $routeId not found")
                return emptyList()
            }

            val stopIds = (routeDoc.get("stops") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            Log.d("RouteManager", "Route $routeId stop IDs from doc: $stopIds")

            val stopQuery = db.collection("stops").whereEqualTo("routeId", routeId).get().await()
            val allStops = stopQuery.documents.mapNotNull { doc ->
                val stop = doc.toObject(Stop::class.java)
                Log.d("RouteManager", "Fetched stop from DB: id=${stop?.stopId} name=${stop?.name}")
                stop
            }
            
            val orderedStops = mutableListOf<Stop>()
            for (id in stopIds) {
                val targetId = id.trim()
                val found = allStops.find { it.stopId.toString().trim() == targetId }
                if (found != null) {
                    orderedStops.add(found)
                    Log.d("RouteManager", "Matched stop: $targetId -> ${found.name}")
                } else {
                    Log.w("RouteManager", "No stop document found in 'stops' collection matching ID: $targetId")
                }
            }

            if (orderedStops.isEmpty() && stopIds.isNotEmpty()) {
                Log.e("RouteManager", "Ordering failed. IDs existed but no matches found.")
            }

            if (direction == "Away from University") {
                val uniDoc = db.collection("universities").document(universityId).get().await()
                if (uniDoc.exists()) {
                    val lat = uniDoc.getDouble("latitude")
                    val lng = uniDoc.getDouble("longitude")
                    if (lat != null && lng != null) {
                        orderedStops.add(0, Stop(
                            stopId = "UNI_START",
                            locationId = "UNI_START",
                            name = "University (Start)",
                            latitude = lat,
                            longitude = lng,
                            type = "terminal",
                            universityId = universityId
                        ))
                    }
                }
                orderedStops.reverse()
            } else {
                val uniDoc = db.collection("universities").document(universityId).get().await()
                if (uniDoc.exists()) {
                    val lat = uniDoc.getDouble("latitude")
                    val lng = uniDoc.getDouble("longitude")
                    if (lat != null && lng != null) {
                        val last = orderedStops.lastOrNull()
                        if (last == null || calculateDistance(last.latitude, last.longitude, lat, lng) > 100) {
                            orderedStops.add(Stop(
                                stopId = "UNI_DEST",
                                locationId = "UNI_DEST",
                                name = "University Campus",
                                latitude = lat,
                                longitude = lng,
                                type = "terminal",
                                universityId = universityId
                            ))
                        }
                    }
                }
            }
            orderedStops
        } catch (e: Exception) {
            Log.e("RouteManager", "Error fetching ordered stops", e)
            emptyList()
        }
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }
}
