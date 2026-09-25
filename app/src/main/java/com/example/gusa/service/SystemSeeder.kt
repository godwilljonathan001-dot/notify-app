package com.example.gusa.service

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.tasks.await

object SystemSeeder {
    private const val TAG = "SystemSeeder"

    suspend fun seedAllData(): Boolean {
        val db = FirebaseFirestore.getInstance()

        try {
            Log.d(TAG, "Starting Database Seeding...")

            // 1. Universities
            val giuLat = 30.0051745
            val giuLng = 31.7007963
            val university = mapOf(
                "universityId" to "GIU",
                "name" to "German International University",
                "location" to "New Administrative Capital, Egypt",
                "latitude" to giuLat,
                "longitude" to giuLng,
                "geoPoint" to GeoPoint(giuLat, giuLng),
                "createdAt" to Timestamp.now()
            )
            db.collection("universities").document("GIU").set(university).await()

            // 2. Stops
            val stops = listOf(
                mapOf(
                    "stopId" to "GIU-S01",
                    "locationId" to "GIU-S01",
                    "name" to "Zahraa Nasr City Station",
                    "latitude" to 30.0513891,
                    "longitude" to 31.4024814,
                    "geoPoint" to GeoPoint(30.0513891, 31.4024814),
                    "type" to "pickup",
                    "routeId" to "GIU-R01",
                    "universityId" to "GIU",
                    "createdAt" to Timestamp.now()
                ),
                mapOf(
                    "stopId" to "GIU-S02",
                    "locationId" to "GIU-S02",
                    "name" to "El Salam Station",
                    "latitude" to 30.0467646,
                    "longitude" to 31.3561580,
                    "geoPoint" to GeoPoint(30.0467646, 31.3561580),
                    "type" to "pickup",
                    "routeId" to "GIU-R02",
                    "universityId" to "GIU",
                    "createdAt" to Timestamp.now()
                )
            )
            for (stop in stops) {
                db.collection("stops").document(stop["stopId"].toString()).set(stop).await()
            }

            // 3. Destinations
            val destinations = listOf(
                mapOf(
                    "destinationId" to "DEST-01",
                    "name" to "GIU Main Campus",
                    "latitude" to 30.0051745,
                    "longitude" to 31.7007963,
                    "geoPoint" to GeoPoint(30.0051745, 31.7007963),
                    "universityId" to "GIU",
                    "type" to "university",
                    "createdAt" to Timestamp.now()
                )
            )
            for (dest in destinations) {
                db.collection("destinations").document(dest["destinationId"].toString()).set(dest).await()
            }

            // 4. Routes
            val routes = listOf(
                mapOf(
                    "routeId" to "GIU-R01",
                    "routeName" to "GIU Inner Loop Route",
                    "universityId" to "GIU",
                    "stops" to listOf("GIU-S01", "GIU-S02"),
                    "createdAt" to Timestamp.now()
                ),
                mapOf(
                    "routeId" to "GIU-R02",
                    "routeName" to "El Salam - Zahra Route",
                    "universityId" to "GIU",
                    "stops" to listOf("GIU-S02", "GIU-S01"),
                    "createdAt" to Timestamp.now()
                )
            )
            for (route in routes) {
                db.collection("routes").document(route["routeId"].toString()).set(route).await()
            }

            // 5. Buses
            val buses = listOf(
                mapOf(
                    "busId" to "B01",
                    "busNumber" to "T 101 ABC",
                    "universityId" to "GIU",
                    "driverId" to "driver_juma_uid",
                    "totalSeats" to 30,
                    "availableSeats" to 30,
                    "passengerCount" to 0,
                    "status" to "Parked",
                    "createdAt" to Timestamp.now()
                ),
                mapOf(
                    "busId" to "B02",
                    "busNumber" to "T 202 XYZ",
                    "universityId" to "GIU",
                    "driverId" to "junior_maro_uid",
                    "totalSeats" to 30,
                    "availableSeats" to 30,
                    "passengerCount" to 0,
                    "status" to "Parked",
                    "createdAt" to Timestamp.now()
                )
            )
            for (bus in buses) {
                db.collection("buses").document(bus["busId"].toString()).set(bus).await()
            }

            // 6. Drivers
            val drivers = listOf(
                mapOf(
                    "driverUID" to "driver_juma_uid",
                    "driverId" to "D101",
                    "driverName" to "Juma Ally",
                    "email" to "driver@gusa.com",
                    "universityId" to "GIU",
                    "busId" to "B01",
                    "status" to "Offline",
                    "latitude" to 30.0051745,
                    "longitude" to 31.7007963,
                    "geoPoint" to GeoPoint(30.0051745, 31.7007963),
                    "createdAt" to Timestamp.now()
                ),
                mapOf(
                    "driverUID" to "junior_maro_uid",
                    "driverId" to "D102",
                    "driverName" to "Junior Maro",
                    "email" to "junior@gusa.com",
                    "universityId" to "GIU",
                    "busId" to "B02",
                    "status" to "Offline",
                    "latitude" to 30.0467646,
                    "longitude" to 31.3561580,
                    "geoPoint" to GeoPoint(30.0467646, 31.3561580),
                    "createdAt" to Timestamp.now()
                )
            )
            for (driver in drivers) {
                val email = driver["email"].toString()
                db.collection("drivers").document(email).set(driver).await()
            }

            // 7. Students
            val students = listOf(
                mapOf(
                    "studentUID" to "student_john_uid",
                    "studentId" to "290",
                    "studentName" to "John Bakunda",
                    "email" to "bakunda@gusa.com",
                    "universityId" to "GIU",
                    "routeId" to "GIU-R01",
                    "pickupStation" to "GIU-S01",
                    "busId" to "B01",
                    "status" to "waiting",
                    "latitude" to 30.0513891,
                    "longitude" to 31.4024814,
                    "geoPoint" to GeoPoint(30.0513891, 31.4024814),
                    "createdAt" to Timestamp.now()
                )
            )
            for (student in students) {
                val email = student["email"].toString()
                db.collection("students").document(email).set(student).await()
            }

            // 8. Admin
            val admin = mapOf(
                "adminId" to "admin_uid",
                "name" to "Notify Admin",
                "email" to "admin@gusa.com",
                "role" to "admin",
                "createdAt" to Timestamp.now()
            )
            db.collection("admins").document("admin_uid").set(admin).await()

            Log.d(TAG, "Geospatial Seeding Completed Successfully!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding data: ${e.message}", e)
            return false
        }
    }
}
