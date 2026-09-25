package com.example.gusa.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gusa.R
import com.example.gusa.StudentActivity
import com.example.gusa.model.Stop
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * GUSA Student Tracking Service
 * 
 * Functions:
 * 1. Continuously updates student's own GPS location to Firestore.
 * 2. Listens to assigned bus location and triggers proximity alerts (500m, 100m, Arrived).
 * 3. Runs in foreground to ensure alerts work when screen is off.
 */
class StudentTrackingService : Service() {

    private val db = FirebaseFirestore.getInstance()
    private var studentListener: ListenerRegistration? = null
    private var tripListener: ValueEventListener? = null
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    private var studentId: String = ""
    private var currentBusId: String? = null
    private var pickupStop: Stop? = null
    private var orderedRouteStops = listOf<Stop>()
    private var currentRouteId: String? = null
    
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    
    private var alert500Triggered = false
    private var alert100Triggered = false
    private var alertArrivedTriggered = false
    private var alertPassedTriggered = false
    private var currentTripId: String? = null
    private var lastDistance = Float.MAX_VALUE
    private var wasClose = false

    companion object {
        const val CHANNEL_ID = "StudentTrackingChannel"
        const val NOTIFICATION_ID = 456
        const val ALERT_NOTIFICATION_ID = 457
        const val EXTRA_STUDENT_ID = "EXTRA_STUDENT_ID"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        createNotificationChannel()
        setupLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        studentId = intent?.getStringExtra(EXTRA_STUDENT_ID) ?: ""
        
        if (studentId.isNotEmpty()) {
            val statusNotification = createStatusNotification("Monitoring Bus", "Initializing student tracking...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, statusNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, statusNotification)
            }
            startLocationUpdates()
            observeStudent()
        } else {
            Log.e("StudentTrackingService", "Start failed: Student ID missing")
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateStudentLocationInFirestore(it) }
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.d("StudentTrackingService", "Student GPS updates started")
        } catch (e: SecurityException) {
            Log.e("StudentTrackingService", "GPS Permission missing", e)
        }
    }

    private fun updateStudentLocationInFirestore(loc: Location) {
        if (studentId.isEmpty()) return
        val updates = hashMapOf<String, Any>(
            "latitude" to loc.latitude,
            "longitude" to loc.longitude,
            "geoPoint" to GeoPoint(loc.latitude, loc.longitude),
            "lastUpdated" to com.google.firebase.Timestamp.now()
        )
        db.collection("students").document(studentId).update(updates)
            .addOnFailureListener { Log.e("StudentTrackingService", "Failed to update student location", it) }
    }

    private fun observeStudent() {
        studentListener?.remove()
        studentListener = db.collection("students").document(studentId).addSnapshotListener { snap, _ ->
            snap?.takeIf { it.exists() }?.let { doc ->
                val busId = doc.getString("busId") ?: ""
                val routeIdFromDoc = doc.getString("routeId") ?: ""
                val stopId = doc.getString("pickupStation") ?: ""
                val status = doc.getString("status") ?: "waiting"
                
                if (status == "boarded") {
                    Log.d("StudentTrackingService", "Student boarded. Stopping service.")
                    stopSelf()
                    return@addSnapshotListener
                }

                if (busId.isNotEmpty() && busId != currentBusId) {
                    currentBusId = busId
                    observeBusLocation(busId)
                }
                
                if (routeIdFromDoc.isNotEmpty() && routeIdFromDoc != currentRouteId) {
                    currentRouteId = routeIdFromDoc
                    fetchRouteStops(routeIdFromDoc)
                }

                if (stopId.isNotEmpty() && stopId != pickupStop?.stopId?.toString()) {
                    fetchPickupStop(stopId)
                }
            }
        }
    }

    private fun fetchRouteStops(routeId: String) {
        val routeManager = com.example.gusa.manager.RouteManager()
        val uniId = "" // We'll try to get this from student doc if needed, or assume generic
        // For the service, we mostly need the sequence. 
        // We'll use a coroutine to fetch
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // We need universityId for RouteManager to work perfectly as per current implementation
                // Let's get it from the student doc first
                val studentDoc = db.collection("students").document(studentId).get().await()
                val uId = studentDoc.getString("universityId") ?: ""
                val direction = "Towards University" // Default
                
                orderedRouteStops = routeManager.getOrderedStops(routeId, uId, direction)
                Log.d("StudentTrackingService", "Route stops loaded: ${orderedRouteStops.size}")
            } catch (e: Exception) {
                Log.e("StudentTrackingService", "Error fetching route stops", e)
            }
        }
    }

    private fun fetchPickupStop(stopId: String) {
        db.collection("stops").document(stopId).get().addOnSuccessListener { doc ->
            pickupStop = doc.toObject(Stop::class.java)
            pickupStop?.let {
                Log.d("StudentTrackingService", "Pickup stop loaded: ${it.name}")
                updateStatusNotification("Tracking Active", "Waiting for bus near ${it.name}")
            }
        }
    }

    private fun observeBusLocation(busId: String) {
        val rtdb = FirebaseDatabase.getInstance().getReference("activeTrips").child(busId)
        
        // Remove existing listener
        tripListener?.let { 
            FirebaseDatabase.getInstance().getReference("activeTrips").child(currentBusId ?: "").removeEventListener(it) 
        }

        tripListener = rtdb.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.exists()) {
                    val busLat = snap.child("latitude").getValue(Double::class.java)
                    val busLng = snap.child("longitude").getValue(Double::class.java)
                    val tripId = snap.child("busId").getValue(String::class.java) ?: busId
                    if (busLat != null && busLng != null) {
                        checkProximity(busLat, busLng, tripId)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("StudentTrackingService", "RTDB Error: ${error.message}")
            }
        })
    }

    private fun checkProximity(busLat: Double, busLng: Double, tripId: String) {
        if (currentTripId != tripId) {
            currentTripId = tripId
            alert500Triggered = false
            alert100Triggered = false
            alertArrivedTriggered = false
            alertPassedTriggered = false
            wasClose = false
            lastDistance = Float.MAX_VALUE
        }

        val stop = pickupStop ?: return
        val busLoc = Location("").apply { latitude = busLat; longitude = busLng }
        val stopLoc = Location("").apply { latitude = stop.latitude; longitude = stop.longitude }
        
        val distance = busLoc.distanceTo(stopLoc)
        
        // Stops Remaining logic
        val stopsRemaining = calculateStopsRemaining(busLoc)
        val statusMsg = if (stopsRemaining > 0) "$stopsRemaining stops remaining" else "Arriving"
        updateStatusNotification("Bus Monitoring", statusMsg)

        Log.d("StudentTrackingService", "Bus is ${distance.toInt()}m from ${stop.name}. Stops left: $stopsRemaining")

        when {
            distance <= 100 && !alertArrivedTriggered -> {
                triggerAlert("Bus Arrived", "Your bus has arrived at ${stop.name}!", true)
                alertArrivedTriggered = true
                alert100Triggered = true
                alert500Triggered = true
                wasClose = true
            }
            distance <= 200 -> {
                wasClose = true
            }
            distance <= 500 && !alert500Triggered -> {
                triggerAlert("Bus Approaching", "The bus is approximately 500m from your stop.", false)
                alert500Triggered = true
            }
        }

        // Enhanced Passed Detection using Stop Sequence
        if (!alertPassedTriggered && !alertArrivedTriggered) {
            val busPassedStopIdx = isBusPassedStopInSequence(busLoc)
            if (busPassedStopIdx) {
                triggerAlert("Bus Passed", "The bus has passed your stop.", true)
                alertPassedTriggered = true
            } else if (wasClose && distance > lastDistance && distance > 400) {
                // Fallback distance-based passed detection
                triggerAlert("Bus Passed", "The bus seems to have passed your stop.", true)
                alertPassedTriggered = true
            }
        }

        lastDistance = distance
    }

    private fun calculateStopsRemaining(busLoc: Location): Int {
        if (orderedRouteStops.isEmpty() || pickupStop == null) return -1
        
        val pickupIdx = orderedRouteStops.indexOfFirst { it.stopId.toString() == pickupStop?.stopId?.toString() }
        if (pickupIdx == -1) return -1

        // Find the current stop the bus is at or just passed
        var currentBusIdx = -1
        var minDist = Float.MAX_VALUE
        orderedRouteStops.forEachIndexed { index, stop ->
            val sLoc = Location("").apply { latitude = stop.latitude; longitude = stop.longitude }
            val d = busLoc.distanceTo(sLoc)
            if (d < minDist) {
                minDist = d
                currentBusIdx = index
            }
        }

        return if (currentBusIdx < pickupIdx) {
            pickupIdx - currentBusIdx
        } else {
            0
        }
    }

    private fun isBusPassedStopInSequence(busLoc: Location): Boolean {
        if (orderedRouteStops.isEmpty() || pickupStop == null) return false
        val pickupIdx = orderedRouteStops.indexOfFirst { it.stopId.toString() == pickupStop?.stopId?.toString() }
        if (pickupIdx == -1) return false

        // Check if the closest stop to the bus is AFTER the pickup stop
        var closestIdx = -1
        var minDist = Float.MAX_VALUE
        orderedRouteStops.forEachIndexed { index, stop ->
            val sLoc = Location("").apply { latitude = stop.latitude; longitude = stop.longitude }
            val d = busLoc.distanceTo(sLoc)
            if (d < minDist) {
                minDist = d
                closestIdx = index
            }
        }
        
        // If the bus is physically closer to a stop that comes after the student's stop, 
        // and it's far enough away from the student's stop (e.g. > 300m)
        val studentStopLoc = Location("").apply { latitude = pickupStop!!.latitude; longitude = pickupStop!!.longitude }
        return closestIdx > pickupIdx && busLoc.distanceTo(studentStopLoc) > 300
    }

    private fun triggerAlert(title: String, msg: String, playSound: Boolean) {
        Log.i("StudentTrackingService", "ALERT: $title - $msg")
        if (playSound) startAlarm()
        
        val intent = Intent(this, StudentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.bus_marker)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 500, 200, 500))

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun startAlarm() {
        if (mediaPlayer?.isPlaying == true) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@StudentTrackingService, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                prepare()
                start()
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.e("StudentTrackingService", "Alarm error", e)
        }
    }

    private fun createStatusNotification(title: String, content: String): Notification {
        val intent = Intent(this, StudentActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.bus_marker)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateStatusNotification(t: String, c: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createStatusNotification(t, c))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val chan = NotificationChannel(CHANNEL_ID, "GUSA Monitoring", NotificationManager.IMPORTANCE_HIGH)
            chan.enableVibration(true)
            chan.description = "Real-time bus proximity alerts"
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    override fun onDestroy() {
        Log.d("StudentTrackingService", "Service Destroyed")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        studentListener?.remove()
        tripListener?.let {
            FirebaseDatabase.getInstance().getReference("activeTrips").child(currentBusId ?: "").removeEventListener(it)
        }
        mediaPlayer?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
