package com.example.gusa

import android.os.Bundle
import android.os.Build
import android.location.Address
import android.location.Geocoder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.example.gusa.databinding.ActivityAddLocationBinding
import com.example.gusa.model.Route
import com.example.gusa.model.University
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class AddLocationActivity : FragmentActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityAddLocationBinding
    private val db = FirebaseFirestore.getInstance()
    private var mMap: GoogleMap? = null
    private var currentMarker: Marker? = null

    private val universityList = mutableListOf<University>()
    private val routeList = mutableListOf<Route>()
    private val locationTypes = listOf("Stop", "Destination", "Terminal", "University", "Pickup Point", "Drop-off Point")

    private var isManualChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityAddLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupSpinners()
        setupListeners()
        setupSearch()
        loadInitialData()
    }

    private fun setupSearch() {
        binding.mapSearchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    searchLocation(query)
                }
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun searchLocation(location: String) {
        val geocoder = Geocoder(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocationName(location, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val latLng = LatLng(address.latitude, address.longitude)
                        runOnUiThread {
                            updateMarkerAndInputs(latLng)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@AddLocationActivity, "Location not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onError(errorMessage: String?) {
                    runOnUiThread {
                        Toast.makeText(this@AddLocationActivity, "Search error: $errorMessage", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } else {
            try {
                @Suppress("DEPRECATION")
                val addressList = geocoder.getFromLocationName(location, 1)
                if (!addressList.isNullOrEmpty()) {
                    val address = addressList[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    updateMarkerAndInputs(latLng)
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("AddLocation", "Geocoding error", e)
                Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        ThemeHelper.applyMapStyle(this, googleMap)

        // Enable UI controls for better map interaction
        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
            isMapToolbarEnabled = true
        }

        mMap?.setOnMapClickListener { latLng ->
            updateMarkerAndInputs(latLng)
        }

        mMap?.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                updateMarkerAndInputs(marker.position)
            }
        })
    }

    private fun setupSpinners() {
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, locationTypes)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerType.adapter = typeAdapter

        binding.spinnerUniversity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (universityList.isNotEmpty()) {
                    loadRoutes(universityList[position].universityId.toString())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        val coordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isManualChange) {
                    syncMapFromInputs()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etLatitude.addTextChangedListener(coordWatcher)
        binding.etLongitude.addTextChangedListener(coordWatcher)

        binding.btnSaveLocation.setOnClickListener {
            saveLocation()
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            try {
                val uniSnapshot = db.collection("universities").get().await()
                universityList.clear()
                val names = mutableListOf<String>()
                uniSnapshot.documents.forEach { doc ->
                    doc.toObject(University::class.java)?.let {
                        universityList.add(it)
                        names.add(it.name)
                    }
                }
                val adapter = ArrayAdapter(this@AddLocationActivity, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerUniversity.adapter = adapter
            } catch (e: Exception) {
                Toast.makeText(this@AddLocationActivity, "Error loading universities", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadRoutes(universityId: String) {
        lifecycleScope.launch {
            try {
                val routeSnapshot = db.collection("routes").whereEqualTo("universityId", universityId).get().await()
                routeList.clear()
                val names = mutableListOf("None / All Routes")
                routeSnapshot.documents.forEach { doc ->
                    doc.toObject(Route::class.java)?.let {
                        routeList.add(it)
                        names.add(it.routeName)
                    }
                }
                val adapter = ArrayAdapter(this@AddLocationActivity, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerRoute.adapter = adapter
            } catch (e: Exception) {
                Log.e("AddLocation", "Error loading routes", e)
            }
        }
    }

    private fun updateMarkerAndInputs(latLng: LatLng) {
        isManualChange = true
        binding.etLatitude.setText(latLng.latitude.toString())
        binding.etLongitude.setText(latLng.longitude.toString())
        isManualChange = false

        if (currentMarker == null) {
            currentMarker = mMap?.addMarker(MarkerOptions().position(latLng).draggable(true))
        } else {
            currentMarker?.position = latLng
        }
        mMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun syncMapFromInputs() {
        val lat = binding.etLatitude.text.toString().toDoubleOrNull()
        val lng = binding.etLongitude.text.toString().toDoubleOrNull()

        if (lat != null && lng != null) {
            val latLng = LatLng(lat, lng)
            if (currentMarker == null) {
                currentMarker = mMap?.addMarker(MarkerOptions().position(latLng).draggable(true))
            } else {
                currentMarker?.position = latLng
            }
            mMap?.moveCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    private fun saveLocation() {
        val name = binding.etLocationName.text.toString().trim()
        val lat = binding.etLatitude.text.toString().toDoubleOrNull()
        val lng = binding.etLongitude.text.toString().toDoubleOrNull()
        val type = binding.spinnerType.selectedItem.toString().lowercase()
        
        if (name.isEmpty() || lat == null || lng == null) {
            Toast.makeText(this, "Please provide name and valid coordinates", Toast.LENGTH_SHORT).show()
            return
        }

        val uniId = if (universityList.isNotEmpty()) universityList[binding.spinnerUniversity.selectedItemPosition].universityId else ""
        val routePos = binding.spinnerRoute.selectedItemPosition
        val routeId = if (routePos > 0) routeList[routePos - 1].routeId else ""

        val existingId = intent.getStringExtra("LOCATION_ID")
        val locationId = existingId ?: UUID.randomUUID().toString()
        val geoPoint = GeoPoint(lat, lng)

        val data = mutableMapOf<String, Any>(
            "locationId" to locationId,
            "name" to name,
            "location" to name,
            "latitude" to lat,
            "longitude" to lng,
            "geoPoint" to geoPoint,
            "type" to type,
            "createdAt" to Timestamp.now()
        )
        
        if (type == "university") {
            data["universityId"] = locationId
        } else {
            data["routeId"] = routeId as Any
            data["universityId"] = uniId as Any
        }

        val collection = when (type) {
            "stop", "pickup point", "drop-off point", "terminal" -> "stops"
            "university" -> "universities"
            else -> "destinations"
        }

        lifecycleScope.launch {
            try {
                db.collection(collection).document(locationId).set(data).await()
                Toast.makeText(this@AddLocationActivity, "Location saved successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddLocationActivity, "Error saving location: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
