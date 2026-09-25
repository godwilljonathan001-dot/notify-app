package com.example.gusa.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data model for GUSA In-App Update Information
 */
@Parcelize
data class UpdateInfo(
    val versionCode: Long = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val forceUpdate: Boolean = false,
    val releaseNotes: List<String> = emptyList()
) : Parcelable
