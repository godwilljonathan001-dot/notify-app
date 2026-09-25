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
import com.example.gusa.databinding.ActivityStopsBinding
import com.example.gusa.databinding.ItemStopBinding
import com.example.gusa.model.Stop
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class StopsActivity : FragmentActivity() {

    private lateinit var binding: ActivityStopsBinding
    private val db = FirebaseFirestore.getInstance()
    private var stopsListener: ListenerRegistration? = null
    
    private val allStops = mutableListOf<Stop>()
    private val filteredStops = mutableListOf<Stop>()
    private lateinit var stopsAdapter: StopsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStopsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupListeners()
        listenToStops()
    }

    private fun setupRecyclerView() {
        stopsAdapter = StopsAdapter(filteredStops) { stop ->
            showManageStopDialog(stop)
        }
        binding.rvStops.apply {
            layoutManager = LinearLayoutManager(this@StopsActivity)
            adapter = stopsAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterStops(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterStops(query: String) {
        filteredStops.clear()
        if (query.isEmpty()) {
            filteredStops.addAll(allStops)
        } else {
            val lowerQuery = query.lowercase()
            allStops.filter { 
                it.name.lowercase().contains(lowerQuery) || 
                it.stopId.toString().lowercase().contains(lowerQuery)
            }.let { filteredStops.addAll(it) }
        }
        stopsAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnAddStop.setOnClickListener {
            startActivity(Intent(this, AddLocationActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            listenToStops()
        }

        binding.btnRefreshEmpty.setOnClickListener {
            listenToStops()
        }
    }

    private fun listenToStops() {
        binding.swipeRefresh.isRefreshing = true
        stopsListener?.remove()
        stopsListener = db.collection("stops")
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                if (e != null || snapshot == null) {
                    Toast.makeText(this, "Error loading stops", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                allStops.clear()
                snapshot.documents.forEach { doc ->
                    doc.toObject(Stop::class.java)?.let { allStops.add(it) }
                }
                
                filterStops(binding.etSearch.text.toString())
            }
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (filteredStops.isEmpty()) View.VISIBLE else View.GONE
        binding.rvStops.visibility = if (filteredStops.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showManageStopDialog(stop: Stop) {
        val options = arrayOf("Edit Stop Details", "View on Map", "Delete Stop")
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage Stop: ${stop.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Edit feature coming soon", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this, "Map view coming soon", Toast.LENGTH_SHORT).show()
                    2 -> confirmDeleteStop(stop)
                }
            }
            .show()
    }

    private fun confirmDeleteStop(stop: Stop) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Stop")
            .setMessage("Are you sure you want to delete ${stop.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val sid = stop.stopId.toString()
                        if (sid.isEmpty()) {
                            Toast.makeText(this@StopsActivity, "Error: Invalid Stop ID", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        db.collection("stops").document(sid).delete().await()
                        Toast.makeText(this@StopsActivity, "Stop deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@StopsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        stopsListener?.remove()
        super.onDestroy()
    }

    private inner class StopsAdapter(
        private val stops: List<Stop>,
        private val onManageClick: (Stop) -> Unit
    ) : RecyclerView.Adapter<StopsAdapter.StopViewHolder>() {

        inner class StopViewHolder(val itemBinding: ItemStopBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
            val b = ItemStopBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return StopViewHolder(b)
        }

        override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
            val stop = stops[position]
            holder.itemBinding.apply {
                tvStopName.text = stop.name
                tvStopLocation.text = "Lat: %.4f, Lng: %.4f".format(stop.latitude, stop.longitude)
                tvStopRoute.text = if (stop.routeId?.toString()?.isNotEmpty() == true) stop.routeId.toString() else "No Route"

                btnViewOnMap.setOnClickListener {
                    Toast.makeText(root.context, "Opening map...", Toast.LENGTH_SHORT).show()
                }

                btnManageStop.setOnClickListener { onManageClick(stop) }
            }
        }

        override fun getItemCount() = stops.size
    }
}
