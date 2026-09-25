package com.example.gusa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityDriverBinding
import com.example.gusa.manager.NavigationManager
import com.example.gusa.manager.RouteManager
import com.example.gusa.model.Stop
import com.example.gusa.service.TrackingService
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.libraries.navigation.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DriverActivity : FragmentActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDriverBinding
    private val db = FirebaseFirestore.getInstance()
    
    private lateinit var navManager: NavigationManager
    private val routeManager = RouteManager()
    
    private var mMap: GoogleMap? = null
    private var isTripRunning = false
    
    private var driverId: String = ""
    private var driverDocId: String = ""
    private var busId: String = ""
    private var routeId: String = ""
    private var universityId: String = ""
    private var direction: String = "Towards University"
    
    private var orderedStops = listOf<Stop>()
    private var tripListener: ListenerRegistration? = null
    private var driverListener: ListenerRegistration? = null
    private var isNavReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navManager = NavigationManager(this)
        
        initIdentity()
        setupUI()
        setupBackNavigation()
        startStatusMonitoring()
        
        navManager.init { _ ->
            isNavReady = true
            Log.d("DriverActivity", "Navigation SDK Initialized")
            val fragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportNavigationFragment
            fragment.getMapAsync { googleMap ->
                mMap = googleMap
                onMapReady(googleMap)
            }
        }
    }

    private fun initIdentity() {
        val uid = intent.getStringExtra("USER_UID")
        driverId = intent.getStringExtra("DRIVER_ID") ?: ""
        
        lifecycleScope.launch {
            try {
                val doc = if (uid != null) {
                    db.collection("drivers").document(uid).get().await()
                } else {
                    db.collection("drivers").whereEqualTo("driverId", driverId).get().await().documents.firstOrNull()
                }

                doc?.takeIf { it.exists() }?.let {
                    driverDocId = it.id
                    driverId = it.getString("driverId") ?: driverDocId
                    busId = it.getString("busId") ?: ""
                    routeId = it.getString("routeId") ?: ""
                    universityId = it.getString("universityId") ?: ""
                    
                    Log.d("DriverActivity", "Driver Loaded: $driverId, Bus: $busId, Route: $routeId")
                    binding.tvDriverWelcome.text = "Welcome, ${it.getString("driverName")}"
                    observeDriverState()
                    loadRoute()
                } ?: run {
                    Log.e("DriverActivity", "Profile Error: No driver document found")
                    Toast.makeText(this@DriverActivity, "Profile Error", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("DriverActivity", "Identity Error", e)
                Toast.makeText(this@DriverActivity, "Identity Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeDriverState() {
        driverListener?.remove()
        driverListener = db.collection("drivers").document(driverDocId).addSnapshotListener { snapshot, _ ->
            snapshot?.getString("status")?.let { status ->
                isTripRunning = status == "Running"
                updateUIState()
            }
        }
    }

    private fun loadRoute() {
        lifecycleScope.launch {
            binding.tvDashboardStopsList.text = "Fetching stops..."
            orderedStops = routeManager.getOrderedStops(routeId, universityId, direction)
            
            Log.d("DriverActivity", "Route Loaded: $routeId. Stops count: ${orderedStops.size}")
            orderedStops.forEach { Log.d("DriverActivity", "Stop: ${it.name} (${it.latitude}, ${it.longitude})") }
            
            binding.tvDashboardRouteInfo.text = "Route: $routeId | $direction"
            binding.tvDashboardStopsList.text = if (orderedStops.isEmpty()) "No stops found" else orderedStops.joinToString("\n") { "• ${it.name}" }
        }
    }

    private fun setupUI() {
        binding.btnStartTrip.setOnClickListener { startTrip() }
        binding.btnStopTripDashboard.setOnClickListener { stopTrip() }
        binding.btnNavMenu.setOnClickListener { /* Navigation Menu */ }
        binding.mapContainer.setOnClickListener { 
            if (isTripRunning) enterFullScreenNavigation()
        }
        binding.btnDriverLogout.setOnClickListener { logout() }

        binding.tvDashboardRouteInfo.setOnClickListener {
            direction = if (direction == "Towards University") "Away from University" else "Towards University"
            loadRoute()
            Toast.makeText(this, "Direction changed to: $direction", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logout() {
        if (isTripRunning) {
            Toast.makeText(this, "End trip before logout", Toast.LENGTH_SHORT).show()
            return
        }
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply { 
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK 
        })
        finish()
    }

    private fun updateUIState() {
        binding.apply {
            layoutDashboard.isVisible = !isTripRunning
            cardHeader.isVisible = !isTripRunning
            layoutNavigation.isVisible = isTripRunning
            btnStartTrip.isEnabled = !isTripRunning
            btnStopTripDashboard.isVisible = isTripRunning
        }
        
        if (isTripRunning) {
            observeTripData()
        } else {
            tripListener?.remove()
        }
    }

    private var stopMarkers = mutableListOf<com.google.android.gms.maps.model.Marker>()

    private fun observeTripData() {
        tripListener?.remove()
        tripListener = db.collection("activeTrips").document(busId).addSnapshotListener { snapshot, _ ->
            snapshot?.let {
                val nextStopName = it.getString("nextStop") ?: "--"
                binding.tvNextStop.text = "NEXT: $nextStopName"
                binding.tvRemainingDistance.text = it.getString("remainingDistance") ?: "-- km"
                binding.tvEta.text = it.getString("estimatedArrival") ?: "--"
                binding.tvNavDistance.text = it.getString("remainingDistance") ?: "--"
                binding.tvNavStreet.text = nextStopName
                binding.tvCurrentSpeed.text = (it.getDouble("speed")?.let { s -> (s * 3.6).toInt() } ?: 0).toString()
                binding.tvPassengerCount.text = (it.getLong("passengerCount") ?: 0).toString()
                
                updateMapMarkers(nextStopName)
            }
        }
    }

    private fun updateMapMarkers(nextStopName: String) {
        if (mMap == null || orderedStops.isEmpty()) return
        
        // Remove old markers if any
        stopMarkers.forEach { it.remove() }
        stopMarkers.clear()

        orderedStops.forEach { stop ->
            val isNext = stop.name == nextStopName
            val icon = if (isNext) {
                com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_YELLOW)
            } else {
                com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE)
            }
            
            mMap?.addMarker(com.google.android.gms.maps.model.MarkerOptions()
                .position(com.google.android.gms.maps.model.LatLng(stop.latitude, stop.longitude))
                .title(stop.name)
                .icon(icon)
                .zIndex(if (isNext) 10f else 5f)
                .alpha(if (isNext) 1.0f else 0.6f)
            )?.let { stopMarkers.add(it) }
        }
    }

    private fun startTrip() {
        Log.d("DriverActivity", "Attempting to start trip...")
        
        // 1. Validation
        if (!checkLocationPermission()) {
            Log.e("DriverActivity", "Start Trip failed: GPS Permissions missing")
            requestLocationPermission()
            return
        }

        if (driverId.isEmpty() || driverDocId.isEmpty()) {
            Log.e("DriverActivity", "Start Trip failed: Driver identity missing")
            Toast.makeText(this, "Error: Driver identity missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (busId.isEmpty()) {
            Log.e("DriverActivity", "Start Trip failed: Bus assignment missing")
            Toast.makeText(this, "Error: Bus assignment missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (routeId.isEmpty()) {
            Log.e("DriverActivity", "Start Trip failed: Route ID missing")
            Toast.makeText(this, "Error: Route ID missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (orderedStops.isEmpty()) {
            Log.e("DriverActivity", "Start Trip failed: Stops not loaded")
            Toast.makeText(this, "Error: Stops not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNavReady) {
            Log.e("DriverActivity", "Start Trip failed: Navigator not initialized")
            Toast.makeText(this, "Error: Navigator not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (mMap == null) {
            Log.e("DriverActivity", "Start Trip failed: Map not ready")
            Toast.makeText(this, "Error: Map not ready", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                Log.d("DriverActivity", "Starting TrackingService...")
                val intent = Intent(this@DriverActivity, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_START
                    putExtra(TrackingService.EXTRA_DRIVER_ID, driverId)
                    putExtra(TrackingService.EXTRA_DRIVER_DOC_ID, driverDocId)
                    putExtra(TrackingService.EXTRA_BUS_ID, busId)
                    putExtra(TrackingService.EXTRA_ROUTE_ID, routeId)
                    putExtra(TrackingService.EXTRA_UNI_ID, universityId)
                    putExtra(TrackingService.EXTRA_DIRECTION, direction)
                }
                ContextCompat.startForegroundService(this@DriverActivity, intent)

                Log.d("DriverActivity", "Updating Firestore statuses...")
                db.collection("drivers").document(driverDocId).update("status", "Running").await()
                db.collection("buses").document(busId).update("status", "On Road").await()

                Log.d("DriverActivity", "Entering Full Screen Navigation UI")
                enterFullScreenNavigation()
                Toast.makeText(this@DriverActivity, "Trip Started Successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("DriverActivity", "Failed to start trip", e)
                Toast.makeText(this@DriverActivity, "Start Trip failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enterFullScreenNavigation() {
        if (orderedStops.isEmpty()) {
            Toast.makeText(this, "No stops found for this route", Toast.LENGTH_SHORT).show()
            return
        }
        binding.layoutDashboard.isVisible = false
        binding.cardHeader.isVisible = false
        binding.layoutNavigation.isVisible = true
        navManager.startNavigation(orderedStops, mMap) {
            Log.d("DriverActivity", "Navigator started successfully")
        }
    }

    private fun stopTrip() {
        Log.d("DriverActivity", "Stopping trip...")
        navManager.stopNavigation()
        val intent = Intent(this, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP }
        startService(intent)
        db.collection("drivers").document(driverDocId).update("status", "Offline")
        updateUIState()
        Toast.makeText(this, "Trip Ended", Toast.LENGTH_SHORT).show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTripRunning && !binding.layoutDashboard.isVisible) {
                    binding.layoutDashboard.isVisible = true
                    binding.cardHeader.isVisible = true
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun startStatusMonitoring() {
        lifecycleScope.launch {
            while (isActive) {
                updateGpsStatus()
                updateNetworkStatus()
                delay(5000)
            }
        }
    }

    private fun updateGpsStatus() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        binding.chipGpsStatus.apply {
            text = if (isEnabled) "GPS Active" else "GPS Disabled"
            setChipIconResource(if (isEnabled) android.R.drawable.presence_online else android.R.drawable.presence_offline)
            setTextColor(if (isEnabled) Color.BLACK else Color.RED)
        }
    }

    private fun updateNetworkStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        val isConnected = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )
        binding.chipNetworkStatus.apply {
            text = if (isConnected) "Online" else "Offline"
            setChipIconResource(if (isConnected) android.R.drawable.presence_online else android.R.drawable.presence_offline)
            setTextColor(if (isConnected) Color.BLACK else Color.RED)
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        ThemeHelper.applyMapStyle(this, googleMap)
        if (checkLocationPermission()) {
            googleMap.isMyLocationEnabled = true
        }
    }

    private fun checkLocationPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocationPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)

    override fun onDestroy() {
        tripListener?.remove()
        driverListener?.remove()
        super.onDestroy()
    }
}
