package com.example.gusa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.gusa.util.UpdateManager
import com.example.gusa.util.ThemeHelper

class MainActivity : FragmentActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var updateManager: UpdateManager

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkUpdate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        setContentView(R.layout.activity_main)
        updateManager = UpdateManager(this)
        
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) launcher.launch(perms.toTypedArray()) else checkUpdate()
    }

    private fun checkUpdate() {
        updateManager.checkForUpdate {
            checkUserStatus()
        }
    }

    private fun checkUserStatus() = auth.currentUser?.let { routeUser(it.uid) } ?: navToLogin()

    override fun onDestroy() {
        updateManager.onDestroy()
        super.onDestroy()
    }

    private fun navToLogin() { startActivity(Intent(this, LoginActivity::class.java)); finish() }

    private fun routeUser(uid: String) = lifecycleScope.launch {
        try {
            db.collection("admins").document(uid).get().await().takeIf { it.exists() }?.let {
                startActivity(Intent(this@MainActivity, AdminActivity::class.java).apply { 
                    putExtra("ADMIN_ID", it.getString("adminId") ?: uid)
                    putExtra("USER_UID", uid) 
                })
            } ?: db.collection("drivers").document(uid).get().await().takeIf { it.exists() }?.let {
                startActivity(Intent(this@MainActivity, DriverActivity::class.java).apply { 
                    putExtra("DRIVER_ID", it.getString("driverId") ?: uid)
                    putExtra("USER_UID", uid)
                })
            } ?: db.collection("students").document(uid).get().await().takeIf { it.exists() }?.let {
                startActivity(Intent(this@MainActivity, StudentActivity::class.java).apply { 
                    putExtra("STUDENT_ID", it.getString("studentId") ?: uid)
                    putExtra("USER_UID", uid) 
                })
            } ?: run {
                Log.w("MainActivity", "User $uid not found in any collection")
                auth.signOut()
                navToLogin()
            }
            finish()
        } catch (e: Exception) { Log.e("MainActivity", "Routing error", e); auth.signOut(); navToLogin() }
    }
}
