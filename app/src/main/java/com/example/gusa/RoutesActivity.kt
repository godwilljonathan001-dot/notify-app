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
import com.example.gusa.databinding.ActivityRoutesBinding
import com.example.gusa.databinding.ItemRouteBinding
import com.example.gusa.model.Route
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class RoutesActivity : FragmentActivity() {

    private lateinit var binding: ActivityRoutesBinding
    private val db = FirebaseFirestore.getInstance()
    private var routesListener: ListenerRegistration? = null
    
    private val allRoutes = mutableListOf<Route>()
    private val filteredRoutes = mutableListOf<Route>()
    private lateinit var routesAdapter: RoutesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoutesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupListeners()
        listenToRoutes()
    }

    private fun setupRecyclerView() {
        routesAdapter = RoutesAdapter(filteredRoutes) { route ->
            showManageRouteDialog(route)
        }
        binding.rvRoutes.apply {
            layoutManager = LinearLayoutManager(this@RoutesActivity)
            adapter = routesAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterRoutes(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterRoutes(query: String) {
        filteredRoutes.clear()
        if (query.isEmpty()) {
            filteredRoutes.addAll(allRoutes)
        } else {
            val lowerQuery = query.lowercase()
            allRoutes.filter { 
                it.routeName.lowercase().contains(lowerQuery) || 
                it.routeId.toString().lowercase().contains(lowerQuery)
            }.let { filteredRoutes.addAll(it) }
        }
        routesAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnAddRoute.setOnClickListener {
            startActivity(Intent(this, ImportRoutesActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            listenToRoutes()
        }

        binding.btnRefreshEmpty.setOnClickListener {
            listenToRoutes()
        }
    }

    private fun listenToRoutes() {
        binding.swipeRefresh.isRefreshing = true
        routesListener?.remove()
        routesListener = db.collection("routes")
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                if (e != null || snapshot == null) {
                    Toast.makeText(this, "Error loading routes", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                allRoutes.clear()
                snapshot.documents.forEach { doc ->
                    doc.toObject(Route::class.java)?.let { allRoutes.add(it) }
                }
                
                filterRoutes(binding.etSearch.text.toString())
            }
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (filteredRoutes.isEmpty()) View.VISIBLE else View.GONE
        binding.rvRoutes.visibility = if (filteredRoutes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showManageRouteDialog(route: Route) {
        val options = arrayOf("Edit Route Details", "Optimize Stops (AI)", "Delete Route")
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage Route: ${route.routeName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Edit feature coming soon", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this, "AI optimization coming soon", Toast.LENGTH_SHORT).show()
                    2 -> confirmDeleteRoute(route)
                }
            }
            .show()
    }

    private fun confirmDeleteRoute(route: Route) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Route")
            .setMessage("Are you sure you want to delete ${route.routeName}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("routes").document(route.routeId.toString()).delete().await()
                        Toast.makeText(this@RoutesActivity, "Route deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@RoutesActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        routesListener?.remove()
        super.onDestroy()
    }

    private inner class RoutesAdapter(
        private val routes: List<Route>,
        private val onManageClick: (Route) -> Unit
    ) : RecyclerView.Adapter<RoutesAdapter.RouteViewHolder>() {

        private val expandedStates = mutableMapOf<String, Boolean>()

        inner class RouteViewHolder(val itemBinding: ItemRouteBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
            val b = ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RouteViewHolder(b)
        }

        override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
            val route = routes[position]
            val isExpanded = expandedStates[route.routeId.toString()] ?: false

            holder.itemBinding.apply {
                tvRouteName.text = route.routeName
                tvStopsCount.text = "${route.stops.size} Scheduled Stops"
                tvRouteBus.text = "Detecting..."
                tvRouteDriver.text = "Loading..."
                tvRouteDistance.text = "-- km"
                
                tvStopsList.text = if (route.stops.isNotEmpty()) route.stops.joinToString(" -> ") else "No stops added"

                layoutDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
                btnExpand.rotation = if (isExpanded) 180f else 0f

                btnExpand.setOnClickListener {
                    val newState = !isExpanded
                    expandedStates[route.routeId.toString()] = newState
                    notifyItemChanged(position)
                }

                btnManageRoute.setOnClickListener { onManageClick(route) }
            }
        }

        override fun getItemCount() = routes.size
    }
}
