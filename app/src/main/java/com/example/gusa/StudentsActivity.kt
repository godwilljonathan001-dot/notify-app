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
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.gusa.databinding.ActivityStudentsBinding
import com.example.gusa.databinding.ItemStudentBinding
import com.example.gusa.model.Student
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class StudentsActivity : FragmentActivity() {

    private lateinit var binding: ActivityStudentsBinding
    private val db = FirebaseFirestore.getInstance()
    private var studentsListener: ListenerRegistration? = null
    
    private val allStudents = mutableListOf<Student>()
    private val filteredStudents = mutableListOf<Student>()
    private lateinit var studentsAdapter: StudentsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupListeners()
        listenToStudents()
    }

    private fun setupRecyclerView() {
        studentsAdapter = StudentsAdapter(filteredStudents) { student ->
            showManageStudentDialog(student)
        }
        binding.rvStudents.apply {
            layoutManager = LinearLayoutManager(this@StudentsActivity)
            adapter = studentsAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterStudents(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterStudents(query: String) {
        filteredStudents.clear()
        if (query.isEmpty()) {
            filteredStudents.addAll(allStudents)
        } else {
            val lowerQuery = query.lowercase()
            allStudents.filter { 
                it.studentName.lowercase().contains(lowerQuery) || 
                it.studentId.toString().lowercase().contains(lowerQuery) ||
                it.email.lowercase().contains(lowerQuery)
            }.let { filteredStudents.addAll(it) }
        }
        studentsAdapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnAddStudent.setOnClickListener {
            startActivity(Intent(this, ImportStudentsActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            listenToStudents()
        }

        binding.chipFilter.setOnClickListener {
            showFilterDialog()
        }

        binding.btnRefreshEmpty.setOnClickListener {
            listenToStudents()
        }
    }

    private fun listenToStudents() {
        binding.swipeRefresh.isRefreshing = true
        studentsListener?.remove()
        studentsListener = db.collection("students")
            .addSnapshotListener { snapshot, e ->
                binding.swipeRefresh.isRefreshing = false
                if (e != null || snapshot == null) {
                    Toast.makeText(this, "Error loading students", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                allStudents.clear()
                snapshot.documents.forEach { doc ->
                    doc.toObject(Student::class.java)?.let { allStudents.add(it) }
                }
                
                updateStats()
                filterStudents(binding.etSearch.text.toString())
            }
    }

    private fun updateStats() {
        binding.chipTotalStudents.text = "Total: ${allStudents.size}"
        binding.chipBoardedStudents.text = "Boarded: ${allStudents.count { it.status == "boarded" }}"
        binding.chipWaitingStudents.text = "Waiting: ${allStudents.count { it.status == "waiting" }}"
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (filteredStudents.isEmpty()) View.VISIBLE else View.GONE
        binding.rvStudents.visibility = if (filteredStudents.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showFilterDialog() {
        val options = arrayOf("Show All", "Boarded Only", "Waiting Only")
        MaterialAlertDialogBuilder(this)
            .setTitle("Filter Students")
            .setItems(options) { _, which ->
                filteredStudents.clear()
                when (which) {
                    0 -> filteredStudents.addAll(allStudents)
                    1 -> filteredStudents.addAll(allStudents.filter { it.status == "boarded" })
                    2 -> filteredStudents.addAll(allStudents.filter { it.status == "waiting" })
                }
                studentsAdapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .show()
    }

    private fun showManageStudentDialog(student: Student) {
        val options = arrayOf("Approve Student", "Edit Information", "Reject/Delete Student", "Assign to Route", "View History")
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage ${student.studentName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> approveStudent(student)
                    1 -> showEditStudentDialog(student)
                    2 -> confirmDeleteStudent(student)
                    3 -> Toast.makeText(this, "Route assignment coming soon", Toast.LENGTH_SHORT).show()
                    4 -> Toast.makeText(this, "History feature coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showEditStudentDialog(student: Student) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val etName = EditText(this).apply { 
            hint = "Full Name"
            setText(student.studentName)
        }
        val etUni = EditText(this).apply { 
            hint = "University"
            setText(student.universityId?.toString() ?: "")
        }
        val etRoute = EditText(this).apply { 
            hint = "Route ID"
            setText(student.routeId?.toString() ?: "")
        }
        val etBus = EditText(this).apply { 
            hint = "Bus ID"
            setText(student.busId?.toString() ?: "")
        }

        layout.addView(etName)
        layout.addView(etUni)
        layout.addView(etRoute)
        layout.addView(etBus)

        AlertDialog.Builder(this)
            .setTitle("Edit ${student.studentName}")
            .setView(layout)
            .setPositiveButton("Save Changes") { _, _ ->
                val updates = hashMapOf<String, Any>(
                    "studentName" to etName.text.toString().trim(),
                    "universityId" to etUni.text.toString().trim(),
                    "routeId" to etRoute.text.toString().trim(),
                    "busId" to etBus.text.toString().trim()
                )
                
                lifecycleScope.launch {
                    try {
                        db.collection("students").document(student.studentUID).update(updates).await()
                        Toast.makeText(this@StudentsActivity, "Student updated", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@StudentsActivity, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveStudent(student: Student) {
        lifecycleScope.launch {
            try {
                // Assuming "approved" is a field or status
                db.collection("students").document(student.studentUID).update("status", "waiting").await()
                Toast.makeText(this@StudentsActivity, "${student.studentName} Approved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@StudentsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteStudent(student: Student) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Student")
            .setMessage("Are you sure you want to delete ${student.studentName}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.collection("students").document(student.studentUID).delete().await()
                        Toast.makeText(this@StudentsActivity, "Student deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@StudentsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        studentsListener?.remove()
        super.onDestroy()
    }

    private inner class StudentsAdapter(
        private val students: List<Student>,
        private val onManageClick: (Student) -> Unit
    ) : RecyclerView.Adapter<StudentsAdapter.StudentViewHolder>() {

        inner class StudentViewHolder(val itemBinding: ItemStudentBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentViewHolder {
            val b = ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return StudentViewHolder(b)
        }

        override fun onBindViewHolder(holder: StudentViewHolder, position: Int) {
            val student = students[position]
            holder.itemBinding.apply {
                tvStudentName.text = student.studentName
                tvStudentReg.text = "Reg: ${student.studentId}"
                tvStudentStatus.text = student.status.replaceFirstChar { it.uppercase() }
                tvPickupStop.text = if (student.pickupStation?.toString()?.isNotEmpty() == true) student.pickupStation.toString() else "Not Set"
                tvStudentRoute.text = if (student.routeId?.toString()?.isNotEmpty() == true) student.routeId.toString() else "Not Assigned"
                tvStudentUni.text = student.universityId?.toString() ?: "Not Specified"

                // Dynamic Status Badge
                when (student.status) {
                    "boarded" -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
                        tvStudentStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    }
                    "waiting" -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                        tvStudentStatus.setTextColor(android.graphics.Color.parseColor("#EF6C00"))
                    }
                    else -> {
                        cardStatusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                        tvStudentStatus.setTextColor(android.graphics.Color.parseColor("#757575"))
                    }
                }

                btnManageStudent.setOnClickListener { onManageClick(student) }
            }
        }

        override fun getItemCount() = students.size
    }
}
