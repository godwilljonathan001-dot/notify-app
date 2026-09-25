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
import com.example.gusa.databinding.ActivityImportstudentsBinding
import com.example.gusa.service.GeminiService
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class ImportStudentsActivity : FragmentActivity() {
    private lateinit var binding: ActivityImportstudentsBinding
    private val db = FirebaseFirestore.getInstance()

    private var validatedData = mutableListOf<JSONObject>()
    private var currentImportId: String? = null
    private var importedIds = mutableListOf<String>()

    private enum class ImportMode { MANUAL, AI, RAW }
    private var currentMode = ImportMode.AI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.gusa.util.ThemeHelper.applySavedTheme(this)
        binding = ActivityImportstudentsBinding.inflate(layoutInflater)
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
            binding.tvAiSubTitle.text = "Paste structured text (CSV, Tabular, etc)"
            binding.etRawData.hint = "Paste student records here..."
            binding.btnValidate.text = "Validate Records"
        } else if (mode == ImportMode.AI) {
            binding.tvAiTitle.text = "AI Assisted Import"
            binding.tvAiSubTitle.text = "Powered by Gemini 1.5 Flash"
            binding.etRawData.hint = "Paste unstructured text (Emails, Lists, etc)..."
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
                binding.spinnerManualUni.setAdapter(ArrayAdapter(this@ImportStudentsActivity, android.R.layout.simple_dropdown_item_1line, unis))

                val routes = db.collection("routes").get().await().documents.map { it.id }
                binding.spinnerManualRoute.setAdapter(ArrayAdapter(this@ImportStudentsActivity, android.R.layout.simple_dropdown_item_1line, routes))

                val buses = db.collection("buses").get().await().documents.map { it.id }
                binding.spinnerManualBus.setAdapter(ArrayAdapter(this@ImportStudentsActivity, android.R.layout.simple_dropdown_item_1line, buses))

                val stops = db.collection("stops").get().await().documents.map { it.id }
                binding.spinnerManualStop.setAdapter(ArrayAdapter(this@ImportStudentsActivity, android.R.layout.simple_dropdown_item_1line, stops))
            } catch (e: Exception) {
                Toast.makeText(this@ImportStudentsActivity, "Failed to sync system catalogs", Toast.LENGTH_SHORT).show()
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
                showSnackbar("Please provide input data")
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
        val studentId = binding.etManualStudentId.text.toString().trim()
        val name = binding.etManualName.text.toString().trim()
        val email = binding.etManualEmail.text.toString().trim().lowercase()
        
        if (studentId.isEmpty() || name.isEmpty() || email.isEmpty()) {
            showSnackbar("ID, Name, and Email are mandatory fields")
            return
        }

        val obj = JSONObject().apply {
            put("studentId", studentId)
            put("studentName", name)
            put("email", email)
            put("universityId", binding.spinnerManualUni.text.toString().trim())
            put("routeId", binding.spinnerManualRoute.text.toString().trim())
            put("pickupStation", binding.spinnerManualStop.text.toString().trim())
            put("busId", binding.spinnerManualBus.text.toString().trim())
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
                updateProgress(if (currentMode == ImportMode.AI) "AI Analysis in progress..." else "Parsing raw text...")
                val responseStr = GeminiService.parseImportData(rawText, "students")
                
                if (responseStr.startsWith("Error")) throw Exception(responseStr)

                val responseJson = JSONObject(responseStr)
                val data = responseJson.optJSONArray("data") ?: JSONArray()
                
                updateProgress("Cross-referencing Firestore...")
                validateParsedData(data)
                updateStepIndicator(3)
            } catch (exception: Exception) {
                showValidationError(exception.message ?: "Processing Error")
                updateStepIndicator(1)
            } finally {
                setValidationState(isProcessing = false)
            }
        }
    }

    private suspend fun validateParsedData(data: JSONArray) {
        validatedData.clear()
        var dupCount = 0
        var errCount = 0

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val sid = obj.optString("studentId")
            val email = obj.optString("email").trim().lowercase()
            
            var status = "Ready"
            val issues = mutableListOf<String>()

            if (sid.isEmpty()) {
                status = "Error"
                issues.add("Missing ID")
                errCount++
            } else if (email.isEmpty()) {
                status = "Error"
                issues.add("Missing Email")
                errCount++
            } else {
                val existingDoc = db.collection("students").document(email).get().await()
                if (existingDoc.exists()) {
                    status = "Duplicate"
                    issues.add("Whitelisted")
                    dupCount++
                } else {
                    val existingById = db.collection("students").whereEqualTo("studentId", sid).get().await()
                    if (!existingById.isEmpty) {
                        status = "Duplicate"
                        issues.add("ID Exists")
                        dupCount++
                    }
                }
                
                // Cross-reference Bus
                val busId = obj.optString("busId")
                if (busId.isNotEmpty()) {
                    val busDoc = db.collection("buses").document(busId).get().await()
                    if (!busDoc.exists()) {
                        issues.add("Bus ID invalid")
                        if (status == "Ready") status = "Warning"
                    }
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
        binding.tvStatTotal.text = "Records: $total"
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
        arrayOf("Status", "Reg ID", "Name", "Info").forEach { 
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
            row.addView(TextView(this).apply { text = obj.optString("studentId"); setPadding(16, 12, 16, 12) })
            row.addView(TextView(this).apply { text = obj.optString("studentName"); setPadding(16, 12, 16, 12) })
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
                
                val importId = "STU-BATCH-${System.currentTimeMillis()}"
                currentImportId = importId
                importedIds.clear()

                toUpload.chunked(500).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { stu ->
                        val email = stu.optString("email").trim().lowercase()
                        if (email.isNotEmpty()) {
                            val docRef = db.collection("students").document(email)
                            
                            val map = hashMapOf(
                                "studentUID" to "",
                                "studentId" to stu.optString("studentId"),
                                "studentName" to stu.optString("studentName"),
                                "email" to email,
                                "universityId" to stu.optString("universityId"),
                                "routeId" to stu.optString("routeId"),
                                "pickupStation" to stu.optString("pickupStation"),
                                "busId" to stu.optString("busId"),
                                "status" to "waiting",
                                "isRegistered" to false,
                                "latitude" to 0.0,
                                "longitude" to 0.0,
                                "geoPoint" to com.google.firebase.firestore.GeoPoint(0.0, 0.0),
                                "importId" to importId,
                                "createdAt" to Timestamp.now()
                            )
                            batch.set(docRef, map)
                            importedIds.add(email)
                        }
                    }
                    batch.commit().await()
                    binding.linearProgress.progress = importedIds.size
                }

                updateStepIndicator(4)
                showCompletion(importedIds.size)
            } catch (e: Exception) {
                showSnackbar("Batch upload failed: ${e.message}")
                binding.layoutProcessing.visibility = View.GONE
                updateStepIndicator(3)
            }
        }
    }

    private fun showCompletion(count: Int) {
        binding.layoutProcessing.visibility = View.GONE
        binding.cardCompletion.visibility = View.VISIBLE
        binding.tvImportSummary.text = "Provisioned $count students.\nID: $currentImportId"
    }

    private fun undoLastImport() {
        lifecycleScope.launch {
            try {
                binding.btnUndo.isEnabled = false
                val batch = db.batch()
                importedIds.forEach { batch.delete(db.collection("students").document(it)) }
                batch.commit().await()
                Toast.makeText(this@ImportStudentsActivity, "Import reverted", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                showSnackbar("Undo failed")
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
