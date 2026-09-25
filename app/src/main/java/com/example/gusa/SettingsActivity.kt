package com.example.gusa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivitySettingsBinding
import com.example.gusa.model.Stop
import com.example.gusa.service.SystemSeeder
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class SettingsActivity : FragmentActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var role = "admin"

    // Driver context for broadcast
    private var driverName = ""
    private var driverId = ""
    private var busId = ""
    private var routeId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        role = intent.getStringExtra("ROLE") ?: "admin"
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadUserProfile()
        setupListeners()
        applyRoleUi()
    }

    private fun applyRoleUi() {
        when (role) {
            "student" -> {
                binding.layoutStudentSettings.visibility = View.VISIBLE
                binding.layoutDriverSettings.visibility = View.GONE
                binding.tvAdminTitle.visibility = View.GONE
                binding.cardAdmin.visibility = View.GONE
            }
            "driver" -> {
                binding.layoutStudentSettings.visibility = View.GONE
                binding.layoutDriverSettings.visibility = View.VISIBLE
                binding.tvAdminTitle.visibility = View.GONE
                binding.cardAdmin.visibility = View.GONE
            }
            else -> {
                binding.layoutStudentSettings.visibility = View.GONE
                binding.layoutDriverSettings.visibility = View.GONE
                binding.tvAdminTitle.visibility = View.VISIBLE
                binding.cardAdmin.visibility = View.VISIBLE
                binding.tvAdminTitle.text = "System Administration"
            }
        }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user != null) {
            binding.tvProfileEmail.text = user.email
            binding.tvProfileRole.text = when(role) {
                "student" -> "University Student"
                "driver" -> "Fleet Driver"
                else -> "System Administrator"
            }
            
            lifecycleScope.launch {
                try {
                    val collection = when(role) {
                        "student" -> "students"
                        "driver" -> "drivers"
                        else -> "admins"
                    }
                    val doc = db.collection(collection).document(user.uid).get().await()
                    if (doc.exists()) {
                        val name = when(role) {
                            "student" -> doc.getString("studentName")
                            "driver" -> doc.getString("driverName")
                            else -> doc.getString("name")
                        }
                        binding.tvProfileName.text = name ?: "User"
                        
                        // Load extra info for drivers/students
                        if (role == "driver" || role == "student") {
                            busId = doc.getString("busId") ?: "--"
                            routeId = doc.getString("routeId") ?: "--"
                            binding.tvProfileExtraInfo.text = "Bus ID: $busId | Route: $routeId"
                            
                            if (role == "driver") {
                                driverName = name ?: "Driver"
                                driverId = doc.getString("driverId") ?: ""
                            }
                        } else {
                            binding.tvProfileExtraInfo.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    binding.tvProfileName.text = "User Profile"
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnSeedData.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Seed Database")
                .setMessage("This action will reset the database to its initial state. This is an administrative tool. Are you sure?")
                .setPositiveButton("Reset Database") { _, _ ->
                    executeSeeding()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Confirm Logout")
                .setMessage("Are you sure you want to sign out of your account?")
                .setPositiveButton("Logout") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        val dummyListener = { Toast.makeText(this, "Feature coming soon!", Toast.LENGTH_SHORT).show() }
        binding.btnChangePassword.setOnClickListener { dummyListener() }
        binding.btnSystemInfo.setOnClickListener { dummyListener() }
        binding.btnFirestoreStatus.setOnClickListener { dummyListener() }
        binding.btnAbout.setOnClickListener { dummyListener() }
        binding.btnChangePickup.setOnClickListener { showChangePickupDialog() }
        binding.btnEditDetails.setOnClickListener { dummyListener() }
        binding.btnNavPreferences.setOnClickListener { dummyListener() }
        
        binding.btnTheme.setOnClickListener { showThemeSelectionDialog() }
        binding.btnMapAppearance.setOnClickListener { showMapAppearanceDialog() }

        // Notification toggle
        val prefs = getSharedPreferences("GUSA_SETTINGS", MODE_PRIVATE)
        binding.switchPushNotifications.isChecked = prefs.getBoolean("PUSH_NOTIFICATIONS", true)
        binding.switchPushNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("PUSH_NOTIFICATIONS", isChecked).apply()
            val status = if (isChecked) "enabled" else "disabled"
            Toast.makeText(this, "Notifications $status", Toast.LENGTH_SHORT).show()
        }

        binding.btnBroadcastMessage.setOnClickListener {
            showBroadcastDialog()
        }
    }

    private fun showBroadcastDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter message for students..."
            setPadding(40, 40, 40, 40)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Broadcast Message")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    sendDriverBroadcast(msg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendDriverBroadcast(message: String) {
        val broadcast = hashMapOf(
            "title" to "Driver Update",
            "message" to message,
            "senderName" to driverName,
            "senderId" to driverId,
            "busId" to busId,
            "routeId" to routeId,
            "type" to "driver_broadcast",
            "createdAt" to com.google.firebase.Timestamp.now()
        )
        db.collection("notifications").add(broadcast)
            .addOnSuccessListener {
                Toast.makeText(this, "Message broadcasted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send broadcast", Toast.LENGTH_SHORT).show()
            }
    }

    private fun executeSeeding() {
        lifecycleScope.launch {
            binding.btnSeedData.isEnabled = false
            binding.btnSeedData.text = "Seeding..."
            val success = SystemSeeder.seedAllData()
            if (success) {
                Toast.makeText(this@SettingsActivity, "Database seeded successfully!", Toast.LENGTH_LONG).show()
                // Change theme when user clicks 'fix it' (Reset Database)
                ThemeHelper.toggleTheme(this@SettingsActivity)
            } else {
                Toast.makeText(this@SettingsActivity, "Seeding failed. Check logs.", Toast.LENGTH_LONG).show()
            }
            binding.btnSeedData.isEnabled = true
            binding.btnSeedData.text = "Seed Initial Database Data"
        }
    }

    private fun performLogout() {
        auth.signOut()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf("Light", "Dark", "System Default")
        var checkedItem = ThemeHelper.getSavedTheme(this)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Appearance Theme")
            .setSingleChoiceItems(themes, checkedItem) { _, which ->
                checkedItem = which
            }
            .setPositiveButton("Apply") { _, _ ->
                ThemeHelper.applyTheme(this, checkedItem)
                Toast.makeText(this, "Theme updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMapAppearanceDialog() {
        val options = arrayOf("Light Map", "Dark Map", "Satellite", "Terrain")
        MaterialAlertDialogBuilder(this)
            .setTitle("Map Configuration")
            .setItems(options) { _, which ->
                Toast.makeText(this, "${options[which]} selected. Updating map...", Toast.LENGTH_SHORT).show()
                // Business logic for map update would go here
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showChangePickupDialog() {
        val options = arrayOf("Manual Selection", "Auto-Detect Closest Stop")
        MaterialAlertDialogBuilder(this)
            .setTitle("Change Pickup Stop")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showManualStopSelection()
                    1 -> autoDetectClosestStop()
                }
            }
            .show()
    }

    private fun showManualStopSelection() {
        if (routeId.isEmpty() || routeId == "--") {
            Toast.makeText(this, "No route assigned to your profile.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val stopsQuery = db.collection("stops")
                    .whereEqualTo("routeId", routeId)
                    .get().await()

                val stopsList = stopsQuery.documents.mapNotNull { it.toObject(Stop::class.java) }
                if (stopsList.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No stops found for your route.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val stopNames = stopsList.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Select New Stop")
                    .setItems(stopNames) { _, index ->
                        updatePickupStop(stopsList[index])
                    }
                    .show()
            } catch (e: Exception) {
                Log.e("Settings", "Error fetching stops", e)
                Toast.makeText(this@SettingsActivity, "Error loading stops", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                autoDetectClosestStop()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                autoDetectClosestStop()
            }
            else -> {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoDetectClosestStop() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        if (routeId.isEmpty() || routeId == "--") {
            Toast.makeText(this, "No route assigned.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@SettingsActivity)
                val lastLocation = fusedLocationClient.lastLocation.await()

                if (lastLocation == null) {
                    Toast.makeText(this@SettingsActivity, "Could not determine current location.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val stopsQuery = db.collection("stops")
                    .whereEqualTo("routeId", routeId)
                    .get().await()

                val stopsList = stopsQuery.documents.mapNotNull { it.toObject(Stop::class.java) }
                if (stopsList.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No stops found for your route.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                var closestStop: Stop? = null
                var minDistance = Float.MAX_VALUE

                for (stop in stopsList) {
                    val stopLoc = Location("").apply {
                        latitude = stop.latitude
                        longitude = stop.longitude
                    }
                    val dist = lastLocation.distanceTo(stopLoc)
                    if (dist < minDistance) {
                        minDistance = dist
                        closestStop = stop
                    }
                }

                closestStop?.let {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Closest Stop Found")
                        .setMessage("Detected ${it.name} (${minDistance.roundToInt()}m away) as your closest stop. Change to this stop?")
                        .setPositiveButton("Change") { _, _ -> updatePickupStop(it) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

            } catch (e: Exception) {
                Log.e("Settings", "Error auto-detecting stop", e)
                Toast.makeText(this@SettingsActivity, "Error detecting location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePickupStop(stop: Stop) {
        val user = auth.currentUser ?: return
        lifecycleScope.launch {
            try {
                db.collection("students").document(user.uid)
                    .update("pickupStation", stop.stopId.toString())
                    .await()
                
                Toast.makeText(this@SettingsActivity, "Pickup stop updated to ${stop.name}", Toast.LENGTH_LONG).show()
                // Update local UI
                loadUserProfile()
            } catch (e: Exception) {
                Log.e("Settings", "Error updating pickup stop", e)
                Toast.makeText(this@SettingsActivity, "Failed to update stop", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
