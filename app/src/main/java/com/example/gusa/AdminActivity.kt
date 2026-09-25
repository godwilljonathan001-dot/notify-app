package com.example.gusa

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityAdminBinding
import com.example.gusa.service.GeminiService
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AdminActivity : FragmentActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val db = FirebaseFirestore.getInstance()
    
    // Thread-safe data structure to hold stats
    private val statsMap = mutableMapOf<String, Any>()
    
    // Realtime listeners for live dashboard updates
    private val statsListeners = mutableListOf<ListenerRegistration>()

    companion object {
        private const val TAG = "AdminActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.gusa.util.ThemeHelper.applySavedTheme(this)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigationClicks()
        setupAiInsights()
        startLiveStats()
        startNotificationFeed()
    }

    private fun areNotificationsEnabled(): Boolean {
        val prefs = getSharedPreferences("GUSA_SETTINGS", MODE_PRIVATE)
        return prefs.getBoolean("PUSH_NOTIFICATIONS", true)
    }

    private fun startNotificationFeed() {
        statsListeners.add(db.collection("notifications")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(10)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                if (!areNotificationsEnabled()) {
                    binding.tvLiveFeed.text = "Notifications are disabled in settings."
                    return@addSnapshotListener
                }
                
                val feed = StringBuilder()
                snapshot.documents.forEach { doc ->
                    val type = doc.getString("type") ?: ""
                    val title = doc.getString("title") ?: "Alert"
                    val msg = doc.getString("message") ?: ""
                    
                    if (type == "trip_finished" || type == "admin_trip_report" || type == "driver_broadcast" || type == "bus_at_stop") {
                        val emoji = when(type) {
                            "trip_finished" -> "🏁"
                            "driver_broadcast" -> "📢"
                            "bus_at_stop" -> "🚏"
                            else -> "🔔"
                        }
                        feed.append("$emoji $title: $msg\n\n")
                    }
                }
                binding.tvLiveFeed.text = if (feed.isEmpty()) "No recent fleet activity." else feed.toString().trim()
            })
    }

    private fun setupNavigationClicks() {
        // Fleet Management
        binding.btnManageDrivers.setOnClickListener { startActivity(Intent(this, DriversActivity::class.java)) }
        binding.btnManageStudents.setOnClickListener { startActivity(Intent(this, StudentsActivity::class.java)) }
        binding.btnManageBuses.setOnClickListener { startActivity(Intent(this, BusesActivity::class.java)) }
        binding.btnManageRoutes.setOnClickListener { startActivity(Intent(this, RoutesActivity::class.java)) }

        // Workspace Actions
        binding.btnNavLiveTracking.setOnClickListener { startActivity(Intent(this, LiveTrackingActivity::class.java)) }
        binding.btnNavUniversity.setOnClickListener { startActivity(Intent(this, UniversityActivity::class.java)) }
        binding.btnNavReports.setOnClickListener { startActivity(Intent(this, ReportsActivity::class.java)) }

        binding.btnNavSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putExtra("ROLE", "admin")
            }
            startActivity(intent)
        }
    }

    private fun setupAiInsights() {
        binding.btnGenerateInsights.setOnClickListener {
            if (statsMap.isEmpty()) return@setOnClickListener
            
            // CRITICAL: Take a read-only snapshot copy of the map to prevent background concurrent modification crashes
            val statsSnapshot = statsMap.toMap()

            lifecycleScope.launch {
                binding.btnGenerateInsights.isEnabled = false
                binding.tvAiInsights.text = "AI is analyzing fleet performance..."
                
                try {
                    val insights = GeminiService.getAiInsights(statsSnapshot)
                    binding.tvAiInsights.text = insights
                } catch (e: Exception) {
                    binding.tvAiInsights.text = "Optimization analysis unavailable: ${e.message}"
                    Log.e(TAG, "Failed to generate AI insights", e)
                } finally {
                    binding.btnGenerateInsights.isEnabled = true
                }
            }
        }
    }

    private fun startLiveStats() {
        // Listen to Students safely
        statsListeners.add(db.collection("students").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to students", error)
                return@addSnapshotListener
            }
            val count = snapshot?.size() ?: 0
            binding.tvStatStudents.text = count.toString()
            statsMap["students"] = count
        })

        // Listen to Drivers safely
        statsListeners.add(db.collection("drivers").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to drivers", error)
                return@addSnapshotListener
            }
            val count = snapshot?.size() ?: 0
            binding.tvStatDrivers.text = count.toString()
            statsMap["drivers"] = count
        })

        // Listen to Buses (On Road) safely
        statsListeners.add(db.collection("buses").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to buses", error)
                return@addSnapshotListener
            }
            val docs = snapshot?.documents ?: emptyList()
            val total = docs.size
            val onRoad = docs.count { it.getString("status") == "On Road" }
            binding.tvStatOnRoad.text = onRoad.toString()
            statsMap["buses_total"] = total
            statsMap["buses_active"] = onRoad
        })

        // Listen to Active Trips safely
        statsListeners.add(db.collection("activeTrips").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to activeTrips", error)
                return@addSnapshotListener
            }
            val count = snapshot?.size() ?: 0
            binding.tvStatActiveTrips.text = count.toString()
            statsMap["trips_active"] = count
        })

        // Static Stats (Routes, Stops, Universities)
        lifecycleScope.launch {
            try {
                val routesCount = db.collection("routes").get().await().size()
                val universitiesCount = db.collection("universities").get().await().size()
                
                statsMap["routes"] = routesCount
                statsMap["universities"] = universitiesCount
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load static stats", e)
            }
        }
    }

    override fun onDestroy() {
        // Clean up real-time snapshot listeners to prevent memory leaks
        statsListeners.forEach { it.remove() }
        statsListeners.clear()
        super.onDestroy()
    }
}
