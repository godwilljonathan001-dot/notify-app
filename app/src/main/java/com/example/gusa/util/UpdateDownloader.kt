package com.example.gusa.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Responsible for downloading the APK and reporting progress.
 */
class UpdateDownloader(private val context: Context) {

    private val client = OkHttpClient()

    /**
     * Downloads the APK from the given URL and emits progress.
     */
    fun downloadApk(url: String, fileName: String): Flow<DownloadStatus> = flow {
        try {
            emit(DownloadStatus.Started)

            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadStatus.Error("Failed to download: ${response.code}"))
                    return@use
                }

                val body = response.body
                if (body == null) {
                    emit(DownloadStatus.Error("Empty response body"))
                    return@use
                }

                val length = body.contentLength()
                val inputStream: InputStream = body.byteStream()
                
                // Save to external files dir that FileProvider can access
                val destinationDir = File(context.getExternalFilesDir(null), "updates")
                if (!destinationDir.exists()) {
                    destinationDir.mkdirs()
                }
                
                val apkFile = File(destinationDir, fileName)
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                FileOutputStream(apkFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (length > 0) {
                            val progress = ((totalRead * 100) / length).toInt()
                            emit(DownloadStatus.Progress(progress))
                        }
                    }
                    outputStream.flush()
                }
                
                emit(DownloadStatus.Completed(apkFile))
            }

        } catch (e: Exception) {
            Log.e("UpdateDownloader", "Download error", e)
            emit(DownloadStatus.Error(e.message ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)

    sealed class DownloadStatus {
        object Started : DownloadStatus()
        data class Progress(val percentage: Int) : DownloadStatus()
        data class Completed(val file: File) : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
    }
}
