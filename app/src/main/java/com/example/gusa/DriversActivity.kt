package com.example.gusa

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gusa.databinding.ActivityDriversBinding
import com.example.gusa.databinding.ItemDriverBinding
import com.example.gusa.model.Driver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DriversActivity : FragmentActivity() {

    private lateinit var binding: ActivityDriversBinding
    private val db = FirebaseFirestore.getInstance()
    private var driversListener: ListenerRegistration? = null
    
    private val allDrivers = mutableListOf<Driver>()
    private val filteredDrivers = mutableListOf<Driver>()
    private lateinit var driversAdapter: DriversAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriversBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupListeners()
        listenToDrivers()
    }

    private fun setupRecyclerView() {
        driversAdapter = DriversAdapter(filteredDrivers) { driver ->
            showManageDriverDialog(driver)
        }
        binding.rvDrivers.apply {
            layoutManager = LinearLayoutManager(this@DriversActivity)
            adapter = driversAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterDrivers(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterDrivers(query: String) {
        filteredDrivers.clear()
        if (query.isEmpty()) {
            filteredDrivers.addAll(allDrivers)
        } else {
            val lowerQuery = query.lowercase()
            allDrivers.filter { 
                it.driverName.lowercase().contains(lowerQuery) || 
                it.driverId.toString().lowercase().contains(lowerQuery) ||
                it.email.lowercase().contains(lowerQuery)
            }.let { filteredDrivers.addAll(it) }
        }
        driversAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnAddDriver.setOnClickListener {
            startActivity(Intent(this, ImportDriversActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            listenToDrivers()
        }

        binding.btnRefreshEmpty.setOnClickListener {
            listenToDrivers()
        }
    }

    private fun listenToDrivers() {
        binding.swipeRefresh.isRefreshing = true
        driversListener?.remove()
        driversListener = db.collection("drivers")
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                if (e != null || snapshot == null) {
                    Toast.makeText(this, "Error loading drivers", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                allDrivers.clear()
                snapshot.documents.forEach { doc ->
                    doc.toObject(Driver::class.java)?.let { allDrivers.add(it) }
                }
                
                updateStats()
                filterDrivers(binding.etSearch.text.toString())
            }
    }

    private fun updateStats() {
        binding.chipTotalDrivers.text = "Total: ${allDrivers.size}"
        binding.chipActiveDrivers.text = "Active: ${allDrivers.count { it.status == "Running" || it.status == "Waiting" }}"
        binding.chipOfflineDrivers.text = "Offline: ${allDrivers.count { it.status == "Offline" }}"
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (filteredDrivers.isEmpty()) View.VISIBLE else View.GONE
        binding.rvDrivers.visibility = if (filteredDrivers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showManageDriverDialog(driver: Driver) {
        val options = arrayOf("Edit Driver Profile", "Unassign Bus", "Delete Driver")
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage ${driver.driverName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Edit profile feature coming soon", Toast.LENGTH_SHORT).show()
                    1 -> unassignBus(driver)
                    2 -> confirmDeleteDriver(driver)
                }
            }
            .show()
    }

    private fun unassignBus(driver: Driver) {
        if (driver.busId?.toString()?.isEmpty() == true) {
            Toast.makeText(this, "No bus assigned", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Unassign Bus")
            .setMessage("Are you sure you want to unassign bus ${driver.busId} from ${driver.driverName}?")
            .setPositiveButton("Unassign") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("drivers").document(driver.driverUID).update("busId", "").await()
                        db.collection("buses").document(driver.busId.toString()).update("driverId", "").await()
                        Toast.makeText(this@DriversActivity, "Bus unassigned", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@DriversActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteDriver(driver: Driver) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Driver")
            .setMessage("This will permanently delete ${driver.driverName}. This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("drivers").document(driver.driverUID).delete().await()
                        Toast.makeText(this@DriversActivity, "Driver deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@DriversActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        driversListener?.remove()
        super.onDestroy()
    }

    // Inner Adapter Class for Modernity and Encapsulation
    private inner class DriversAdapter(
        private val drivers: List<Driver>,
        private val onManageClick: (Driver) -> Unit
    ) : RecyclerView.Adapter<DriversAdapter.DriverViewHolder>() {

        inner class DriverViewHolder(val itemBinding: ItemDriverBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriverViewHolder {
            val b = ItemDriverBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return DriverViewHolder(b)
        }

        override fun onBindViewHolder(holder: DriverViewHolder, position: Int) {
            val driver = drivers[position]
            holder.itemBinding.apply {
                tvDriverName.text = driver.driverName
                tvDriverId.text = "ID: ${driver.driverId}"
                tvDriverStatus.text = driver.status
                tvAssignedBus.text = if (driver.busId?.toString()?.isNotEmpty() == true) driver.busId.toString() else "Not Assigned"
                tvAssignedRoute.text = "Loading..." // In a real app, you'd fetch route name
                tvDriverPhone.text = "Email: ${driver.email}"

                // Dynamic Status Badge Styling
                when (driver.status) {
                    "Running", "Waiting" -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
                        tvDriverStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    }
                    "Offline" -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                        tvDriverStatus.setTextColor(android.graphics.Color.parseColor("#757575"))
                    }
                    else -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                        tvDriverStatus.setTextColor(android.graphics.Color.parseColor("#EF6C00"))
                    }
                }

                btnManageDriver.setOnClickListener { onManageClick(driver) }
            }
        }

        override fun getItemCount() = drivers.size
    }
}
