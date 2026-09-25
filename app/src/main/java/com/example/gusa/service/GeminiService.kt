package com.example.gusa.service

import android.util.Log
import com.example.gusa.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared AI Service for all GUSA Enterprise Import Operations.
 * Updated to use Firebase AI SDK with Gemini Developer API backend.
 */
object GeminiService {
    private const val TAG = "GeminiService"
    private const val GEMINI_MODEL = "gemini-1.5-flash"

    private val generativeModel by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey.contains("MY_GEMINI_API_KEY")) {
            Log.e(TAG, "Gemini API Key is invalid or using placeholder. Ensure GEMINI_API_KEY is set in local.properties.")
        } else {
            Log.d(TAG, "Gemini model initialized with API Key via Firebase AI SDK.")
        }

        // Initialize Firebase AI with Google AI backend (Gemini Developer API)
        // In the Firebase AI SDK, the API key is typically managed via the Firebase console
        // and secured by App Check, but for the Developer API backend, it might still be needed.
        // If googleAI() takes no arguments, we use the standard initialization.
        val ai = Firebase.ai(backend = GenerativeBackend.googleAI())
        ai.generativeModel(
            modelName = GEMINI_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
            }
        )
    }

    /**
     * Intelligent parsing of raw administrator input into structured JSON.
     */
    suspend fun parseImportData(rawText: String, importType: String): String = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are GUSA Enterprise AI Data Architect.
                Extract and convert the following raw text into a clean, structured JSON array for the category: '$importType'.
                
                Return ONLY a valid JSON object. Do not include markdown formatting like ```json.
                EXACT structure:
                {
                    "status": "success",
                    "data": [ Array of parsed objects ]
                }
    
                SCHEMA for '$importType':
                - 'students': { studentId: String, studentName: String, email: String, universityId: String, routeId: String, pickupStation: String, busId: String }
                - 'drivers': { driverId: String, driverName: String, email: String, universityId: String, routeId: String, busId: String }
                - 'buses': { busId: String, busNumber: String, universityId: String, driverId: String, totalSeats: Int }
                - 'routes': { routeId: String, routeName: String, universityId: String, startingLocation: String, destination: String, stops: Array<String> }
                - 'stops': { stopId: String, name: String, latitude: Double, longitude: Double, routeId: String, universityId: String }
                - 'universities': { universityId: String, name: String, location: String }
                - 'admins': { adminId: String, name: String, email: String }
                - 'destinations': { destinationId: String, name: String, latitude: Double, longitude: Double, universityId: String }
                - 'starting_locations': { startingLocationId: String, name: String, universityId: String, latitude: Double, longitude: Double }
    
                Rules:
                - Standardize formatting (TRIM names, UPPERCASE IDs where applicable).
                - For latitude/longitude, ensure they are Double.
                - If data is missing for a field, use an empty string "" or 0.0 for numbers.
                - Output ONLY the JSON object.
                
                Raw text to parse:
                $rawText
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val rawResponse = response.text ?: ""
            val cleanedText = cleanJsonResponse(rawResponse)

            if (cleanedText.isEmpty()) {
                Log.e(TAG, "AI returned empty response or invalid format: $rawResponse")
                "Error: AI failed to generate valid data."
            } else {
                cleanedText
            }
        } catch (e: com.google.firebase.FirebaseException) {
            if (e.message?.contains("Too many attempts") == true) {
                Log.e(TAG, "AppCheck Throttling: Too many attempts. This usually means attestation is failing (Check Play Integrity or Debug Token).", e)
                "Error: AI service throttled due to security check failures. Please check your App Check configuration."
            } else {
                Log.e(TAG, "Firebase SDK Error", e)
                "Error: ${e.localizedMessage ?: "Firebase Service unavailable"}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini SDK Error", e)
            "Error: ${e.localizedMessage ?: "AI Service unavailable"}"
        }
    }

    private fun cleanJsonResponse(text: String): String {
        var result = text.trim()
        
        // Remove markdown code blocks if present
        if (result.startsWith("```")) {
            val lines = result.lines()
            if (lines.size >= 2) {
                result = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }
        
        // Handle cases where there might be text before or after the JSON object
        val startIndex = result.indexOf('{')
        val endIndex = result.lastIndexOf('}')
        
        return if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            result.substring(startIndex, endIndex + 1)
        } else {
            // If it's not a single object, maybe it's just the array? 
            // The prompt asks for an object, but let's be safe.
            ""
        }
    }

    suspend fun summarizeLogs(logs: List<String>): String = withContext(Dispatchers.IO) {
        try {
            val prompt = "Summarize these audit logs briefly: \n${logs.joinToString("\n")}"
            generativeModel.generateContent(prompt).text ?: "No summary available."
        } catch (e: Exception) {
            "Error summarizing logs."
        }
    }

    suspend fun getAiInsights(stats: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val prompt = "Provide 3 brief insights for these system stats: $stats"
            generativeModel.generateContent(prompt).text ?: "No insights available."
        } catch (e: Exception) {
            "Error generating insights."
        }
    }
}