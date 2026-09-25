package com.example.gusa

import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.location.Address
import android.location.Geocoder
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityImportuniversitiesBinding
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

class ImportUniversitiesActivity : FragmentActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityImportuniversitiesBinding
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
        binding = ActivityImportuniversitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupModeSelector()
        setupListeners()
        setupSearch()
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
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
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
            binding.tvAiSubTitle.text = "Paste structured university data (ID, Name, Location)"
            binding.etRawData.hint = "Paste university records here..."
            binding.btnValidate.text = "Validate Registry"
        } else if (mode == ImportMode.AI) {
            binding.tvAiTitle.text = "AI Assisted Import"
            binding.tvAiSubTitle.text = "Powered by Gemini 3.1 Flash Lite"
            binding.etRawData.hint = "Paste unstructured campus details..."
            binding.btnValidate.text = "Establish with AI"
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
        binding.btnPickOnMap.setOnClickListener {
            binding.mapContainer.visibility = if (binding.mapContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.btnPickOnMap.text = if (binding.mapContainer.visibility == View.VISIBLE) "Collapse Map" else "Locate on World Map"
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
        val uid = binding.etManualId.text.toString().trim()
        val name = binding.etManualName.text.toString().trim()
        val loc = binding.etManualLocation.text.toString().trim()
        val lat = binding.etManualLat.text.toString().toDoubleOrNull()
        val lng = binding.etManualLng.text.toString().toDoubleOrNull()
        
        if (uid.isEmpty() || name.isEmpty() || lat == null || lng == null) {
            showSnackbar("ID, Name, and Coordinates are required")
            return
        }

        val obj = JSONObject().apply {
            put("universityId", uid)
            put("name", name)
            put("location", loc)
            put("latitude", lat)
            put("longitude", lng)
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
                updateProgress("AI Architect establishing campus bounds...")
                val responseStr = GeminiService.parseImportData(rawText, "universities")
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
            val uid = obj.optString("universityId")
            
            var status = "Ready"
            val issues = mutableListOf<String>()

            if (uid.isEmpty()) {
                status = "Error"
                issues.add("Missing ID")
                errCount++
            } else {
                val existing = db.collection("universities").document(uid).get().await()
                if (existing.exists()) {
                    status = "Duplicate"
                    issues.add("ID in use")
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
        binding.tvStatTotal.text = "Institutions: $total"
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
        arrayOf("Status", "Uni ID", "Name", "Location").forEach { 
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
            row.addView(TextView(this).apply { text = obj.optString("universityId"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("name"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("location"); setPadding(16, 12, 16, 12) })
            binding.tablePreview.addView(row)
        }
    }

    private fun executeBatchUpload() {
        val toUpload = validatedData.filter { it.optString("_status") != "Error" }
        lifecycleScope.launch {
            try {
                binding.layoutValidation.visibility = View.GONE
                binding.layoutProcessing.visibility = View.VISIBLE
                val importId = "UNI-BATCH-${System.currentTimeMillis()}"
                currentImportId = importId
                importedIds.clear()

                toUpload.forEach { uni ->
                    val id = uni.optString("universityId")
                    val lat = uni.optDouble("latitude", 0.0)
                    val lng = uni.optDouble("longitude", 0.0)
                    val map = hashMapOf(
                        "universityId" to id,
                        "name" to uni.optString("name"),
                        "location" to uni.optString("location"),
                        "latitude" to lat,
                        "longitude" to lng,
                        "geoPoint" to GeoPoint(lat, lng),
                        "importId" to importId,
                        "createdAt" to Timestamp.now()
                    )
                    db.collection("universities").document(id).set(map).await()
                    importedIds.add(id)
                }
                updateStepIndicator(4)
                showCompletion(importedIds.size)
            } catch (e: Exception) {
                showSnackbar("Campus establishment failed")
                binding.layoutProcessing.visibility = View.GONE
            }
        }
    }

    private fun showCompletion(count: Int) {
        binding.layoutProcessing.visibility = View.GONE
        binding.cardCompletion.visibility = View.VISIBLE
        binding.tvImportSummary.text = "Established $count campuses.\nBatch: $currentImportId"
    }

    private fun undoLastImport() {
        lifecycleScope.launch {
            try {
                importedIds.forEach { db.collection("universities").document(it).delete().await() }
                Toast.makeText(this@ImportUniversitiesActivity, "Reverted", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) { showSnackbar("Undo failed") }
        }
    }

    private fun showValidationError(msg: String) { showSnackbar(msg) }
    private fun showSnackbar(msg: String) { Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show() }
}
