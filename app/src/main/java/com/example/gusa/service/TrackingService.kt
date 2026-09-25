package com.example.gusa.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gusa.DriverActivity
import com.example.gusa.R
import com.example.gusa.manager.FirebaseTripManager
import com.example.gusa.manager.RouteManager
import com.example.gusa.model.Stop
import com.example.gusa.util.GeofenceManager
import com.example.gusa.util.LocationFilter
import com.example.gusa.util.NavigationUtils
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * GUSA Driver Tracking Service
 * 
 * Source of truth for:
 * 1. Live GPS updates to Firestore (activeTrips and drivers collections).
 * 2. Stop arrival detection (Geofencing).
 * 3. Foreground monitoring to ensure tracking persists.
 */
class TrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val routeManager = RouteManager()
    private val tripManager = FirebaseTripManager()
    private lateinit var geofenceManager: GeofenceManager
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val locationFilter = LocationFilter(minDistance = 2.0f)

    private var driverId: String = ""
    private var driverDocId: String = ""
    private var busId: String = ""
    private var routeId: String = ""
    private var universityId: String = ""
    private var direction: String = ""
    
    private var orderedStops = mutableListOf<Stop>()
    private var roadPoints = mutableListOf<LatLng>()

    companion object {
        const val CHANNEL_ID = "TrackingChannel"
        const val NOTIFICATION_ID = 123
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        const val EXTRA_DRIVER_ID = "EXTRA_DRIVER_ID"
        const val EXTRA_DRIVER_DOC_ID = "EXTRA_DRIVER_DOC_ID"
        const val EXTRA_BUS_ID = "EXTRA_BUS_ID"
        const val EXTRA_ROUTE_ID = "EXTRA_ROUTE_ID"
        const val EXTRA_UNI_ID = "EXTRA_UNI_ID"
        const val EXTRA_DIRECTION = "EXTRA_DIRECTION"
    }

    override fun onCreate() {
        super.onCreate()
        geofenceManager = GeofenceManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking(intent: Intent?) {
        if (intent == null) return
        driverId = intent.getStringExtra(EXTRA_DRIVER_ID) ?: ""
        driverDocId = intent.getStringExtra(EXTRA_DRIVER_DOC_ID) ?: ""
        busId = intent.getStringExtra(EXTRA_BUS_ID) ?: ""
        routeId = intent.getStringExtra(EXTRA_ROUTE_ID) ?: ""
        universityId = intent.getStringExtra(EXTRA_UNI_ID) ?: ""
        direction = intent.getStringExtra(EXTRA_DIRECTION) ?: ""

        Log.i("TrackingService", "Starting tracking for Bus: $busId, Driver: $driverId")

        val notification = createNotification("Trip Starting...", "Initializing route...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                orderedStops = routeManager.getOrderedStops(routeId, universityId, direction).toMutableList()
                Log.d("TrackingService", "Loaded ${orderedStops.size} stops for tracking")

                val startLoc = try {
                    fusedLocationClient.lastLocation.await()
                } catch (e: SecurityException) {
                    null
                } ?: Location("").apply { 
                    latitude = orderedStops.firstOrNull()?.latitude ?: 0.0
                    longitude = orderedStops.firstOrNull()?.longitude ?: 0.0
                }
                
                // Initialize Trip document in Firestore
                tripManager.startTrip(busId, driverId, routeId, direction, startLoc, orderedStops)
                Log.d("TrackingService", "Firestore Trip document created/updated")

                // Add Geofences for stops
                geofenceManager.addRouteStops(orderedStops, busId)
                
                // Fetch road points for map snapping
                fetchRoadPoints()

                withContext(Dispatchers.Main) {
                    startLocationUpdates()
                }
            } catch (e: Exception) {
                Log.e("TrackingService", "Error during startTracking", e)
            }
        }
    }

    private suspend fun fetchRoadPoints() {
        if (orderedStops.size < 2) return
        val origin = LatLng(orderedStops.first().latitude, orderedStops.first().longitude)
        val dest = LatLng(orderedStops.last().latitude, orderedStops.last().longitude)
        val wps = if (orderedStops.size > 2) orderedStops.subList(1, orderedStops.size - 1).map { LatLng(it.latitude, it.longitude) } else emptyList()
        
        try {
            roadPoints = MapRoutingService.getDirectionPoints(this, origin, dest, wps, optimize = false).toMutableList()
            Log.d("TrackingService", "Fetched ${roadPoints.size} road points for snapping")
        } catch (e: Exception) {
            Log.w("TrackingService", "Failed to fetch road points for snapping")
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let { processLocation(it) }
            }
        }
    }

    private fun processLocation(rawLocation: Location) {
        // 1. Filter Noise
        val filtered = locationFilter.process(rawLocation) ?: return
        
        // 2. We keep the raw GPS coordinate for Firebase as per Requirement #11
        // Visualization snapping will happen on the UI if needed
        
        // 3. Update Firestore
        val nextStop = findNextStop(filtered)
        val distToNext = nextStop?.let { 
            val stopLoc = Location("").apply { latitude = it.latitude; longitude = it.longitude }
            filtered.distanceTo(stopLoc)
        } ?: 0f

        Log.v("TrackingService", "Updating live location: (${filtered.latitude}, ${filtered.longitude}) Next: ${nextStop?.name}")
        tripManager.updateLiveLocation(busId, driverDocId, filtered, nextStop, distToNext)
        
        updateNotification(nextStop?.name ?: "End of Route", distToNext)
    }

    private var nextStopIndex = 0

    private fun findNextStop(loc: Location): Stop? {
        if (orderedStops.isEmpty()) return null
        
        // 1. Initial Index Calculation (if just started or reset)
        if (nextStopIndex == 0) {
            var minDistance = Float.MAX_VALUE
            var closestIdx = 0
            orderedStops.forEachIndexed { index, stop ->
                val stopLoc = Location("").apply { latitude = stop.latitude; longitude = stop.longitude }
                val dist = loc.distanceTo(stopLoc)
                if (dist < minDistance) {
                    minDistance = dist
                    closestIdx = index
                }
            }
            nextStopIndex = closestIdx
        }

        // 2. Advance sequence if we get very close to the current target
        val currentTarget = orderedStops.getOrNull(nextStopIndex)
        if (currentTarget != null) {
            val stopLoc = Location("").apply { latitude = currentTarget.latitude; longitude = currentTarget.longitude }
            val dist = loc.distanceTo(stopLoc)
            
            // If within 80m, we consider this stop "reached" or "arrived", so we target the next one
            if (dist < 80) {
                if (nextStopIndex < orderedStops.size - 1) {
                    nextStopIndex++
                    Log.i("TrackingService", "Advanced to next stop index: $nextStopIndex (${orderedStops[nextStopIndex].name})")
                }
            }
        }
        
        return orderedStops.getOrNull(nextStopIndex)
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1500)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.i("TrackingService", "Driver GPS tracking active")
        } catch (e: SecurityException) {
            Log.e("TrackingService", "Permission error", e)
        }
    }

    private fun stopTracking() {
        Log.i("TrackingService", "Stopping tracking service")
        geofenceManager.removeAll()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.launch {
            try {
                tripManager.endTrip(busId, driverDocId)
                Log.d("TrackingService", "Trip ended successfully in Firestore")
            } catch (e: Exception) {
                Log.e("TrackingService", "Error ending trip", e)
            } finally {
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "GUSA Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, DriverActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.bus_marker)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(nextStop: String, dist: Float) {
        val content = "Next: $nextStop | ${"%.1f".format(dist / 1000)} km"
        val notification = createNotification("GUSA Trip Active", content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("TrackingService", "Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
