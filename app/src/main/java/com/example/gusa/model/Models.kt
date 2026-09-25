package com.example.gusa.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint

data class Admin(
    val adminId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "admin",
    val createdAt: Any? = null
)

data class University(
    val universityId: Any? = "",
    val name: String = "",
    val location: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val createdAt: Any? = null
)

data class Route(
    val routeId: Any? = "",
    val routeName: String = "",
    val universityId: Any? = "",
    val startingLocationId: Any? = "",
    val destinationId: Any? = "",
    val stops: List<Any?> = emptyList(), // list of stopIds or potentially GeoPoints
    val createdAt: Any? = null
)

data class StartingLocation(
    val startingLocationId: Any? = "",
    val name: String = "",
    val universityId: Any? = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val createdAt: Any? = null
)

data class Stop(
    val stopId: Any? = "",
    val locationId: Any? = "", // Same as stopId for legacy consistency
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val type: String = "stop", // stop, terminal, pickup, drop-off
    val routeId: Any? = "",
    val universityId: Any? = "",
    val createdAt: Any? = null
)

data class Destination(
    val destinationId: Any? = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val universityId: Any? = "",
    val type: String = "destination",
    val createdAt: Any? = null
)

data class Student(
    val studentUID: String = "",
    val studentId: Any? = "",
    val studentName: String = "",
    val email: String = "",
    val universityId: Any? = "",
    val routeId: Any? = "",
    val pickupStation: Any? = "", // stopId
    val busId: Any? = "",
    val status: String = "waiting", // waiting, boarded
    val isRegistered: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val createdAt: Any? = null
)

data class Driver(
    val driverUID: String = "",
    val driverId: Any? = "",
    val driverName: String = "",
    val email: String = "",
    val universityId: Any? = "",
    val routeId: Any? = "",
    val busId: Any? = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val geoPoint: GeoPoint? = null,
    val status: String = "Offline", // Waiting, Running, Paused, Completed, Offline
    val isRegistered: Boolean = false,
    val createdAt: Any? = null
)

data class Bus(
    val busId: Any? = "",
    val busNumber: String = "",
    val universityId: Any? = "",
    val driverId: Any? = "",
    val totalSeats: Int = 30,
    val availableSeats: Int = 30,
    val passengerCount: Int = 0,
    val status: String = "Parked", // On Road, Parked
    val createdAt: Any? = null
)

data class TripHistory(
    val tripId: String = "",
    val driverId: String = "",
    val busId: String = "",
    val routeId: String = "",
    val startTime: Any? = null,
    val endTime: Any? = null,
    val distance: Double = 0.0,
    val passengerCount: Int = 0,
    val status: String = "Completed",
    val createdAt: Any? = null
)

data class AuditLog(
    val logId: String = "",
    val action: String = "",
    val description: String = "",
    val adminId: String = "",
    val universityId: Any? = "",
    val createdAt: Any? = null
)

data class Notification(
    val notificationId: String = "",
    val title: String = "",
    val message: String = "",
    val recipientId: String = "",
    val senderId: String = "",
    val createdAt: Any? = null,
    val isLocalOnly: Boolean = false
)

data class Settings(
    val settingsId: String = "",
    val universityId: Any? = "",
    val allowAutoImport: Boolean = true,
    val isNotificationEnabled: Boolean = true
)

data class NavStep(
    val location: com.google.android.gms.maps.model.LatLng,
    val instruction: String,
    val maneuver: String,
    val distance: String
)
