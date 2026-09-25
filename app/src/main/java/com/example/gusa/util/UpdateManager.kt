package com.example.gusa.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.gusa.R
import com.example.gusa.model.UpdateInfo
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.lang.ref.WeakReference

/**
 * GUSA In-App Update Manager
 * 
 * Central controller for detecting, downloading, and installing updates.
 */
class UpdateManager(activity: Activity) {

    private val activityRef = WeakReference(activity)
    private val firestore = FirebaseFirestore.getInstance()
    private val downloader = UpdateDownloader(activity.applicationContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isDownloading = false

    /**
     * Checks Firestore for a newer version of the application.
     *
     * @param isManual Flag indicating if this was a user-initiated check.
     * @param onProceed Callback invoked if no update is found or needed.
     */
    fun checkForUpdate(isManual: Boolean = false, onProceed: () -> Unit = {}) {
        val activity = activityRef.get() ?: return
        
        scope.launch {
            try {
                // Check if Google Play Services are available before attempting Firestore calls
                val gmsApi = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                val gmsResult = gmsApi.isGooglePlayServicesAvailable(activity)
                if (gmsResult != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    Log.w("UpdateManager", "GMS not available (code $gmsResult). Skipping update check.")
                    onProceed()
                    return@launch
                }

                val installedVersionCode = getInstalledVersionCode(activity)
                Log.d("UpdateManager", "Installed Version Code: $installedVersionCode")
                
                val document = withContext(Dispatchers.IO) {
                    firestore.collection("app_updates")
                        .document("android")
                        .get()
                        .await()
                }

                if (!document.exists()) {
                    Log.d("UpdateManager", "No update info found in Firestore.")
                    if (isManual) Toast.makeText(activity, "You are using the latest version.", Toast.LENGTH_SHORT).show()
                    onProceed()
                    return@launch
                }

                val remoteVersionCode = document.getLong("versionCode") ?: 0L
                val versionName = document.getString("versionName") ?: "Unknown"
                val downloadUrl = document.getString("downloadUrl") ?: ""
                val forceUpdate = document.getBoolean("forceUpdate") ?: false
                
                @Suppress("UNCHECKED_CAST")
                val releaseNotes = (document["releaseNotes"] as? List<String>) ?: emptyList()

                Log.d("UpdateManager", "Remote Version Code: $remoteVersionCode")

                // Ensure the message of updating occurs ONLY when a strictly newer version is present
                if (remoteVersionCode > installedVersionCode && downloadUrl.isNotEmpty()) {
                    val updateInfo = UpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = versionName,
                        downloadUrl = downloadUrl,
                        forceUpdate = forceUpdate,
                        releaseNotes = releaseNotes,
                    )
                    showUpdateDialog(updateInfo, onProceed)
                } else {
                    Log.d("UpdateManager", "Application is up to date.")
                    if (isManual) {
                        Toast.makeText(activity, "GUSA $versionName is up to date.", Toast.LENGTH_SHORT).show()
                    }
                    onProceed()
                }
            } catch (e: Exception) {
                // Fix for SecurityException: Unknown calling package name 'com.google.android.gms'
                // This usually happens during Firestore/GMS authentication/verification
                if (e is SecurityException || e.message?.contains("SecurityException") == true) {
                    Log.e("UpdateManager", "GMS Security error during update check. Please verify package configuration.")
                } else {
                    Log.e("UpdateManager", "Check for update failed", e)
                }
                onProceed()
            }
        }
    }

    private fun getInstalledVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            PackageInfoCompat.getLongVersionCode(packageInfo)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to get installed version code", e)
            0L
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo, onProceed: () -> Unit) {
        val activity = activityRef.get() ?: return
        
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvVersion)
        val tvReleaseNotes = dialogView.findViewById<TextView>(R.id.tvReleaseNotes)
        val layoutProgress = dialogView.findViewById<LinearLayout>(R.id.layoutProgress)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvProgress)
        val btnLater = dialogView.findViewById<Button>(R.id.btnLater)
        val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdate)
        val layoutButtons = dialogView.findViewById<LinearLayout>(R.id.layoutButtons)

        tvVersion.text = "GUSA ${updateInfo.versionName} is available."
        
        if (updateInfo.releaseNotes.isNotEmpty()) {
            tvReleaseNotes.text = updateInfo.releaseNotes.joinToString("\n") { "• $it" }
        } else {
            dialogView.findViewById<View>(R.id.tvReleaseNotesLabel).visibility = View.GONE
            tvReleaseNotes.visibility = View.GONE
        }

        if (updateInfo.forceUpdate) {
            btnLater.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(!updateInfo.forceUpdate)
            .create()

        btnLater.setOnClickListener {
            dialog.dismiss()
            onProceed()
        }

        btnUpdate.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            
            startDownload(updateInfo, dialog, layoutProgress, progressBar, tvProgress, layoutButtons)
        }

        dialog.show()
    }

    private fun startDownload(
        updateInfo: UpdateInfo,
        dialog: AlertDialog,
        layoutProgress: LinearLayout,
        progressBar: ProgressBar,
        tvProgress: TextView,
        layoutButtons: LinearLayout
    ) {
        val activity = activityRef.get() ?: return
        isDownloading = true
        
        layoutProgress.visibility = View.VISIBLE
        layoutButtons.visibility = View.GONE
        dialog.setCancelable(false)

        scope.launch {
            val fileName = "gusa_v${updateInfo.versionName}.apk"
            downloader.downloadApk(updateInfo.downloadUrl, fileName).collect { status ->
                when (status) {
                    is UpdateDownloader.DownloadStatus.Progress -> {
                        progressBar.progress = status.percentage
                        tvProgress.text = "${status.percentage}%"
                    }
                    is UpdateDownloader.DownloadStatus.Completed -> {
                        isDownloading = false
                        installApk(activity, status.file)
                        dialog.dismiss()
                    }
                    is UpdateDownloader.DownloadStatus.Error -> {
                        isDownloading = false
                        Log.e("UpdateManager", "Download error: ${status.message}")
                        Toast.makeText(activity, "Download failed: ${status.message}", Toast.LENGTH_LONG).show()
                        
                        if (!updateInfo.forceUpdate) {
                            dialog.dismiss()
                        } else {
                            // Allow retry for forced update
                            layoutProgress.visibility = View.GONE
                            layoutButtons.visibility = View.VISIBLE
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun installApk(activity: Activity, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Log.w("UpdateManager", "Cannot request package installs. Redirecting to settings.")
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    Toast.makeText(activity, "Please enable installation from this source and try again.", Toast.LENGTH_LONG).show()
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                activity, 
                "${activity.packageName}.fileprovider", 
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            activity.startActivity(intent)
            
        } catch (e: Exception) {
            Log.e("UpdateManager", "Installation failed", e)
            Toast.makeText(activity, "Failed to start installation. Check permissions.", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Call this in Activity.onDestroy() to prevent leaks and cancel pending operations.
     */
    fun onDestroy() {
        scope.cancel()
    }
}
