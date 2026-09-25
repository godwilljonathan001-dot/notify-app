package com.example.gusa

import android.app.Application
import android.util.Log
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.MapsInitializer
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

class GusaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        try {
            // Log identity for debugging
            Log.d("GusaApp", "Initializing Notify app: $packageName")

            // Apply persistent theme
            ThemeHelper.applySavedTheme(this)
            
            // Verify Google Play Services availability
            val gmsApi = GoogleApiAvailability.getInstance()
            val gmsAvailable = gmsApi.isGooglePlayServicesAvailable(this)
            Log.d("GusaApp", "GMS Availability: $gmsAvailable")

            // Initialize Maps if GMS is available
            if (gmsAvailable == ConnectionResult.SUCCESS) {
                MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) { renderer ->
                    Log.d("GusaApp", "Maps Renderer: $renderer")
                }
            }

            // Initialize Firebase
            initializeFirebase()
        } catch (e: Exception) {
            // If anything goes wrong here, we log it but don't crash the entire app start
            Log.e("GusaApp", "Non-fatal initialization error: ${e.message}", e)
        }
    }

    private fun initializeFirebase() {
        try {
            val app = FirebaseApp.initializeApp(this)
            if (app != null) {
                Log.d("GusaApp", "Firebase initialized successfully.")
                
                try {
                    // During development (not on Play Store), we use the Debug provider.
                    // This allows the AI service to work on emulators and test devices.
                    val firebaseAppCheck = FirebaseAppCheck.getInstance()
                    firebaseAppCheck.setTokenAutoRefreshEnabled(true)
                    
                    // Defaulting to Debug provider for your current development state
                    firebaseAppCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                    Log.d("GusaApp", "Firebase App Check initialized with Debug Provider.")
                } catch (e: Exception) {
                    Log.e("GusaApp", "Firebase App Check initialization failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("GusaApp", "Firebase Initialization failed: ${e.message}")
        }
    }
}
