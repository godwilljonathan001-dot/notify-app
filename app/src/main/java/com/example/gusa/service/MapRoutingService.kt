package com.example.gusa.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.gusa.model.NavStep
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

data class RouteData(
    val points: List<LatLng> = emptyList(),
    val steps: List<NavStep> = emptyList(),
    val status: String = "UNKNOWN"
)

object MapRoutingService {
    private val client = OkHttpClient()

    /**
     * Fetches comprehensive route data including points and steps.
     */
    suspend fun fetchRoute(
        context: Context,
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng> = emptyList(),
        optimize: Boolean = false
    ): RouteData = withContext(Dispatchers.IO) {
        val apiKey = getMapsApiKey(context)
        if (apiKey.isEmpty()) return@withContext RouteData(status = "MISSING_API_KEY")

        val wps = if (waypoints.isNotEmpty()) "&waypoints=${if (optimize) "optimize:true|" else ""}" + waypoints.joinToString("|") { "${it.latitude},${it.longitude}" } else ""
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}$wps&key=$apiKey"

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Android-Package", context.packageName)
                .addHeader("X-Android-Cert", getCertificateFingerprint(context))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext RouteData(status = "HTTP_ERROR_${response.code}")
            
            val responseBody = response.body?.string() ?: ""
            if (responseBody.isEmpty()) return@withContext RouteData(status = "EMPTY_RESPONSE")
            
            val json = JSONObject(responseBody)
            val status = json.optString("status", "UNKNOWN")

            if (status == "OK") {
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) return@withContext RouteData(status = "NO_ROUTES_FOUND")
                
                val route = routes.getJSONObject(0)
                val points = decodePolyline(route.getJSONObject("overview_polyline").getString("points"))
                val steps = mutableListOf<NavStep>()
                val legs = route.getJSONArray("legs")

                for (i in 0 until legs.length()) {
                    val s = legs.getJSONObject(i).getJSONArray("steps")
                    for (j in 0 until s.length()) {
                        val o = s.getJSONObject(j)
                        steps.add(NavStep(
                            LatLng(o.getJSONObject("end_location").getDouble("lat"), o.getJSONObject("end_location").getDouble("lng")),
                            o.getString("html_instructions").replace(Regex("<[^>]*>"), ""),
                            o.optString("maneuver", "straight"),
                            o.getJSONObject("distance").getString("text")
                        ))
                    }
                }
                return@withContext RouteData(points, steps, status)
            }
            Log.w("MapRoutingService", "Directions API Status: $status. Response: $responseBody")
            return@withContext RouteData(status = status)
        } catch (e: Exception) {
            Log.e("MapRoutingService", "Route fetch error", e)
            return@withContext RouteData(status = "EXCEPTION: ${e.message}")
        }
    }

    /**
     * Legacy helper for backward compatibility, now uses fetchRoute internally.
     */
    suspend fun getDirectionPoints(
        context: Context,
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng> = emptyList(),
        optimize: Boolean = true
    ): List<LatLng> = fetchRoute(context, origin, destination, waypoints, optimize).points

    internal fun getMapsApiKey(context: Context): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            ai.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) { "" }
    }

    internal fun getCertificateFingerprint(context: Context): String {
        try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }

            signatures?.firstOrNull()?.let {
                val digest = MessageDigest.getInstance("SHA1").digest(it.toByteArray())
                return digest.joinToString(":") { b -> "%02X".format(b) }
            }
        } catch (e: Exception) { }
        return ""
    }

    internal fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0; val len = encoded.length; var lat = 0; var lng = 0
        while (index < len) {
            var b: Int; var shift = 0; var result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }
}
