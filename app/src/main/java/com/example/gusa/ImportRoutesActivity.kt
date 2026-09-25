package com.example.gusa

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityImportroutesBinding
import com.example.gusa.service.GeminiService
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ImportRoutesActivity : FragmentActivity() {
    private lateinit var binding: ActivityImportroutesBinding
    private val db = FirebaseFirestore.getInstance()

    private var validatedData = mutableListOf<JSONObject>()
    private var currentImportId: String? = null
    private var importedIds = mutableListOf<String>()

    private enum class ImportMode { MANUAL, AI, RAW }
    private var currentMode = ImportMode.AI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.gusa.util.ThemeHelper.applySavedTheme(this)
        binding = ActivityImportroutesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupModeSelector()
        setupListeners()
        loadDropdownData()
        updateStepIndicator(1)
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
        switchMode(ImportMode.AI)
    }

    private fun switchMode(mode: ImportMode) {
        currentMode = mode
        binding.cardManualForm.visibility = if (mode == ImportMode.MANUAL) View.VISIBLE else View.GONE
        binding.cardAiImport.visibility = if (mode != ImportMode.MANUAL) View.VISIBLE else View.GONE
        
        binding.layoutValidation.visibility = View.GONE
        updateStepIndicator(1)

        if (mode == ImportMode.RAW) {
            binding.tvAiTitle.text = "Raw Text Import"
            binding.tvAiSubTitle.text = "Paste structured route data (CSV, Tabular)"
            binding.etRawData.hint = "Paste route definitions here..."
            binding.btnValidate.text = "Validate Routes"
        } else if (mode == ImportMode.AI) {
            binding.tvAiTitle.text = "AI Assisted Import"
            binding.tvAiSubTitle.text = "Powered by Gemini 3.1 Flash Lite"
            binding.etRawData.hint = "Paste unstructured route descriptions..."
            binding.btnValidate.text = "Analyze Routes with AI"
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
                binding.spinnerManualUni.setAdapter(ArrayAdapter(this@ImportRoutesActivity, android.R.layout.simple_dropdown_item_1line, unis))

                val starts = db.collection("starting_locations").get().await().documents.map { it.id }
                binding.spinnerManualStart.setAdapter(ArrayAdapter(this@ImportRoutesActivity, android.R.layout.simple_dropdown_item_1line, starts))

                val dests = db.collection("destinations").get().await().documents.map { it.id }
                binding.spinnerManualDest.setAdapter(ArrayAdapter(this@ImportRoutesActivity, android.R.layout.simple_dropdown_item_1line, dests))
            } catch (e: Exception) {
                Toast.makeText(this@ImportRoutesActivity, "Sync error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnSaveManual.setOnClickListener {
            processManualEntry()
        }

        binding.btnValidate.setOnClickListener {
            val rawText = binding.etRawData.text.toString().trim()
            if (rawText.isEmpty()) {
                showSnackbar("Provide source data to proceed")
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
        val routeId = binding.etManualRouteId.text.toString().trim()
        val name = binding.etManualRouteName.text.toString().trim()
        
        if (routeId.isEmpty() || name.isEmpty()) {
            showSnackbar("Route ID and Name are required")
            return
        }

        val obj = JSONObject().apply {
            put("routeId", routeId)
            put("routeName", name)
            put("universityId", binding.spinnerManualUni.text.toString().trim())
            put("startingLocationId", binding.spinnerManualStart.text.toString().trim())
            put("destinationId", binding.spinnerManualDest.text.toString().trim())
            put("stops", JSONArray()) // Empty for manual quick entry
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
                updateProgress(if (currentMode == ImportMode.AI) "AI Analysis in progress..." else "Parsing route data...")
                val responseStr = GeminiService.parseImportData(rawText, "routes")
                
                if (responseStr.startsWith("Error")) throw Exception(responseStr)

                val responseJson = JSONObject(responseStr)
                val data = responseJson.optJSONArray("data") ?: JSONArray()
                
                updateProgress("Cross-checking with Infrastructure...")
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
        val totalCount = data.length()
        var dupCount = 0
        val errCount = 0 // Auto-generation removed current error path

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val rid = obj.optString("routeId").trim()
            
            var status = "Ready"
            val issues = mutableListOf<String>()

            if (rid.isEmpty()) {
                val autoId = "ROUTE-${UUID.randomUUID().toString().take(6).uppercase()}"
                obj.put("routeId", autoId)
                issues.add("ID Auto-generated")
            } else {
                val existing = db.collection("routes").document(rid).get().await()
                if (existing.exists()) {
                    status = "Duplicate"
                    issues.add("ID taken")
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
        binding.tvStatTotal.text = "Routes: $total"
        binding.tvStatValid.text = "Valid: ${total - dups - errs}"
        binding.tvStatDuplicates.text = "Dups: $dups"
        binding.tvStatErrors.text = "Errs: $errs"
        
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
        arrayOf("Status", "Route ID", "Name", "Issues").forEach { 
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
            row.addView(TextView(this).apply { text = obj.optString("routeId"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("routeName"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("_issues"); setPadding(16, 12, 16, 12); setMaxLines(1) })
            binding.tablePreview.addView(row)
        }
    }

    private fun executeBatchUpload() {
        val toUpload = validatedData.filter { it.optString("_status") != "Error" }
        if (toUpload.isEmpty()) return

        lifecycleScope.launch {
            try {
                binding.layoutValidation.visibility = View.GONE
                binding.layoutProcessing.visibility = View.VISIBLE
                binding.linearProgress.visibility = View.VISIBLE
                binding.linearProgress.max = toUpload.size
                
                val importId = "ROUTE-BATCH-${System.currentTimeMillis()}"
                currentImportId = importId
                importedIds.clear()

                toUpload.chunked(500).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { route ->
                        val rid = route.optString("routeId")
                        val docRef = db.collection("routes").document(rid)
                        
                        val stopsArr = route.optJSONArray("stops")
                        val stopsList = mutableListOf<String>()
                        if (stopsArr != null) {
                            for (i in 0 until stopsArr.length()) {
                                val stopId = stopsArr.getString(i).trim()
                                if (stopId.isNotEmpty()) {
                                    stopsList.add(stopId)
                                }
                            }
                        }

                        val map = hashMapOf(
                            "routeId" to rid,
                            "routeName" to route.optString("routeName"),
                            "universityId" to route.optString("universityId"),
                            "startingLocationId" to route.optString("startingLocationId"),
                            "destinationId" to route.optString("destinationId"),
                            "stops" to stopsList,
                            "importId" to importId,
                            "createdAt" to Timestamp.now()
                        )
                        batch.set(docRef, map)
                        importedIds.add(rid)
                    }
                    batch.commit().await()
                    binding.linearProgress.progress = importedIds.size
                }

                updateStepIndicator(4)
                showCompletion(importedIds.size)
            } catch (e: Exception) {
                showSnackbar("Route network commit failed")
                binding.layoutProcessing.visibility = View.GONE
                updateStepIndicator(3)
            }
        }
    }

    private fun showCompletion(count: Int) {
        binding.layoutProcessing.visibility = View.GONE
        binding.cardCompletion.visibility = View.VISIBLE
        binding.tvImportSummary.text = "Registered $count service routes.\nBatch ID: $currentImportId"
    }

    private fun undoLastImport() {
        lifecycleScope.launch {
            try {
                binding.btnUndo.isEnabled = false
                val batch = db.batch()
                importedIds.forEach { batch.delete(db.collection("routes").document(it)) }
                batch.commit().await()
                Toast.makeText(this@ImportRoutesActivity, "Network Reverted", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                showSnackbar("Revert failed")
            }
        }
    }

    private fun showValidationError(msg: String) {
        showSnackbar(msg)
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
