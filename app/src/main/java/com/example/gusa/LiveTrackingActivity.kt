package com.example.gusa

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityLivetrackingBinding
import com.example.gusa.model.NavStep
import com.example.gusa.model.Stop
import com.example.gusa.service.GeminiService
import com.example.gusa.service.MapRoutingService
import com.example.gusa.service.TrackingService
import com.example.gusa.util.LocationFilter
import com.example.gusa.util.NavigationUtils
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.SupportNavigationFragment
import com.google.android.libraries.navigation.Waypoint
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class LiveTrackingActivity : FragmentActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityLivetrackingBinding
    private var mMap: GoogleMap? = null
    private val db = FirebaseFirestore.getInstance()
    private val busMarkers = mutableMapOf<String, Marker>()
    private val busStatuses = mutableMapOf<String, String>()
    
    // Animation Tracking
    private val markerAnimators = mutableMapOf<String, ValueAnimator>()
    private val rotationAnimators = mutableMapOf<String, ValueAnimator>()

    // Fleet Management State
    private var selectedBusId: String? = null
    private var cameraMode = "overview" // overview, follow, free
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    
    // Static Overlays Cache
    private val routePolylines = mutableListOf<Polyline>()
    private val stopMarkers = mutableListOf<Marker>()
    private val universityMarkers = mutableListOf<Marker>()

    private val rtdb = FirebaseDatabase.getInstance().getReference("activeTrips")
    private var rtdbListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityLivetrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetBus)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        setupSearchAndFilters()
        setupFloatingButtons()
        
        // Start periodic AI insights analysis
        startAiInsightsEngine()
    }

    private fun setupSearchAndFilters() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchFleet(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipRunning -> "Running"
                R.id.chipParked -> "Offline" // Mapped from logic
                else -> "All"
            }
            applyFleetFilter(filter)
        }
    }

    private fun setupFloatingButtons() {
        binding.btnMapType.setOnClickListener {
            val types = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
            val current = mMap?.mapType ?: GoogleMap.MAP_TYPE_NORMAL
            val nextType = (current % 4) + 1
            mMap?.mapType = nextType
            showSnackbar("Map Type changed")
        }

        binding.btnTraffic.setOnClickListener {
            val enabled = !(mMap?.isTrafficEnabled ?: false)
            mMap?.isTrafficEnabled = enabled
            showSnackbar("Traffic ${if (enabled) "Enabled" else "Disabled"}")
        }

        binding.btnLocateFleet.setOnClickListener {
            cameraMode = "overview"
            adjustCameraToFleet()
            binding.btnRecenter.visibility = View.GONE
        }

        binding.btnRecenter.setOnClickListener {
            cameraMode = "overview"
            adjustCameraToFleet()
            binding.btnRecenter.visibility = View.GONE
        }

        binding.btnFollowBus.setOnClickListener {
            if (selectedBusId != null) {
                cameraMode = "follow"
                showSnackbar("Engaging Follow Mode")
                binding.btnFollowBus.text = "Follow Mode Active"
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        ThemeHelper.applyMapStyle(this, googleMap)
        
        mMap?.apply {
            isTrafficEnabled = true
            isBuildingsEnabled = true
            isIndoorEnabled = true
            uiSettings.isCompassEnabled = true
            uiSettings.isRotateGesturesEnabled = true
            uiSettings.isTiltGesturesEnabled = true
            
            // Re-center button trigger
            setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    if (cameraMode != "free") {
                        cameraMode = "free"
                        binding.btnRecenter.visibility = View.VISIBLE
                    }
                }
            }

            setOnMarkerClickListener { marker ->
                val busId = busMarkers.entries.find { it.value == marker }?.key
                if (busId != null) {
                    selectBus(busId)
                }
                true
            }

            setOnMapClickListener {
                deselectBus()
            }
        }

        // Load static assets (Routes & Stops)
        loadFleetOverlays()
        loadUniversityMarkers()
        
        // Connect to Live Data Stream
        setupLiveBusTracking()
    }

    private fun loadFleetOverlays() {
        lifecycleScope.launch {
            try {
                val routes = db.collection("routes").get().await()
                val colors = arrayOf("#4285F4", "#34A853", "#FBBC05", "#EA4335", "#673AB7")
                
                routes.documents.forEachIndexed { index, doc ->
                    val stops = doc.get("stops") as? List<*>
                    if (stops != null) {
                        val stopIds = stops.mapNotNull { it?.toString() }
                        if (stopIds.isNotEmpty()) {
                            drawRoutePolyline(stopIds, Color.parseColor(colors[index % colors.size]))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FleetCenter", "Error loading overlays", e)
            }
        }
    }

    private fun drawRoutePolyline(stopIds: List<String>, color: Int) {
        lifecycleScope.launch {
            val stopPoints = mutableListOf<LatLng>()
            for (id in stopIds) {
                if (id.isEmpty()) continue
                try {
                    val stopDoc = db.collection("stops").document(id).get().await()
                    if (stopDoc.exists()) {
                        val lat = stopDoc.getDouble("latitude")
                        val lng = stopDoc.getDouble("longitude")
                        if (lat != null && lng != null) {
                            val pos = LatLng(lat, lng)
                            stopPoints.add(pos)
                            withContext(Dispatchers.Main) {
                                addStopMarker(pos, stopDoc.getString("name") ?: "Stop")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FleetCenter", "Error loading stop $id", e)
                }
            }

            if (stopPoints.size < 2) return@launch

            val origin = stopPoints.first()
            val dest = stopPoints.last()
            val waypoints = if (stopPoints.size > 2) stopPoints.subList(1, stopPoints.size - 1) else emptyList()

            try {
                val roadPoints = MapRoutingService.getDirectionPoints(this@LiveTrackingActivity, origin, dest, waypoints)
                withContext(Dispatchers.Main) {
                    val polyOptions = PolylineOptions()
                        .color(color)
                        .width(10f)
                        .jointType(JointType.ROUND)
                        .startCap(RoundCap())
                        .endCap(RoundCap())

                    if (roadPoints.isNotEmpty()) {
                        polyOptions.addAll(roadPoints)
                    } else {
                        polyOptions.addAll(stopPoints)
                    }

                    val polyline = mMap?.addPolyline(polyOptions)
                    if (polyline != null) routePolylines.add(polyline)
                }
            } catch (e: Exception) {
                Log.e("FleetCenter", "Routing error", e)
                withContext(Dispatchers.Main) {
                    val polyline = mMap?.addPolyline(PolylineOptions().addAll(stopPoints).color(color).width(10f))
                    if (polyline != null) routePolylines.add(polyline)
                }
            }
        }
    }

    private fun addStopMarker(pos: LatLng, name: String) {
        val marker = mMap?.addMarker(MarkerOptions()
            .position(pos)
            .title(name)
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            .anchor(0.5f, 0.5f))
        if (marker != null) stopMarkers.add(marker)
    }

    private fun loadUniversityMarkers() {
        lifecycleScope.launch {
            try {
                val unis = db.collection("universities").get().await()
                unis.documents.forEach { doc ->
                    val name = doc.getString("name") ?: "University"
                    val lat = doc.getDouble("latitude") ?: 30.0444
                    val lng = doc.getDouble("longitude") ?: 31.2357
                    
                    withContext(Dispatchers.Main) {
                        val marker = mMap?.addMarker(MarkerOptions()
                            .position(LatLng(lat, lng))
                            .title(name)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)))
                        if (marker != null) universityMarkers.add(marker)
                    }
                }
            } catch (e: Exception) {
                Log.e("FleetCenter", "Error loading universities", e)
            }
        }
    }

    private fun setupLiveBusTracking() {
        // We still use Firestore for aggregate stats and overall fleet list
        db.collection("drivers")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("FleetCenter", "Firestore Stream error", e)
                    binding.tvSyncTime.text = "Sync Error"
                    binding.indicatorConnection.setBackgroundResource(android.R.drawable.presence_offline)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    snapshotsCount = snapshots.size()
                    binding.tvSyncTime.text = "Sync: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
                    binding.indicatorConnection.setBackgroundResource(android.R.drawable.presence_online)
                    
                    val running = snapshots.documents.count { it.getString("status") == "Running" }
                    val parked = snapshots.size() - running
                    val totalPassengers = snapshots.documents.sumOf { it.getLong("passengerCount") ?: 0 }

                    binding.tvActiveBuses.text = running.toString()
                    binding.tvParkedBuses.text = parked.toString()
                    binding.tvTotalPassengers.text = totalPassengers.toString()
                }
            }

        // Live location from RTDB
        rtdbListener = rtdb.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentBusIds = mutableSetOf<String>()
                for (busSnap in snapshot.children) {
                    try {
                        val busId = busSnap.key ?: continue
                        currentBusIds.add(busId)
                        
                        val lat = busSnap.child("latitude").getValue(Double::class.java) ?: continue
                        val lng = busSnap.child("longitude").getValue(Double::class.java) ?: continue
                        val heading = busSnap.child("heading").getValue(Float::class.java) ?: 0f
                        val status = busSnap.child("status").getValue(String::class.java) ?: "Running"
                        
                        // We need the driver name and passenger count from Firestore or locally cached if we want to show it
                        // For live marker movement, RTDB is enough.
                        val position = LatLng(lat, lng)
                        updateOrAddBusMarker(busId, position, heading, "Driver", busId, 0, status)
                        
                        if (selectedBusId == busId) {
                            val speed = busSnap.child("speed").getValue(Float::class.java) ?: 0f
                            updateSelectionPanel("Active Driver", busId, 0, status, speed)
                        }
                    } catch (ex: Exception) {
                        Log.e("FleetCenter", "Error processing RTDB bus data", ex)
                    }
                }

                // Cleanup inactive markers
                val toRemove = busMarkers.keys.subtract(currentBusIds)
                for (id in toRemove) {
                    busMarkers[id]?.remove()
                    busMarkers.remove(id)
                    busStatuses.remove(id)
                }
                
                if (cameraMode == "overview") {
                    adjustCameraToFleet()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FleetCenter", "RTDB Error: ${error.message}")
            }
        })
    }

    private fun updateOrAddBusMarker(id: String, pos: LatLng, bearing: Float, name: String, busId: String, passengers: Long, status: String) {
        busStatuses[id] = status
        val marker = busMarkers[id]
        if (marker == null) {
            val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.bus_marker)
            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, 120, 120, false)
            val icon = BitmapDescriptorFactory.fromBitmap(resizedBitmap)

            val newMarker = mMap?.addMarker(MarkerOptions()
                .position(pos)
                .rotation(bearing)
                .title("Bus $busId")
                .snippet("Driver: $name")
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .flat(true))
            if (newMarker != null) busMarkers[id] = newMarker
        } else {
            animateMarker(id, marker, pos, bearing)
        }

        // Live camera follow
        if (cameraMode == "follow" && selectedBusId == id) {
            val cameraPosition = CameraPosition.Builder()
                .target(pos)
                .zoom(17f)
                .tilt(45f)
                .bearing(bearing)
                .build()
            mMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null)
        }
    }

    private fun animateMarker(id: String, marker: Marker, destination: LatLng, bearing: Float) {
        val startPos = marker.position
        markerAnimators[id]?.cancel()
        val posAnimator = ValueAnimator.ofFloat(0f, 1f)
        posAnimator.duration = 1000
        posAnimator.interpolator = LinearInterpolator()
        posAnimator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            val lat = (destination.latitude - startPos.latitude) * fraction + startPos.latitude
            val lng = (destination.longitude - startPos.longitude) * fraction + startPos.longitude
            marker.position = LatLng(lat, lng)
        }
        posAnimator.start()
        markerAnimators[id] = posAnimator

        var startRot = marker.rotation
        var endRot = bearing
        
        if (Math.abs(endRot - startRot) > 180) {
            if (endRot > startRot) startRot += 360f else endRot += 360f
        }

        rotationAnimators[id]?.cancel()
        val rotAnimator = ValueAnimator.ofFloat(0f, 1f)
        rotAnimator.duration = 800
        rotAnimator.interpolator = LinearInterpolator()
        rotAnimator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            marker.rotation = (endRot - startRot) * fraction + startRot
        }
        rotAnimator.start()
        rotationAnimators[id] = rotAnimator
    }

    private fun selectBus(id: String) {
        selectedBusId = id
        val marker = busMarkers[id] ?: return
        
        binding.tvSheetBusNum.text = marker.title
        binding.tvSheetDriver.text = marker.snippet
        
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        
        if (cameraMode != "follow") {
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 16f))
        }
    }

    private fun deselectBus() {
        selectedBusId = null
        cameraMode = "overview"
        binding.btnFollowBus.text = "Engage Follow Mode"
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun updateSelectionPanel(name: String, bus: String, passengers: Long, status: String, speed: Float) {
        binding.tvSheetBusNum.text = "Bus #$bus"
        binding.tvSheetDriver.text = "Driver: $name"
        binding.tvSheetLoad.text = "$passengers / 30"
        binding.tvSheetSpeed.text = "${speed.toInt()} km/h"
        binding.tvSheetUpdate.text = "Updated ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        
        binding.chipSheetStatus.text = status
        when (status) {
            "Running" -> binding.chipSheetStatus.setChipBackgroundColorResource(com.google.android.material.R.color.material_dynamic_primary40)
            else -> binding.chipSheetStatus.setChipBackgroundColorResource(com.google.android.material.R.color.material_dynamic_neutral90)
        }
    }

    private fun adjustCameraToFleet() {
        if (busMarkers.isEmpty()) return
        val builder = LatLngBounds.Builder()
        busMarkers.values.forEach { builder.include(it.position) }
        try {
            val bounds = builder.build()
            mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
        } catch (e: Exception) {}
    }

    private fun searchFleet(query: String) {
        val match = busMarkers.entries.find { 
            it.value.title?.contains(query, true) == true || 
            it.value.snippet?.contains(query, true) == true 
        }
        match?.let { selectBus(it.key) }
    }

    private fun applyFleetFilter(filter: String) {
        busMarkers.forEach { (id, marker) ->
            val status = busStatuses[id] ?: "Offline"
            marker.isVisible = when (filter) {
                "All" -> true
                "Running" -> status == "Running"
                "Offline" -> status != "Running"
                else -> true
            }
        }
    }

    private fun startAiInsightsEngine() {
        lifecycleScope.launch {
            while (true) {
                try {
                    val activeBuses = snapshotsCount ?: busMarkers.size
                    val totalPassengers = binding.tvTotalPassengers.text.toString()
                    val stats = mapOf(
                        "active" to activeBuses,
                        "passengers" to totalPassengers
                    )
                    val insights = GeminiService.getAiInsights(stats)
                    withContext(Dispatchers.Main) {
                        binding.cardAiInsights.visibility = View.VISIBLE
                        binding.tvAiFleetInsights.text = insights
                    }
                } catch (e: Exception) {
                    Log.e("FleetCenter", "AI Insight engine error", e)
                }
                delay(60000)
            }
        }
    }
    
    private var snapshotsCount = 0

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }
}
