package com.example.gusa

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityReportsBinding
import com.example.gusa.service.GeminiService
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.firebase.firestore.FirebaseFirestore
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportsActivity : FragmentActivity() {

    private lateinit var binding: ActivityReportsBinding
    private val db = FirebaseFirestore.getInstance()
    private val loadedLogs = mutableListOf<LogEntry>()

    data class LogEntry(val date: String, val action: String, val adminId: String, val description: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupExportButtons()
        setupAiSummary()
        loadAuditLogs()
        setupChart()
    }

    private fun setupChart() {
        val entries = ArrayList<BarEntry>()
        entries.add(BarEntry(0f, 45f)) // Mon
        entries.add(BarEntry(1f, 80f)) // Tue
        entries.add(BarEntry(2f, 65f)) // Wed
        entries.add(BarEntry(3f, 38f)) // Thu
        entries.add(BarEntry(4f, 92f)) // Fri
        entries.add(BarEntry(5f, 20f)) // Sat
        entries.add(BarEntry(6f, 15f)) // Sun

        val dataSet = BarDataSet(entries, "System Usage (App Launches)")
        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
        dataSet.valueTextSize = 12f

        val barData = BarData(dataSet)
        binding.usageChart.data = barData
        binding.usageChart.description.isEnabled = false
        
        val xAxis = binding.usageChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        val days = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return days.getOrElse(value.toInt()) { "" }
            }
        }
        xAxis.granularity = 1f

        binding.usageChart.animateY(1000)
        binding.usageChart.invalidate()
    }

    private fun setupAiSummary() {
        binding.btnSummarizeLogs.setOnClickListener {
            if (loadedLogs.isEmpty()) {
                Toast.makeText(this, "No logs loaded yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                binding.btnSummarizeLogs.isEnabled = false
                binding.tvAiLogSummary.text = "Summarizing recent logs..."
                try {
                    val logStrings = loadedLogs.map { "${it.date}: ${it.action} by ${it.adminId}" }
                    val summary = GeminiService.summarizeLogs(logStrings)
                    binding.tvAiLogSummary.text = summary
                } catch (e: Exception) {
                    binding.tvAiLogSummary.text = "Error: ${e.message}"
                } finally {
                    binding.btnSummarizeLogs.isEnabled = true
                }
            }
        }
    }

    private fun setupExportButtons() {
        val clickListener = View.OnClickListener { view ->
            val format = when {
                binding.rbPdf.isChecked -> "PDF"
                binding.rbCsv.isChecked -> "CSV"
                binding.rbExcel.isChecked -> "Excel"
                else -> "PDF"
            }
            
            val reportName = when (view.id) {
                R.id.btnExportDaily -> "Daily Report"
                R.id.btnExportWeekly -> "Weekly Report"
                R.id.btnExportMonthly -> "Monthly Report"
                R.id.btnExportStudents -> "Student Report"
                R.id.btnExportDrivers -> "Driver Report"
                R.id.btnExportTrips -> "Trip Report"
                R.id.btnExportBuses -> "Bus Report"
                else -> "Report"
            }
            
            if (format == "PDF") {
                exportToPdf(reportName)
            } else {
                Toast.makeText(this, "Only PDF export is fully implemented for cross-platform support.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExportDaily.setOnClickListener(clickListener)
        binding.btnExportWeekly.setOnClickListener(clickListener)
        binding.btnExportMonthly.setOnClickListener(clickListener)
        binding.btnExportStudents.setOnClickListener(clickListener)
        binding.btnExportDrivers.setOnClickListener(clickListener)
        binding.btnExportTrips.setOnClickListener(clickListener)
        binding.btnExportBuses.setOnClickListener(clickListener)
    }

    private fun exportToPdf(reportName: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ReportsActivity, "Generating PDF...", Toast.LENGTH_SHORT).show()
                
                val uri = withContext(Dispatchers.IO) {
                    createPdfFile(reportName)
                }

                if (uri != null) {
                    Toast.makeText(this@ReportsActivity, "PDF saved to Downloads folder", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ReportsActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ReportsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }

    private fun createPdfFile(reportName: String): Uri? {
        val fileName = "${reportName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentResolver = applicationContext.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues) ?: return null
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                generatePdfContent(outputStream, reportName)
            }
            uri
        } else {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            FileOutputStream(file).use { outputStream ->
                generatePdfContent(outputStream, reportName)
            }
            Uri.fromFile(file)
        }
    }

    private fun generatePdfContent(outputStream: OutputStream, reportName: String) {
        val writer = PdfWriter(outputStream)
        val pdf = PdfDocument(writer)
        val document = Document(pdf)

        // 1. Header
        document.add(Paragraph("Notify Admin System - $reportName")
            .setTextAlignment(TextAlignment.CENTER)
            .setFontSize(20f))
        
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        document.add(Paragraph("Generated on: ${sdf.format(Date())}")
            .setTextAlignment(TextAlignment.RIGHT)
            .setFontSize(10f))
        
        document.add(Paragraph("\n"))

        // 2. Usage Graph
        document.add(Paragraph("Usage Analytics").setFontSize(16f))
        val chartBitmap = viewToBitmap(binding.usageChart)
        val stream = ByteArrayOutputStream()
        chartBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val chartImage = Image(ImageDataFactory.create(stream.toByteArray()))
        chartImage.setMaxWidth(500f)
        document.add(chartImage)
        
        document.add(Paragraph("\n"))

        // 3. Audit Logs Table
        document.add(Paragraph("Recent System Logs").setFontSize(16f))
        val table = Table(floatArrayOf(150f, 100f, 100f, 200f))
        table.addHeaderCell("Date")
        table.addHeaderCell("Action")
        table.addHeaderCell("Admin")
        table.addHeaderCell("Description")

        for (log in loadedLogs.take(50)) {
            table.addCell(log.date)
            table.addCell(log.action)
            table.addCell(log.adminId)
            table.addCell(log.description)
        }
        
        document.add(table)

        document.close()
    }

    private fun viewToBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun loadAuditLogs() {
        lifecycleScope.launch {
            try {
                // If it's a trip report, we might want to load from tripHistory instead
                // For now, let's keep the general audit log load but add context
                val snapshot = db.collection("auditLogs")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(50)
                    .get()
                    .await()
                
                binding.llAuditLogs.removeAllViews()
                loadedLogs.clear()
                
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                
                if (snapshot.isEmpty) {
                    val tv = TextView(this@ReportsActivity).apply {
                        text = "No audit logs found."
                        setPadding(16, 16, 16, 16)
                    }
                    binding.llAuditLogs.addView(tv)
                    return@launch
                }

                for (doc in snapshot.documents) {
                    val action = doc.getString("action") ?: "Action"
                    val desc = doc.getString("description") ?: ""
                    val adminId = doc.getString("adminId") ?: "Admin"
                    val timestamp = doc.getTimestamp("createdAt")?.toDate() ?: Date()
                    val dateString = sdf.format(timestamp)

                    loadedLogs.add(LogEntry(dateString, action, adminId, desc))

                    val logText = "[$dateString] $action by $adminId"
                    val tv = TextView(this@ReportsActivity).apply {
                        text = logText
                        setPadding(16, 24, 16, 8)
                        textSize = 14f
                        setTextColor(resources.getColor(android.R.color.black, null))
                    }
                    binding.llAuditLogs.addView(tv)
                    
                    val descTv = TextView(this@ReportsActivity).apply {
                        text = desc
                        setPadding(16, 0, 16, 24)
                        textSize = 12f
                        setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    }
                    binding.llAuditLogs.addView(descTv)

                    val divider = View(this@ReportsActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                    }
                    binding.llAuditLogs.addView(divider)
                }

            } catch (e: Exception) {
                val tv = TextView(this@ReportsActivity).apply {
                    text = "Failed to load logs: ${e.message}"
                    setPadding(16, 16, 16, 16)
                    setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
                binding.llAuditLogs.addView(tv)
            }
        }
    }
}
