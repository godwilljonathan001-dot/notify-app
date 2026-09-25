package com.example.gusa

import android.Manifest
import android.animation.ValueAnimator
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.*
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityStudentBinding
import com.example.gusa.model.Stop
import com.example.gusa.service.MapRoutingService
import com.example.gusa.service.StudentTrackingService
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import android.view.animation.LinearInterpolator

class StudentActivity : FragmentActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityStudentBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var googleMapInstance: GoogleMap? = null
    
    private var isBoarded = false
    private var currentBusId = ""
    private var currentRouteId = ""
    private var currentPickupStopId = ""
    private var universityId = ""
    private var universityLatLng: LatLng? = null
    private var journeyDirection = "Towards University"
    
    private var busMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var routePolyline: Polyline? = null
    
    private var studentListener: ListenerRegistration? = null
    private var activeTripListener: ValueEventListener? = null
    private var busListener: ListenerRegistration? = null
    private var routeListener: ListenerRegistration? = null

    private var busMarkerAnimator: ValueAnimator? = null
    private var busRotationAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityStudentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        binding.studentMapView.apply { 
            onCreate(savedInstanceState)
            getMapAsync(this@StudentActivity) 
        }

        setupUI()
        startDataPipeline()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMapInstance = map.apply { 
            ThemeHelper.applyMapStyle(this@StudentActivity, this)
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = true
        }
        
        if (checkLocationPermission()) {
            map.isMyLocationEnabled = true
        } else {
            requestLocationPermission()
        }
    }

    private fun startDataPipeline() {
        val uid = auth.currentUser?.uid ?: return
        Log.d("StudentActivity", "Starting data pipeline for student: $uid")
        
        val serviceIntent = Intent(this, StudentTrackingService::class.java).apply {
            putExtra(StudentTrackingService.EXTRA_STUDENT_ID, uid)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        studentListener = db.collection("students").document(uid).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("StudentActivity", "Student listener error", e)
                return@addSnapshotListener
            }
            snap?.takeIf { it.exists() }?.let { doc ->
                processStudentData(doc)
            }
        }
    }

    private fun processStudentData(doc: DocumentSnapshot) {
        val busId = doc.getString("busId") ?: ""
        val routeId = doc.getString("routeId") ?: ""
        val stopId = doc.getString("pickupStation") ?: ""
        universityId = doc.getString("universityId") ?: ""
        isBoarded = doc.getString("status") == "boarded"

        Log.d("StudentActivity", "Data Update: Bus=$busId, Route=$routeId, Stop=$stopId, Status=${doc.getString("status")}")
        updateBoardingUI()

        if (busId.isNotEmpty() && busId != currentBusId) {
            currentBusId = busId
            observeActiveTrip(busId)
            observeBusBasicInfo(busId)
        }

        if (routeId.isNotEmpty() && routeId != currentRouteId) {
            currentRouteId = routeId
            observeRoute(routeId)
        }

        if (stopId.isNotEmpty() && stopId != currentPickupStopId) {
            currentPickupStopId = stopId
            updatePickupMarkerFromDb(stopId)
        }
        
        if (universityId.isNotEmpty() && universityLatLng == null) {
            fetchUniversity(universityId)
        }
    }

    private var busLocation: LatLng? = null
    private var currentPickupStopLatLng: LatLng? = null
    private var lastDistanceToStop = Float.MAX_VALUE
    private var isWasNear = false
    private var isArrivalDialogShown = false
    private var currentActiveTripId: String? = null
    private var orderedStops = listOf<Stop>()
    private var lastUpdateTimestamp = 0L

    private fun observeActiveTrip(busId: String) {
        val rtdb = FirebaseDatabase.getInstance().getReference("activeTrips").child(busId)
        
        activeTripListener?.let { 
            FirebaseDatabase.getInstance().getReference("activeTrips").child(currentBusId).removeEventListener(it) 
        }
        
        activeTripListener = rtdb.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) {
                    binding.tvStudentEta.text = "Bus Offline"
                    return
                }
                
                val lat = snap.child("latitude").getValue(Double::class.java)
                val lng = snap.child("longitude").getValue(Double::class.java)
                val heading = snap.child("heading").getValue(Float::class.java) ?: 0f
                val eta = snap.child("ETA").getValue(String::class.java) ?: "--"
                val dist = snap.child("remainingDistance").getValue(String::class.java) ?: "-- km"
                val direction = snap.child("direction").getValue(String::class.java) ?: journeyDirection
                val tripId = snap.child("tripId").getValue(String::class.java) ?: busId
                val ts = snap.child("lastUpdated").getValue(Long::class.java) ?: 0L
                
                if (currentActiveTripId != tripId) {
                    currentActiveTripId = tripId
                    isArrivalDialogShown = false
                    isWasNear = false
                }

                lastUpdateTimestamp = ts
                checkStaleData()

                if (direction != journeyDirection) {
                    journeyDirection = direction
                    currentRouteId.takeIf { it.isNotEmpty() }?.let { fetchRouteAndDraw(it) }
                }

                binding.tvStudentEta.text = "ETA: $eta"
                binding.tvDistanceMetric.text = dist
                
                if (lat != null && lng != null) {
                    busLocation = LatLng(lat, lng)
                    animateBus(LatLng(lat, lng), heading)
                    updateDynamicRoute()
                    updateProximityStatusUI(LatLng(lat, lng))
                    updateStopsRemainingUI(LatLng(lat, lng))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("StudentActivity", "RTDB Error: ${error.message}")
            }
        })
    }

    private fun updateStopsRemainingUI(busPos: LatLng) {
        if (orderedStops.isEmpty() || currentPickupStopId.isEmpty()) return
        
        val pickupIdx = orderedStops.indexOfFirst { it.stopId.toString() == currentPickupStopId }
        if (pickupIdx == -1) return

        var currentBusIdx = -1
        var minDist = Float.MAX_VALUE
        orderedStops.forEachIndexed { index, stop ->
            val d = calculateDistance(busPos, LatLng(stop.latitude, stop.longitude))
            if (d < minDist) {
                minDist = d
                currentBusIdx = index
            }
        }

        if (currentBusIdx != -1) {
            val remaining = if (currentBusIdx < pickupIdx) pickupIdx - currentBusIdx else 0
            binding.tvStopsRemaining.text = if (remaining > 0) "Stops remaining: $remaining" else "Bus at/past your stop"
            binding.tvStopsRemaining.isVisible = !isBoarded
        }
    }

    private fun checkStaleData() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTimestamp > 60000L) { // 1 minute stale
            binding.tvStudentEta.setTextColor(Color.RED)
            binding.tvStudentEta.text = "Stale Location (1m+)"
        } else {
            binding.tvStudentEta.setTextColor(Color.BLACK)
        }
    }

    private fun updateDynamicRoute() {
        val busLoc = busLocation ?: return
        if (orderedStops.isEmpty()) return

        lifecycleScope.launch(Dispatchers.Default) {
            val relevantPoints = mutableListOf<LatLng>()
            relevantPoints.add(busLoc)

            if (!isBoarded) {
                // To Pickup Stop
                val pickup = orderedStops.find { it.stopId.toString() == currentPickupStopId }
                if (pickup != null) {
                    // Find stops between current bus and pickup
                    // This is a simplified version; in a real app, we'd find the "closest" segment on the route
                    val pickupIdx = orderedStops.indexOf(pickup)
                    // For now, let's just draw from Bus to Pickup directly through road
                    withContext(Dispatchers.Main) {
                        drawRelevantRoadPath(busLoc, LatLng(pickup.latitude, pickup.longitude))
                    }
                }
            } else {
                // To University
                universityLatLng?.let { uni ->
                    withContext(Dispatchers.Main) {
                        drawRelevantRoadPath(busLoc, uni)
                    }
                }
            }
        }
    }

    private var dynamicRoutePolyline: Polyline? = null
    private suspend fun drawRelevantRoadPath(start: LatLng, end: LatLng) {
        val road = try {
            MapRoutingService.getDirectionPoints(this@StudentActivity, start, end)
        } catch (e: Exception) {
            listOf(start, end)
        }
        
        withContext(Dispatchers.Main) {
            dynamicRoutePolyline?.remove()
            dynamicRoutePolyline = googleMapInstance?.addPolyline(PolylineOptions()
                .color(Color.parseColor("#FFC107")) // Yellow for dynamic path
                .width(15f).zIndex(10f)
                .addAll(road))
        }
    }

    private fun observeBusBasicInfo(busId: String) {
        busListener?.remove()
        busListener = db.collection("buses").document(busId).addSnapshotListener { snap, _ ->
            snap?.takeIf { it.exists() }?.let { doc ->
                val busNum = doc.getString("busNumber") ?: busId
                val avail = doc.getLong("availableSeats") ?: 0
                val total = doc.getLong("totalSeats") ?: 30
                val passengers = doc.getLong("passengerCount") ?: 0
                
                binding.tvStudentBus.text = "Bus: $busNum"
                binding.tvStudentSeats.text = "Seats: $avail / $total Available"
                binding.pbOccupancyMetric.progress = ((passengers.toFloat() / total.toFloat()) * 100).toInt()
            }
        }
    }

    private fun observeRoute(routeId: String) {
        routeListener?.remove()
        routeListener = db.collection("routes").document(routeId).addSnapshotListener { snap, _ ->
            snap?.takeIf { it.exists() }?.let { doc ->
                binding.tvStudentRoute.text = "Route: ${doc.getString("routeName") ?: routeId}"
                drawRoutePolyline(doc)
            }
        }
    }

    private fun fetchRouteAndDraw(routeId: String) {
        db.collection("routes").document(routeId).get().addOnSuccessListener { doc ->
            if (doc.exists()) drawRoutePolyline(doc)
        }
    }

    private fun animateBus(newPos: LatLng, newHeading: Float) {
        if (googleMapInstance == null) return
        
        if (busMarker == null) {
            val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.bus_marker)
            val icon = BitmapDescriptorFactory.fromBitmap(Bitmap.createScaledBitmap(originalBitmap, 120, 120, false))
            busMarker = googleMapInstance?.addMarker(MarkerOptions()
                .position(newPos).rotation(newHeading).anchor(0.5f, 0.5f).flat(true).icon(icon))
            googleMapInstance?.animateCamera(CameraUpdateFactory.newLatLngZoom(newPos, 16f))
            return
        }

        busMarkerAnimator?.cancel()
        val startPos = busMarker!!.position
        busMarkerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedFraction
                val lat = v * newPos.latitude + (1 - v) * startPos.latitude
                val lng = v * newPos.longitude + (1 - v) * startPos.longitude
                busMarker?.position = LatLng(lat, lng)
            }
            start()
        }

        var startRot = busMarker!!.rotation
        var endRot = newHeading
        if (Math.abs(endRot - startRot) > 180) {
            if (endRot > startRot) startRot += 360f else endRot += 360f
        }
        busRotationAnimator?.cancel()
        busRotationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedFraction
                busMarker?.rotation = v * endRot + (1 - v) * startRot
            }
            start()
        }
    }

    private fun updatePickupMarkerFromDb(stopId: String) {
        db.collection("stops").document(stopId).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val lat = doc.getDouble("latitude")
                val lng = doc.getDouble("longitude")
                val name = doc.getString("name") ?: "Pickup"
                binding.tvStudentStation.text = "Pickup Station: $name"
                if (lat != null && lng != null) {
                    val pos = LatLng(lat, lng)
                    currentPickupStopLatLng = pos
                    pickupMarker?.remove()
                    pickupMarker = googleMapInstance?.addMarker(MarkerOptions()
                        .position(pos)
                        .title("YOUR STOP: $name")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        .zIndex(15f))
                }
            }
        }
    }

    private val routeManager = com.example.gusa.manager.RouteManager()

    private fun drawRoutePolyline(snap: DocumentSnapshot) {
        lifecycleScope.launch {
            try {
                orderedStops = routeManager.getOrderedStops(snap.id, universityId, journeyDirection)
                
                val points = orderedStops.map { LatLng(it.latitude, it.longitude) }.toMutableList()
                
                universityLatLng?.let { uni ->
                    if (journeyDirection != "Away from University") {
                        val last = points.lastOrNull()
                        if (last == null || calculateDistance(last, uni) > 100) points.add(uni)
                    } else {
                        val first = points.firstOrNull()
                        if (first == null || calculateDistance(first, uni) > 100) points.add(0, uni)
                    }
                }

                if (points.size < 2) {
                    Log.e("StudentActivity", "Insufficient points for route polyline")
                    return@launch
                }
                
                val road = try {
                    MapRoutingService.getDirectionPoints(this@StudentActivity, points.first(), points.last(), 
                        if (points.size > 2) points.subList(1, points.size - 1) else emptyList())
                } catch (e: Exception) {
                    Log.w("StudentActivity", "Directions API failed, using straight lines")
                    emptyList()
                }
                
                withContext(Dispatchers.Main) { 
                    routePolyline?.remove()
                    routePolyline = googleMapInstance?.addPolyline(PolylineOptions()
                        .color(Color.parseColor("#E0E0E0")).width(8f).geodesic(true) // Faded background route
                        .startCap(RoundCap()).endCap(RoundCap()).jointType(JointType.ROUND).zIndex(4f)
                        .addAll(if (road.isNotEmpty()) road else points)) 
                    
                    // Add markers for all stops
                    orderedStops.forEach { stop ->
                        val isPickup = stop.stopId.toString() == currentPickupStopId
                        if (!isPickup) {
                            googleMapInstance?.addMarker(MarkerOptions()
                                .position(LatLng(stop.latitude, stop.longitude))
                                .title(stop.name)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                                .alpha(0.4f)
                                .zIndex(5f))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StudentActivity", "Error drawing route", e)
            }
        }
    }

    private fun calculateDistance(p1: LatLng, p2: LatLng): Float {
        val r = FloatArray(1)
        Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, r)
        return r[0]
    }

    private fun fetchUniversity(id: String) {
        db.collection("universities").document(id).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val lat = doc.getDouble("latitude")
                val lng = doc.getDouble("longitude")
                if (lat != null && lng != null) {
                    universityLatLng = LatLng(lat, lng)
                    currentRouteId.takeIf { it.isNotEmpty() }?.let { fetchRouteAndDraw(it) }
                }
            }
        }
    }

    private fun updateBoardingUI() {
        if (isBoarded) {
            binding.tvBoardingStatus.apply {
                text = "Successfully Boarded!"
                setTextColor(ContextCompat.getColor(this@StudentActivity, R.color.status_green))
            }
        } else {
            // If not boarded, the text is handled by updateProximityStatusUI
            // which is called frequently via activeTripListener
            busLocation?.let { updateProximityStatusUI(it) } ?: run {
                binding.tvBoardingStatus.text = "Waiting for Bus..."
                binding.tvBoardingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            }
        }
        binding.btnBoardedAction.isVisible = !isBoarded
    }

    private fun updateProximityStatusUI(busPos: LatLng) {
        if (isBoarded) return

        val stopPos = currentPickupStopLatLng ?: return
        val distance = calculateDistance(busPos, stopPos)

        val statusText: String
        val statusColor: Int

        when {
            distance <= 100 -> {
                statusText = "Bus Arrived at Stop!"
                statusColor = R.color.status_green
                isWasNear = true
                if (!isArrivalDialogShown) {
                    isArrivalDialogShown = true
                    showArrivalBoardingDialog()
                }
            }
            distance <= 500 -> {
                statusText = "Bus Approaching..."
                statusColor = R.color.status_orange
                isWasNear = true
            }
            isWasNear && distance > lastDistanceToStop && distance > 300 -> {
                statusText = "Bus Passed Your Stop"
                statusColor = android.R.color.holo_red_dark
            }
            else -> {
                statusText = "Waiting for Bus..."
                statusColor = R.color.status_orange
            }
        }

        binding.tvBoardingStatus.text = statusText
        binding.tvBoardingStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        lastDistanceToStop = distance
    }

    private fun showArrivalBoardingDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Bus Arrived!")
            .setMessage("Your bus has arrived at the stop. Have you boarded the vehicle?")
            .setCancelable(false)
            .setPositiveButton("I Boarded") { _, _ -> execBoarding() }
            .setNegativeButton("Not Yet", null)
            .show()
    }

    private fun setupUI() {
        binding.btnBoardedAction.setOnClickListener { confirmBoarding() }
        binding.toolbar.setNavigationOnClickListener { 
            startActivity(Intent(this, SettingsActivity::class.java).apply { putExtra("ROLE", "student") }) 
        }
        binding.btnAutoDetectStop.setOnClickListener { autoDetectStop() }
        binding.btnViewRoute.setOnClickListener { zoomToFit() }
    }

    private fun confirmBoarding() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Boarding")
            .setMessage("Are you currently on the bus?")
            .setPositiveButton("Yes, I Boarded") { _, _ -> execBoarding() }
            .setNegativeButton("Not Yet", null)
            .show()
    }

    private fun execBoarding() {
        val uid = auth.currentUser?.uid ?: return
        if (currentBusId.isEmpty()) return

        lifecycleScope.launch {
            try {
                val result = db.runTransaction { tx ->
                    val busRef = db.collection("buses").document(currentBusId)
                    val busSnap = tx.get(busRef)
                    val avail = busSnap.getLong("availableSeats") ?: 30
                    val count = busSnap.getLong("passengerCount") ?: 0
                    
                    if (avail > 0) {
                        tx.update(busRef, "availableSeats", avail - 1)
                        tx.update(busRef, "passengerCount", count + 1)
                        tx.update(db.collection("students").document(uid), "status", "boarded")
                        "SUCCESS"
                    } else {
                        "FULL"
                    }
                }.await()
                
                if (result == "SUCCESS") {
                    stopService(Intent(this@StudentActivity, StudentTrackingService::class.java))
                    Toast.makeText(this@StudentActivity, "Boarding Confirmed!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@StudentActivity, "Boarding Rejected: Bus is Full", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("StudentActivity", "Boarding error", e)
                Toast.makeText(this@StudentActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun zoomToFit() {
        val bounds = LatLngBounds.builder()
        var any = false
        busMarker?.let { bounds.include(it.position); any = true }
        pickupMarker?.let { bounds.include(it.position); any = true }
        if (any) googleMapInstance?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 150))
    }

    private fun autoDetectStop() {
        if (!checkLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (currentRouteId.isEmpty()) {
            Toast.makeText(this, "No route assigned to detect stops", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                showStopSelectionDialog(loc)
            } else {
                // Fallback to manual selection if location is null
                showStopSelectionDialog(null)
            }
        }
    }

    private fun showStopSelectionDialog(userLoc: Location?) {
        lifecycleScope.launch {
            try {
                // 1. Fetch all stops for the current route
                val stops = db.collection("stops").whereEqualTo("routeId", currentRouteId).get().await()
                    .documents.mapNotNull { it.toObject(Stop::class.java) }
                    .sortedBy { it.name } // Sort alphabetically for selection ease

                if (stops.isEmpty()) {
                    Toast.makeText(this@StudentActivity, "No stops found for this route", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Identify nearest stop if user location is available
                var nearest: Stop? = null
                if (userLoc != null) {
                    var minD = Float.MAX_VALUE
                    stops.forEach { s ->
                        val d = userLoc.distanceTo(Location("").apply { latitude = s.latitude; longitude = s.longitude })
                        if (d < minD) { minD = d; nearest = s }
                    }
                }

                // 3. Prepare Dialog options
                val stopNames = stops.map { 
                    if (it.stopId == nearest?.stopId) "${it.name} (Suggested Nearest)" else it.name 
                }.toTypedArray()

                var selectedIdx = stops.indexOf(nearest).coerceAtLeast(0)

                MaterialAlertDialogBuilder(this@StudentActivity)
                    .setTitle("Select Pickup Stop")
                    .setSingleChoiceItems(stopNames, selectedIdx) { _, which ->
                        selectedIdx = which
                    }
                    .setPositiveButton("Confirm") { _, _ ->
                        val selectedStop = stops[selectedIdx]
                        updatePickupStop(selectedStop)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                Log.e("StudentActivity", "Error loading stops for selection", e)
                Toast.makeText(this@StudentActivity, "Failed to load stops", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePickupStop(stop: Stop) {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.collection("students").document(uid).update("pickupStation", stop.stopId.toString()).await()
                Toast.makeText(this@StudentActivity, "Pickup stop updated to: ${stop.name}", Toast.LENGTH_SHORT).show()
                
                // Refresh local UI
                currentPickupStopId = stop.stopId.toString()
                updatePickupMarkerFromDb(currentPickupStopId)
                
                // Restart service to monitor the new stop
                val serviceIntent = Intent(this@StudentActivity, StudentTrackingService::class.java).apply {
                    putExtra(StudentTrackingService.EXTRA_STUDENT_ID, uid)
                }
                ContextCompat.startForegroundService(this@StudentActivity, serviceIntent)
                
            } catch (e: Exception) {
                Log.e("StudentActivity", "Error updating pickup stop", e)
                Toast.makeText(this@StudentActivity, "Failed to update stop", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkLocationPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocationPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2002)

    override fun onResume() { super.onResume(); binding.studentMapView.onResume() }
    override fun onPause() { super.onPause(); binding.studentMapView.onPause() }
    override fun onDestroy() { 
        studentListener?.remove()
        activeTripListener?.let {
            FirebaseDatabase.getInstance().getReference("activeTrips").child(currentBusId).removeEventListener(it)
        }
        busListener?.remove()
        routeListener?.remove()
        binding.studentMapView.onDestroy()
        super.onDestroy() 
    }
}
