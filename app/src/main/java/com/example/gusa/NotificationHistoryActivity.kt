package com.example.gusa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gusa.databinding.ActivityNotificationHistoryBinding
import com.example.gusa.model.Notification
import com.example.gusa.util.ThemeHelper
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

class NotificationHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationHistoryBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val adapter = NotificationAdapter()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notifications Enabled", Toast.LENGTH_SHORT).show()
            binding.cardPermission.visibility = View.GONE
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ThemeHelper.applySavedTheme(this)
            binding = ActivityNotificationHistoryBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupUI()
            fetchHistory()
            checkNotificationPermission()
        } catch (e: Exception) {
            Log.e("NotifHistory", "Critical error in onCreate", e)
            Toast.makeText(this, "Something went wrong. Please try again.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_delete_history) {
                confirmClearLocalHistory()
                true
            } else false
        }
        
        binding.rvNotificationHistory.layoutManager = LinearLayoutManager(this)
        binding.rvNotificationHistory.adapter = adapter

        binding.btnEnableNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Notifications are already enabled in settings", Toast.LENGTH_SHORT).show()
                binding.cardPermission.visibility = View.GONE
            }
        }
    }

    private fun confirmClearLocalHistory() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Clear Bus History?")
            .setMessage("This will only delete the history on your phone. It will not affect the official records.")
            .setPositiveButton("Clear Locally") { _, _ ->
                clearLocalHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearLocalHistory() {
        val prefs = getSharedPreferences("BUS_HISTORY_LOCAL", MODE_PRIVATE)
        val deletedIds = prefs.getStringSet("DELETED_NOTIF_IDS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        // We add all current notification IDs to the "deleted" set
        adapter.getCurrentList().forEach {
            deletedIds.add(it.notificationId)
        }
        
        prefs.edit().putStringSet("DELETED_NOTIF_IDS", deletedIds).apply()
        
        // Refresh UI
        fetchHistory()
        Toast.makeText(this, "Local history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun deleteOneByOne(id: String) {
        val prefs = getSharedPreferences("BUS_HISTORY_LOCAL", MODE_PRIVATE)
        val deletedIds = prefs.getStringSet("DELETED_NOTIF_IDS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        deletedIds.add(id)
        
        prefs.edit().putStringSet("DELETED_NOTIF_IDS", deletedIds).apply()
        
        // Refresh UI
        fetchHistory()
        Toast.makeText(this, "Item removed locally", Toast.LENGTH_SHORT).show()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                binding.cardPermission.visibility = View.GONE
            } else {
                binding.cardPermission.visibility = View.VISIBLE
            }
        } else {
            binding.cardPermission.visibility = View.GONE
        }
    }

    private fun fetchHistory() {
        val currentUser = auth.currentUser ?: return
        binding.progressBar.visibility = View.VISIBLE
        
        val prefs = getSharedPreferences("BUS_HISTORY_LOCAL", MODE_PRIVATE)
        val deletedIds = prefs.getStringSet("DELETED_NOTIF_IDS", emptySet()) ?: emptySet()

        try {
            db.collection("notifications")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .addOnSuccessListener { snapshot ->
                    binding.progressBar.visibility = View.GONE
                    val notifications = mutableListOf<Notification>()
                    for (doc in snapshot.documents) {
                        try {
                            if (deletedIds.contains(doc.id)) continue
                            
                            val notif = doc.toObject(Notification::class.java)
                            if (notif != null) {
                                // Ensure notification has the doc ID for deletion tracking
                                val finalNotif = if (notif.notificationId.isEmpty()) {
                                    notif.copy(notificationId = doc.id)
                                } else notif
                                notifications.add(finalNotif)
                            }
                        } catch (e: Exception) {
                            Log.e("NotifHistory", "Error parsing notification", e)
                        }
                    }
                    
                    if (notifications.isEmpty()) {
                        binding.tvEmptyState.visibility = View.VISIBLE
                        adapter.submitList(emptyList())
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                        adapter.submitList(notifications)
                    }
                }
                .addOnFailureListener { e ->
                    binding.progressBar.visibility = View.GONE
                    Log.e("NotifHistory", "Error fetching history", e)
                    binding.tvEmptyState.text = "Error loading history"
                    binding.tvEmptyState.visibility = View.VISIBLE
                }
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            Log.e("NotifHistory", "Error in fetchHistory", e)
        }
    }

    inner class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {
        private var list: List<Notification> = emptyList()

        fun submitList(newList: List<Notification>) {
            list = newList
            notifyDataSetChanged()
        }

        fun getCurrentList(): List<Notification> = list

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.bind(item)
        }

        override fun getItemCount(): Int = list.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvNotifTitle)
            val message: TextView = view.findViewById(R.id.tvNotifMessage)
            val time: TextView = view.findViewById(R.id.tvNotifTime)
            val icon: ImageView = view.findViewById(R.id.ivTypeIcon)
            val btnDelete: View = view.findViewById(R.id.btnDeleteNotif)

            fun bind(item: Notification) {
                try {
                    title.text = item.title
                    message.text = item.message
                    
                    btnDelete.setOnClickListener {
                        deleteOneByOne(item.notificationId)
                    }
                    
                    val timestamp = item.createdAt as? Timestamp
                    if (timestamp != null) {
                        val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                        time.text = sdf.format(timestamp.toDate())
                    } else {
                        time.text = "Recent"
                    }

                    // Dynamic Icon selection based on content
                    when {
                        item.title.contains("Arrived", true) -> {
                            icon.setImageResource(R.drawable.ic_notifications)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                        }
                        item.title.contains("Passed", true) || item.message.contains("Passed", true) -> {
                            icon.setImageResource(android.R.drawable.ic_dialog_alert)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                        }
                        item.title.contains("Boarded", true) || item.title.contains("Boarding", true) -> {
                            icon.setImageResource(android.R.drawable.ic_menu_directions)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.status_green))
                        }
                        else -> {
                            icon.setImageResource(R.drawable.ic_notifications)
                            icon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.status_blue))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NotifAdapter", "Error binding notification", e)
                }
            }
        }
    }
}
