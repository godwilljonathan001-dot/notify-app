package com.example.gusa

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gusa.databinding.ActivityUniversityBinding
import com.example.gusa.databinding.ItemUniversityBinding
import com.example.gusa.model.University
import com.example.gusa.util.ThemeHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class UniversityActivity : FragmentActivity() {

    private lateinit var binding: ActivityUniversityBinding
    private val db = FirebaseFirestore.getInstance()
    private val universities = mutableListOf<University>()
    private lateinit var adapter: UniversityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityUniversityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadUniversities()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        adapter = UniversityAdapter(universities, 
            onEdit = { uni -> editUniversity(uni) },
            onDelete = { uni -> confirmDeletion(uni) }
        )
        
        binding.rvUniversities.layoutManager = LinearLayoutManager(this)
        binding.rvUniversities.adapter = adapter
        
        binding.fabAddUniversity.setOnClickListener {
            startActivity(Intent(this, AddLocationActivity::class.java))
        }
    }

    private fun loadUniversities() {
        db.collection("universities").addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null) return@addSnapshotListener
            
            universities.clear()
            snapshot.documents.forEach { doc ->
                doc.toObject(University::class.java)?.let { universities.add(it) }
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun editUniversity(uni: University) {
        // For simplicity in this refinement, we reuse AddLocationActivity for both
        // In a full implementation, we'd pass the existing ID for editing
        Toast.makeText(this, "Editing ${uni.name}", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, AddLocationActivity::class.java).apply {
            putExtra("LOCATION_ID", uni.universityId.toString())
            putExtra("TYPE", "university")
        }
        startActivity(intent)
    }

    private fun confirmDeletion(uni: University) {
        lifecycleScope.launch {
            try {
                // Relationship check
                val students = db.collection("students").whereEqualTo("universityId", uni.universityId).limit(1).get().await()
                val drivers = db.collection("drivers").whereEqualTo("universityId", uni.universityId).limit(1).get().await()
                
                val hasDependents = !students.isEmpty || !drivers.isEmpty
                
                val message = if (hasDependents) {
                    "Warning: This university has active students or drivers assigned to it. Deleting it may cause system issues. Proceed anyway?"
                } else {
                    "Are you sure you want to delete ${uni.name}?"
                }

                MaterialAlertDialogBuilder(this@UniversityActivity)
                    .setTitle("Delete University")
                    .setMessage(message)
                    .setPositiveButton("Delete") { _, _ -> deleteUni(uni) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@UniversityActivity, "Error checking relationships", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteUni(uni: University) {
        db.collection("universities").document(uni.universityId.toString()).delete()
            .addOnSuccessListener { Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show() }
    }

    inner class UniversityAdapter(
        private val list: List<University>,
        private val onEdit: (University) -> Unit,
        private val onDelete: (University) -> Unit
    ) : RecyclerView.Adapter<UniversityAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemUniversityBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemUniversityBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uni = list[position]
            holder.itemBinding.apply {
                tvUniName.text = uni.name
                tvUniLocation.text = uni.location.ifEmpty { "${uni.latitude}, ${uni.longitude}" }
                tvUniID.text = "ID: ${uni.universityId}"
                
                // Static labels for refinement demo, in real app these would be live counts
                tvUniBuses.text = "Buses: --"
                tvUniStudents.text = "Students: --"
                tvUniRoutes.text = "Routes: --"
                
                btnEditUni.setOnClickListener { onEdit(uni) }
                btnDeleteUni.setOnClickListener { onDelete(uni) }
            }
        }

        override fun getItemCount() = list.size
    }
}
