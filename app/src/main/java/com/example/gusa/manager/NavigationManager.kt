package com.example.gusa.manager

import android.annotation.SuppressLint
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.gusa.model.Stop
import com.google.android.libraries.navigation.*
import com.google.android.gms.maps.GoogleMap

class NavigationManager(private val activity: FragmentActivity) {

    private var navigator: Navigator? = null
    private var simulationEnabled = false

    fun init(callback: (Navigator) -> Unit) {
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav
                callback(nav)
            }
            override fun onError(code: Int) {
                Log.e("NavigationManager", "SDK Error: $code")
            }
        })
    }

    fun setSimulation(enabled: Boolean) {
        simulationEnabled = enabled
        navigator?.simulator?.setUserLocation(null) // Reset simulator
    }

    @SuppressLint("MissingPermission")
    fun startNavigation(stops: List<Stop>, googleMap: GoogleMap?, updateCallback: (Navigator) -> Unit) {
        val nav = navigator ?: return

        if (stops.isEmpty()) {
            Log.e("NavigationManager", "Cannot start navigation: stops list is empty")
            return
        }
        
        val waypoints = stops.map { stop ->
            Waypoint.builder()
                .setLatLng(stop.latitude, stop.longitude)
                .setTitle(stop.name)
                .build()
        }

        nav.setDestinations(waypoints).setOnResultListener { status ->
            if (status == Navigator.RouteStatus.OK) {
                nav.startGuidance()
                
                // Allow SDK to control camera
                googleMap?.followMyLocation(GoogleMap.CameraPerspective.TILTED)
                
                // Register for guidance updates
                nav.addArrivalListener {
                    Log.d("NavigationManager", "Arrived at destination/waypoint")
                }
                
                updateCallback(nav)
            } else {
                Log.e("NavigationManager", "Routing failed: $status")
            }
        }
    }

    fun stopNavigation() {
        navigator?.apply {
            stopGuidance()
            clearDestinations()
        }
    }
}
