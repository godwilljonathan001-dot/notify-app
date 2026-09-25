package com.example.gusa

import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.location.Address
import android.location.Geocoder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityImportdestinationsBinding
import com.example.gusa.service.GeminiService
import com.example.gusa.util.ThemeHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class ImportDestinationsActivity : FragmentActivity(), OnMapReadyCallback {
    
    private lateinit var binding: ActivityImportdestinationsBinding
    private val db = FirebaseFirestore.getInstance()
    private var mMap: GoogleMap? = null
    private var currentMarker: Marker? = null

    private var validatedData = mutableListOf<JSONObject>()
    private var currentImportId: String? = null
    private var importedIds = mutableListOf<String>()

    private enum class ImportMode { MANUAL, AI, RAW }
    private var currentMode = ImportMode.MANUAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        
        // Ensure binding is initialized before use
        binding = ActivityImportdestinationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupModeSelector()
        setupListeners()
        setupSearch()
        loadDropdownData()
        updateStepIndicator(1)

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.mapFrame, mapFragment).commit()
        mapFragment.getMapAsync(this)
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
                            showSnackbar("Location not found")
                        }
                    }
                }
                override fun onError(errorMessage: String?) {
                    runOnUiThread {
                        showSnackbar("Search error: $errorMessage")
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
                    showSnackbar("Location not found")
                }
            } catch (e: Exception) {
                showSnackbar("Search error: ${e.message}")
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupModeSelector() {
        // Adding explicit types to help with inference issues in the IDE
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeManual -> switchMode(ImportMode.MANUAL)
                    R.id.btnModeAi -> switchMode(ImportMode.AI)
                    R.id.btnModeJson -> switchMode(ImportMode.RAW)
                }
            }
        }
        switchMode(ImportMode.MANUAL)
    }

    private fun switchMode(mode: ImportMode) {
        currentMode = mode
        binding.cardManualForm.visibility = if (mode == ImportMode.MANUAL) View.VISIBLE else View.GONE
        binding.cardAiImport.visibility = if (mode != ImportMode.MANUAL) View.VISIBLE else View.GONE
        
        binding.layoutValidation.visibility = View.GONE
        updateStepIndicator(1)

        if (mode == ImportMode.RAW) {
            binding.tvAiTitle.text = "Raw Text Import"
            binding.tvAiSubTitle.text = "Paste structured destination data (Name, Lat, Lng)"
            binding.etRawData.hint = "Paste destination records here..."
            binding.btnValidate.text = "Validate Destinations"
        } else if (mode == ImportMode.AI) {
            binding.tvAiTitle.text = "AI Assisted Import"
            binding.tvAiSubTitle.text = "Powered by Gemini 3.1 Flash Lite"
            binding.etRawData.hint = "Paste unstructured location descriptions..."
            binding.btnValidate.text = "Analyze with AI"
        }
    }

    private fun updateStepIndicator(step: Int) {
        val activeColor = Color.parseColor("#6200EE")
        val inactiveColor = Color.parseColor("#E0E0E0")
        binding.step1.setBackgroundColor(if (step >= 1) activeColor else inactiveColor)
        binding.step2.setBackgroundColor(if (step >= 2) activeColor else inactiveColor)
        binding.step3.setBackgroundColor(if (step >= 3) activeColor else inactiveColor)
        binding.step4.setBackgroundColor(if (step >= 4) activeColor else inactiveColor)
    }

    private fun loadDropdownData() {
        lifecycleScope.launch {
            try {
                val unis = db.collection("universities").get().await().documents.map { it.id }
                binding.spinnerManualUni.setAdapter(ArrayAdapter(this@ImportDestinationsActivity, android.R.layout.simple_dropdown_item_1line, unis))
            } catch (e: Exception) {
                Toast.makeText(this@ImportDestinationsActivity, "Sync error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        ThemeHelper.applyMapStyle(this, googleMap)
        
        // Enhance map stability and usability for admin selection
        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
            isMapToolbarEnabled = true
        }

        mMap?.setOnMapClickListener { latLng ->
            updateMarkerAndInputs(latLng)
        }
    }

    private fun updateMarkerAndInputs(latLng: LatLng) {
        binding.etManualLat.setText(latLng.latitude.toString())
        binding.etManualLng.setText(latLng.longitude.toString())

        if (currentMarker == null) {
            currentMarker = mMap?.addMarker(MarkerOptions().position(latLng))
        } else {
            currentMarker?.position = latLng
        }
        mMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun setupListeners() {
        val coordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val lat = binding.etManualLat.text.toString().toDoubleOrNull()
                val lng = binding.etManualLng.text.toString().toDoubleOrNull()
                if (lat != null && lng != null) {
                    val latLng = LatLng(lat, lng)
                    if (currentMarker == null) {
                        currentMarker = mMap?.addMarker(MarkerOptions().position(latLng))
                    } else {
                        currentMarker?.position = latLng
                    }
                    mMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
                }
            }
        }
        binding.etManualLat.addTextChangedListener(coordWatcher)
        binding.etManualLng.addTextChangedListener(coordWatcher)

        binding.btnPickOnMap.setOnClickListener {
            binding.mapContainer.visibility = if (binding.mapContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.btnPickOnMap.text = if (binding.mapContainer.visibility == View.VISIBLE) "Hide Map" else "Select on Google Maps"
        }

        binding.btnSaveManual.setOnClickListener {
            processManualEntry()
        }

        binding.btnValidate.setOnClickListener {
            val rawText = binding.etRawData.text.toString().trim()
            if (rawText.isEmpty()) {
                showSnackbar("Provide source data")
                return@setOnClickListener
            }
            startValidationWorkflow(rawText)
        }

        binding.btnConfirmUpload.setOnClickListener {
            executeBatchUpload()
        }

        binding.btnUndo.setOnClickListener {
            undoLastImport()
        }
    }

    private fun processManualEntry() {
        val name = binding.etManualName.text.toString().trim()
        val lat = binding.etManualLat.text.toString().toDoubleOrNull()
        val lng = binding.etManualLng.text.toString().toDoubleOrNull()
        
        if (name.isEmpty() || lat == null || lng == null) {
            showSnackbar("Name and valid coordinates are required")
            return
        }

        val obj = JSONObject().apply {
            put("destinationId", UUID.randomUUID().toString())
            put("name", name)
            put("latitude", lat)
            put("longitude", lng)
            put("universityId", binding.spinnerManualUni.text.toString().trim())
        }

        validatedData.clear()
        validatedData.add(obj)
        
        lifecycleScope.launch {
            updateStepIndicator(2)
            setValidationState(isProcessing = true)
            validateParsedData(JSONArray().put(obj))
            setValidationState(isProcessing = false)
            updateStepIndicator(3)
        }
    }

    private fun startValidationWorkflow(rawText: String) {
        lifecycleScope.launch {
            updateStepIndicator(2)
            setValidationState(isProcessing = true)
            try {
                updateProgress("AI Architect analyzing spatial data...")
                val responseStr = GeminiService.parseImportData(rawText, "destinations")
                if (responseStr.startsWith("Error")) throw Exception(responseStr)
                val responseJson = JSONObject(responseStr)
                val data = responseJson.optJSONArray("data") ?: JSONArray()
                validateParsedData(data)
                updateStepIndicator(3)
            } catch (e: Exception) {
                showValidationError(e.message ?: "Analysis failed")
                updateStepIndicator(1)
            } finally {
                setValidationState(isProcessing = false)
            }
        }
    }

    private suspend fun validateParsedData(data: JSONArray) {
        validatedData.clear()
        var errCount = 0
        var dupCount = 0
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val name = obj.optString("name")
            val lat = obj.optDouble("latitude", Double.NaN)
            val lng = obj.optDouble("longitude", Double.NaN)
            
            var status = "Ready"
            val issues = mutableListOf<String>()

            if (name.isEmpty() || lat.isNaN() || lng.isNaN()) {
                status = "Error"
                issues.add("Incomplete Data")
                errCount++
            } else {
                // Check for existing destination with same name
                val existing = db.collection("destinations").whereEqualTo("name", name).get().await()
                if (!existing.isEmpty) {
                    status = "Duplicate"
                    issues.add("Name exists")
                    dupCount++
                }
            }
            obj.put("_status", status)
            obj.put("_issues", issues.joinToString(", "))
            validatedData.add(obj)
        }
        updateStatsUi(data.length(), dupCount, errCount)
        generatePreviewTable()
    }

    private fun updateStatsUi(total: Int, dups: Int, errs: Int) {
        binding.layoutValidation.visibility = View.VISIBLE
        binding.tvStatTotal.text = "Units: $total"
        binding.tvStatValid.text = "Valid: ${total - dups - errs}"
        binding.tvStatDuplicates.text = "Dups: $dups"
        binding.tvStatErrors.text = "Errors: $errs"
        binding.btnConfirmUpload.isEnabled = (total - errs) > 0
    }

    private fun setValidationState(isProcessing: Boolean) {
        binding.layoutProcessing.visibility = if (isProcessing) View.VISIBLE else View.GONE
        binding.btnValidate.isEnabled = !isProcessing
    }

    private fun updateProgress(msg: String) {
        binding.tvProgressStatus.text = msg
    }

    private fun generatePreviewTable() {
        binding.tablePreview.removeAllViews()
        val header = TableRow(this).apply { setBackgroundColor(Color.parseColor("#F1F3F4")) }
        arrayOf("Status", "Name", "Latitude", "Longitude").forEach { 
            header.addView(TextView(this).apply { text = it; setPadding(16, 16, 16, 16); setTypeface(null, android.graphics.Typeface.BOLD) }) 
        }
        binding.tablePreview.addView(header)

        validatedData.take(50).forEach { obj ->
            val row = TableRow(this)
            row.addView(TextView(this).apply { 
                text = obj.optString("_status")
                setTextColor(if (text == "Ready") Color.parseColor("#2E7D32") else Color.RED)
                setPadding(16, 12, 16, 12)
            })
            row.addView(TextView(this).apply { text = obj.optString("name"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("latitude"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("longitude"); setPadding(16, 12, 16, 12) })
            binding.tablePreview.addView(row)
        }
    }

    private fun executeBatchUpload() {
        val toUpload = validatedData.filter { it.optString("_status") != "Error" }
        lifecycleScope.launch {
            try {
                binding.layoutValidation.visibility = View.GONE
                binding.layoutProcessing.visibility = View.VISIBLE
                val importId = "DEST-BATCH-${System.currentTimeMillis()}"
                currentImportId = importId
                importedIds.clear()

                toUpload.forEach { dest ->
                    val id = dest.optString("destinationId", UUID.randomUUID().toString())
                    val lat = dest.optDouble("latitude")
                    val lng = dest.optDouble("longitude")
                    val map = hashMapOf(
                        "destinationId" to id,
                        "name" to dest.optString("name"),
                        "latitude" to lat,
                        "longitude" to lng,
                        "geoPoint" to GeoPoint(lat, lng),
                        "universityId" to dest.optString("universityId"),
                        "type" to "destination",
                        "importId" to importId,
                        "createdAt" to Timestamp.now()
                    )
                    db.collection("destinations").document(id).set(map).await()
                    importedIds.add(id)
                }
                updateStepIndicator(4)
                showCompletion(importedIds.size)
            } catch (e: Exception) {
                showSnackbar("Onboarding failed")
                binding.layoutProcessing.visibility = View.GONE
            }
        }
    }

    private fun showCompletion(count: Int) {
        binding.layoutProcessing.visibility = View.GONE
        binding.cardCompletion.visibility = View.VISIBLE
        binding.tvImportSummary.text = "Registered $count destinations.\nBatch: $currentImportId"
    }

    private fun undoLastImport() {
        lifecycleScope.launch {
            try {
                importedIds.forEach { db.collection("destinations").document(it).delete().await() }
                Toast.makeText(this@ImportDestinationsActivity, "Reverted", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) { showSnackbar("Undo failed") }
        }
    }

    private fun showValidationError(msg: String) { showSnackbar(msg) }
    private fun showSnackbar(msg: String) { Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show() }
}
