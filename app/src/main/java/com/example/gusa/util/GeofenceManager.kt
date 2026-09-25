package com.example.gusa.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.gusa.model.Stop
import com.example.gusa.service.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceManager(private val context: Context) {

    private val geofencingClient = LocationServices.getGeofencingClient(context)
    
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /**
     * Driver Side: Add geofences for every stop in the route to track progress.
     */
    @SuppressLint("MissingPermission")
    fun addRouteStops(stops: List<Stop>, busId: String) {
        if (stops.isEmpty()) return

        val geofences = stops.map { stop ->
            Geofence.Builder()
                .setRequestId("DRIVER_STOP:${stop.stopId}:${busId}:${stop.name}")
                .setCircularRegion(stop.latitude, stop.longitude, 50f) // 50m for arrival
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent).addOnFailureListener {
            Log.e("GeofenceManager", "Failed to add route geofences: ${it.message}")
        }
    }

    fun removeAll() {
        geofencingClient.removeGeofences(geofencePendingIntent)
    }
}
