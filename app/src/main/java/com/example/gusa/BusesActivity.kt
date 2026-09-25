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
import com.example.gusa.databinding.ActivityBusesBinding
import com.example.gusa.databinding.ItemBusBinding
import com.example.gusa.model.Bus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BusesActivity : FragmentActivity() {

    private lateinit var binding: ActivityBusesBinding
    private val db = FirebaseFirestore.getInstance()
    private var busesListener: ListenerRegistration? = null
    
    private val allBuses = mutableListOf<Bus>()
    private val filteredBuses = mutableListOf<Bus>()
    private lateinit var busesAdapter: BusesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupListeners()
        listenToBuses()
    }

    private fun setupRecyclerView() {
        busesAdapter = BusesAdapter(filteredBuses) { bus ->
            showManageBusDialog(bus)
        }
        binding.rvBuses.apply {
            layoutManager = LinearLayoutManager(this@BusesActivity)
            adapter = busesAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterBuses(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterBuses(query: String) {
        filteredBuses.clear()
        if (query.isEmpty()) {
            filteredBuses.addAll(allBuses)
        } else {
            val lowerQuery = query.lowercase()
            allBuses.filter { 
                it.busNumber.lowercase().contains(lowerQuery) || 
                it.busId.toString().lowercase().contains(lowerQuery)
            }.let { filteredBuses.addAll(it) }
        }
        busesAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnAddBus.setOnClickListener {
            startActivity(Intent(this, ImportBusesActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            listenToBuses()
        }

        binding.chipSort.setOnClickListener {
            showSortDialog()
        }

        binding.btnRefreshEmpty.setOnClickListener {
            listenToBuses()
        }
    }

    private fun listenToBuses() {
        binding.swipeRefresh.isRefreshing = true
        busesListener?.remove()
        busesListener = db.collection("buses")
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                if (e != null || snapshot == null) {
                    Toast.makeText(this, "Error loading buses", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                allBuses.clear()
                snapshot.documents.forEach { doc ->
                    doc.toObject(Bus::class.java)?.let { allBuses.add(it) }
                }
                
                updateStats()
                filterBuses(binding.etSearch.text.toString())
            }
    }

    private fun updateStats() {
        binding.chipTotalBuses.text = "Total: ${allBuses.size}"
        binding.chipRunningBuses.text = "Running: ${allBuses.count { it.status == "On Road" }}"
        binding.chipParkedBuses.text = "Parked: ${allBuses.count { it.status == "Parked" }}"
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (filteredBuses.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBuses.visibility = if (filteredBuses.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSortDialog() {
        val options = arrayOf("Bus Number (Asc)", "Bus Number (Desc)", "Status")
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort Buses")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> allBuses.sortBy { it.busNumber }
                    1 -> allBuses.sortByDescending { it.busNumber }
                    2 -> allBuses.sortBy { it.status }
                }
                filterBuses(binding.etSearch.text.toString())
            }
            .show()
    }

    private fun showManageBusDialog(bus: Bus) {
        val options = arrayOf("Update Status", "Assign Driver", "Delete Bus")
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage Bus ${bus.busNumber}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showStatusUpdateDialog(bus)
                    1 -> Toast.makeText(this, "Driver assignment coming soon", Toast.LENGTH_SHORT).show()
                    2 -> confirmDeleteBus(bus)
                }
            }
            .show()
    }

    private fun showStatusUpdateDialog(bus: Bus) {
        val statuses = arrayOf("Parked", "On Road", "Maintenance")
        var selected = statuses.indexOf(bus.status).coerceAtLeast(0)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Update Bus Status")
            .setSingleChoiceItems(statuses, selected) { _, which ->
                selected = which
            }
            .setPositiveButton("Update") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("buses").document(bus.busId.toString()).update("status", statuses[selected]).await()
                        Toast.makeText(this@BusesActivity, "Status updated to ${statuses[selected]}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@BusesActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBus(bus: Bus) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Bus")
            .setMessage("Are you sure you want to delete Bus ${bus.busNumber}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("buses").document(bus.busId.toString()).delete().await()
                        Toast.makeText(this@BusesActivity, "Bus deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@BusesActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        busesListener?.remove()
        super.onDestroy()
    }

    private inner class BusesAdapter(
        private val buses: List<Bus>,
        private val onManageClick: (Bus) -> Unit
    ) : RecyclerView.Adapter<BusesAdapter.BusViewHolder>() {

        inner class BusViewHolder(val itemBinding: ItemBusBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusViewHolder {
            val b = ItemBusBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return BusViewHolder(b)
        }

        override fun onBindViewHolder(holder: BusViewHolder, position: Int) {
            val bus = buses[position]
            holder.itemBinding.apply {
                tvBusNumber.text = "Bus #${bus.busNumber}"
                tvBusStatus.text = bus.status
                tvBusStatusDetail.text = if (bus.status == "On Road") "Active on assigned route" else "Stationary"
                tvBusDriver.text = if (bus.driverId?.toString()?.isNotEmpty() == true) bus.driverId.toString() else "Unassigned"
                tvBusLoad.text = "${bus.passengerCount} / ${bus.totalSeats}"
                tvBusCapacity.text = "${bus.totalSeats} Seats"
                tvBusStop.text = "Detecting..." // Fetch actual stop if needed

                // Dynamic Status Badge
                when (bus.status) {
                    "On Road" -> {
                        cardBusStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
                        tvBusStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    }
                    "Parked" -> {
                        cardBusStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                        tvBusStatus.setTextColor(android.graphics.Color.parseColor("#757575"))
                    }
                    "Maintenance" -> {
                        cardBusStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#FEEBEE"))
                        tvBusStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                    }
                }

                btnManageBus.setOnClickListener { onManageClick(bus) }
            }
        }

        override fun getItemCount() = buses.size
    }
}
