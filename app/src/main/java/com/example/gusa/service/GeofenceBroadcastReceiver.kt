package com.example.gusa.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.Timestamp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * GUSA GeofenceBroadcastReceiver
 *
 * Responsibilities:
 *
 * DRIVER:
 *   ENTER -> mark stop as NEAR
 *   EXIT  -> record LEFT_AREA
 *
 * STUDENT:
 *   ENTER -> send in-app broadcast + local system notification
 *   EXIT  -> send in-app broadcast
 *
 * IMPORTANT:
 *
 * Geofence ENTER does NOT mean ARRIVED.
 * Geofence EXIT does NOT mean PASSED.
 *
 * Physical arrival must be confirmed by the bus location engine:
 *
 *   GPS
 *     -> distance
 *     -> GPS accuracy
 *     -> direction/bearing
 *     -> route sequence
 *     -> consecutive confirmations
 *     -> ARRIVED
 *
 * Passing a stop must also be confirmed by the location engine:
 *
 *   previous GPS position
 *     -> current GPS position
 *     -> route direction
 *     -> stop sequence
 *     -> movement beyond stop
 *     -> PASSED
 *
 * Recommended request IDs:
 *
 * DRIVER:
 *   DRIVER_STOP:stopId:busId
 *
 * STUDENT:
 *   STUDENT_APPROACH:radius:stopId:busId
 *
 * Legacy request IDs containing stopName are also supported:
 *
 * DRIVER:
 *   DRIVER_STOP:stopId:busId:stopName
 *
 * STUDENT:
 *   STUDENT_APPROACH:radius:stopId:busId:stopName
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {

        private const val TAG = "GeofenceReceiver"

        // ---------------------------------------------------------------------
        // Broadcast action
        // ---------------------------------------------------------------------

        const val ACTION_GEOFENCE_ALERT =
            "com.example.gusa.GEOFENCE_ALERT"

        // ---------------------------------------------------------------------
        // Intent extras
        // ---------------------------------------------------------------------

        const val EXTRA_RADIUS = "RADIUS"
        const val EXTRA_STOP_ID = "STOP_ID"
        const val EXTRA_BUS_ID = "BUS_ID"
        const val EXTRA_STOP_NAME = "STOP_NAME"
        const val EXTRA_TRANSITION = "TRANSITION"
        const val EXTRA_TRIP_ID = "TRIP_ID"

        // ---------------------------------------------------------------------
        // Firestore status values
        // ---------------------------------------------------------------------

        private const val STATUS_FAR = "far"
        private const val STATUS_APPROACHING = "approaching"
        private const val STATUS_NEAR = "near"
        private const val STATUS_AT_STOP = "arrived"
        private const val STATUS_LEFT_AREA = "left_area"
        private const val STATUS_PASSED = "passed"

        // ---------------------------------------------------------------------
        // Notification
        // ---------------------------------------------------------------------

        private const val CHANNEL_ID = "student_geofence_alerts"

        private const val CHANNEL_NAME =
            "Bus Proximity Alerts"

        private const val CHANNEL_DESCRIPTION =
            "Alerts students when their bus is approaching their stop."

        private const val NOTIFICATION_PERMISSION =
            Manifest.permission.POST_NOTIFICATIONS

        // ---------------------------------------------------------------------
        // Local preferences
        // ---------------------------------------------------------------------

        private const val PREFS_NAME =
            "gusa_geofence_state"

        private const val KEY_PREFIX_ALERT =
            "alert_"

        // ---------------------------------------------------------------------
        // Firestore collections
        // ---------------------------------------------------------------------

        private const val COLLECTION_STOPS =
            "stops"

        private const val COLLECTION_NOTIFICATIONS =
            "notifications"

        private const val COLLECTION_ACTIVE_TRIPS =
            "activeTrips"

        // ---------------------------------------------------------------------
        // Notification/event types
        // ---------------------------------------------------------------------

        private const val TYPE_BUS_NEAR_STOP =
            "bus_near_stop"

        private const val TYPE_BUS_AT_STOP =
            "bus_at_stop"

        private const val TYPE_BUS_PASSED_STOP =
            "bus_passed_stop"

        // ---------------------------------------------------------------------
        // Notification IDs
        // ---------------------------------------------------------------------

        private const val NOTIFICATION_OFFSET_APPROACHING = 1000

        private const val NOTIFICATION_OFFSET_NEAR = 2000

        private const val NOTIFICATION_OFFSET_ARRIVED = 3000

        private const val NOTIFICATION_OFFSET_PASSED = 4000

        /**
         * Confirm that the bus has physically arrived at a stop.
         *
         * This method MUST be called only after the GPS/location engine
         * verifies the arrival.
         *
         * Recommended verification:
         *
         * - accurate GPS fix
         * - small distance from stop
         * - correct route direction
         * - correct stop sequence
         * - consecutive GPS confirmations
         */
        suspend fun confirmDriverArrival(
            stopId: String,
            busId: String,
            stopName: String,
            tripId: String? = null
        ): Boolean {

            if (stopId.isBlank() || busId.isBlank()) {
                Log.e(
                    TAG,
                    "Cannot confirm arrival: stopId or busId is empty."
                )
                return false
            }

            val db = FirebaseFirestore.getInstance()

            val stopRef =
                db.collection(COLLECTION_STOPS)
                    .document(stopId)

            val now = Timestamp.now()

            return try {

                /*
                 * Transaction makes arrival confirmation atomic.
                 *
                 * This prevents two simultaneous location/geofence events
                 * from both creating an arrival event.
                 */
                val shouldCreateEvent = db.runTransaction { transaction ->

                    val snapshot =
                        transaction.get(stopRef)

                    val currentStatus =
                        snapshot.getString("status")

                    /*
                     * Already arrived for the same bus.
                     */
                    if (
                        currentStatus == STATUS_AT_STOP &&
                        snapshot.getString("activeBusId") == busId
                    ) {
                        false
                    } else {

                        val update = hashMapOf<String, Any>(
                            "status" to STATUS_AT_STOP,
                            "lastArrival" to now,
                            "activeBusId" to busId,
                            "updatedAt" to now
                        )

                        if (!tripId.isNullOrBlank()) {
                            update["activeTripId"] = tripId
                        }

                        /*
                         * Do NOT set completedAt here.
                         *
                         * completedAt should represent completion of the
                         * entire trip/event according to the application's
                         * business meaning, not merely arrival at a stop.
                         */
                        transaction.update(
                            stopRef,
                            update
                        )

                        true
                    }
                }.await()

                if (!shouldCreateEvent) {

                    Log.d(
                        TAG,
                        "Arrival already confirmed for stop=$stopId bus=$busId"
                    )

                    return true
                }

                Log.d(
                    TAG,
                    "Confirmed ARRIVED: bus=$busId stop=$stopName"
                )

                /*
                 * Create a deterministic event.
                 *
                 * The document ID prevents duplicate arrival event
                 * documents for the same trip/stop.
                 */
                createTripNotificationEvent(
                    stopId = stopId,
                    busId = busId,
                    stopName = stopName,
                    type = TYPE_BUS_AT_STOP,
                    status = STATUS_AT_STOP,
                    title = "Bus Arrived",
                    message = "Bus ($busId) has arrived at $stopName.",
                    tripId = tripId,
                    timestamp = now
                )

                updateActiveTrip(
                    busId = busId,
                    stopId = stopId,
                    stopName = stopName,
                    status = STATUS_AT_STOP,
                    tripId = tripId
                )

                true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to confirm arrival at $stopName",
                    e
                )

                false
            }
        }

        /**
         * Confirm that the bus has passed a stop.
         *
         * IMPORTANT:
         *
         * Do NOT call this merely because a geofence EXIT occurred.
         *
         * The location engine should verify:
         *
         * - bus was previously near/at the stop
         * - bus is moving in route direction
         * - current position is beyond the stop
         * - correct route/stop sequence
         */
        suspend fun confirmDriverPassedStop(
            stopId: String,
            busId: String,
            stopName: String,
            tripId: String? = null
        ): Boolean {

            if (stopId.isBlank() || busId.isBlank()) {
                Log.e(
                    TAG,
                    "Cannot confirm passed: stopId or busId is empty."
                )
                return false
            }

            val db = FirebaseFirestore.getInstance()

            val stopRef =
                db.collection(COLLECTION_STOPS)
                    .document(stopId)

            val now = Timestamp.now()

            return try {

                val shouldCreateEvent = db.runTransaction { transaction ->

                    val snapshot =
                        transaction.get(stopRef)

                    val currentStatus =
                        snapshot.getString("status")

                    /*
                     * Already passed.
                     */
                    if (
                        currentStatus == STATUS_PASSED &&
                        snapshot.getString("activeBusId") == busId
                    ) {
                        false
                    } else {

                        val update = hashMapOf<String, Any>(
                            "status" to STATUS_PASSED,
                            "lastPassed" to now,
                            "activeBusId" to busId,
                            "updatedAt" to now
                        )

                        if (!tripId.isNullOrBlank()) {
                            update["activeTripId"] = tripId
                        }

                        transaction.update(
                            stopRef,
                            update
                        )

                        true
                    }
                }.await()

                if (!shouldCreateEvent) {

                    Log.d(
                        TAG,
                        "Passed already confirmed for stop=$stopId bus=$busId"
                    )

                    return true
                }

                Log.d(
                    TAG,
                    "Confirmed PASSED: bus=$busId stop=$stopName"
                )

                createTripNotificationEvent(
                    stopId = stopId,
                    busId = busId,
                    stopName = stopName,
                    type = TYPE_BUS_PASSED_STOP,
                    status = STATUS_PASSED,
                    title = "Bus Passed",
                    message = "Bus ($busId) has passed $stopName.",
                    tripId = tripId,
                    timestamp = now
                )

                /*
                 * The active trip can move to the next stop.
                 *
                 * The actual next stop should be determined by the
                 * route engine. Therefore we only record the event here.
                 */
                true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to confirm passed stop=$stopName",
                    e
                )

                false
            }
        }

        /**
         * Mark a stop as APPROACHING.
         *
         * This should preferably be called by the location engine when
         * actual GPS distance indicates the bus is approaching.
         */
        suspend fun markApproaching(
            stopId: String,
            busId: String,
            tripId: String? = null
        ): Boolean {

            if (stopId.isBlank() || busId.isBlank()) {
                return false
            }

            val db = FirebaseFirestore.getInstance()

            val stopRef =
                db.collection(COLLECTION_STOPS)
                    .document(stopId)

            val now = Timestamp.now()

            return try {

                db.runTransaction { transaction ->

                    val snapshot =
                        transaction.get(stopRef)

                    val currentStatus =
                        snapshot.getString("status")

                    /*
                     * Do not move backwards from a later state.
                     */
                    if (
                        currentStatus == STATUS_AT_STOP ||
                        currentStatus == STATUS_PASSED
                    ) {
                        return@runTransaction
                    }

                    val update = hashMapOf<String, Any>(
                        "status" to STATUS_APPROACHING,
                        "activeBusId" to busId,
                        "lastApproachingUpdate" to now,
                        "updatedAt" to now
                    )

                    if (!tripId.isNullOrBlank()) {
                        update["activeTripId"] = tripId
                    }

                    transaction.update(
                        stopRef,
                        update
                    )
                }.await()

                true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to mark stop as approaching",
                    e
                )

                false
            }
        }

        /**
         * Mark a stop as NEAR.
         */
        suspend fun markNear(
            stopId: String,
            busId: String,
            stopName: String,
            tripId: String? = null
        ): Boolean {

            if (stopId.isBlank() || busId.isBlank()) {
                return false
            }

            val db = FirebaseFirestore.getInstance()

            val stopRef =
                db.collection(COLLECTION_STOPS)
                    .document(stopId)

            val now = Timestamp.now()

            return try {

                val shouldCreateEvent =
                    db.runTransaction { transaction ->

                        val snapshot =
                            transaction.get(stopRef)

                        val currentStatus =
                            snapshot.getString("status")

                        /*
                         * Never downgrade an already-arrived/passed stop.
                         */
                        if (
                            currentStatus == STATUS_AT_STOP ||
                            currentStatus == STATUS_PASSED
                        ) {
                            false
                        } else if (
                            currentStatus == STATUS_NEAR &&
                            snapshot.getString("activeBusId") == busId
                        ) {
                            false
                        } else {

                            val update =
                                hashMapOf<String, Any>(
                                    "status" to STATUS_NEAR,
                                    "activeBusId" to busId,
                                    "lastProximityUpdate" to now,
                                    "updatedAt" to now
                                )

                            if (!tripId.isNullOrBlank()) {
                                update["activeTripId"] = tripId
                            }

                            transaction.update(
                                stopRef,
                                update
                            )

                            true
                        }
                    }.await()

                if (shouldCreateEvent) {

                    createTripNotificationEvent(
                        stopId = stopId,
                        busId = busId,
                        stopName = stopName,
                        type = TYPE_BUS_NEAR_STOP,
                        status = STATUS_NEAR,
                        title = "Bus Nearby",
                        message = "Bus ($busId) is near $stopName.",
                        tripId = tripId,
                        timestamp = now
                    )
                }

                true

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to mark stop as near",
                    e
                )

                false
            }
        }

        /**
         * Create a deterministic notification event.
         *
         * This is an EVENT RECORD, not FCM itself.
         *
         * A Firebase Cloud Function/backend can listen for this document
         * and send the actual FCM push notification.
         */
        private suspend fun createTripNotificationEvent(
            stopId: String,
            busId: String,
            stopName: String,
            type: String,
            status: String,
            title: String,
            message: String,
            tripId: String?,
            timestamp: Timestamp
        ) {

            val db =
                FirebaseFirestore.getInstance()

            /*
             * Deterministic ID.
             *
             * Same trip + bus + stop + event type
             * cannot create unlimited duplicate documents.
             */
            val eventId = buildEventId(
                stopId = stopId,
                busId = busId,
                type = type,
                tripId = tripId
            )

            val notificationRef =
                db.collection(COLLECTION_NOTIFICATIONS)
                    .document(eventId)

            val notification =
                hashMapOf<String, Any>(
                    "eventId" to eventId,
                    "title" to title,
                    "message" to message,
                    "stopId" to stopId,
                    "busId" to busId,
                    "type" to type,
                    "status" to status,
                    "createdAt" to timestamp,
                    "sent" to false
                )

            if (!tripId.isNullOrBlank()) {
                notification["tripId"] = tripId
            }

            try {

                /*
                 * set() is used to create the document.
                 */
                notificationRef
                    .set(notification)
                    .await()

                Log.d(
                    TAG,
                    "Notification event created: $eventId"
                )

            } catch (e: Exception) {

                /*
                 * If it already exists, this is not a fatal error.
                 */
                Log.d(
                    TAG,
                    "Notification event may already exist: $eventId"
                )
            }
        }

        /**
         * Update active trip in both Firestore and RTDB.
         *
         * Firestore:
         *   trip/business state
         *
         * RTDB:
         *   active/live state
         */
        private suspend fun updateActiveTrip(
            busId: String,
            stopId: String,
            stopName: String,
            status: String,
            tripId: String?
        ) {

            val timestamp =
                Timestamp.now()

            val firestoreUpdate =
                hashMapOf<String, Any>(
                    "currentStopId" to stopId,
                    "currentStop" to stopName,
                    "status" to status,
                    "updatedAt" to timestamp
                )

            if (!tripId.isNullOrBlank()) {
                firestoreUpdate["tripId"] = tripId
            }

            val firestoreTask =
                FirebaseFirestore
                    .getInstance()
                    .collection(COLLECTION_ACTIVE_TRIPS)
                    .document(busId)
                    .update(firestoreUpdate)

            val realtimeUpdate =
                hashMapOf<String, Any>(
                    "currentStopId" to stopId,
                    "currentStop" to stopName,
                    "status" to status,
                    "updatedAt" to System.currentTimeMillis()
                )

            if (!tripId.isNullOrBlank()) {
                realtimeUpdate["tripId"] = tripId
            }

            val realtimeTask =
                FirebaseDatabase
                    .getInstance()
                    .getReference(COLLECTION_ACTIVE_TRIPS)
                    .child(busId)
                    .updateChildren(realtimeUpdate)

            /*
             * Run independently.
             *
             * A failure in one database should not prevent the other
             * database from receiving the update.
             */
            try {

                firestoreTask.await()

                Log.d(
                    TAG,
                    "Firestore activeTrip updated for bus=$busId"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Firestore activeTrip update failed",
                    e
                )
            }

            try {

                realtimeTask.await()

                Log.d(
                    TAG,
                    "RTDB activeTrip updated for bus=$busId"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "RTDB activeTrip update failed",
                    e
                )
            }
        }

        /**
         * Generate deterministic event ID.
         */
        private fun buildEventId(
            stopId: String,
            busId: String,
            type: String,
            tripId: String?
        ): String {

            val safeTrip =
                tripId
                    ?.takeIf { it.isNotBlank() }
                    ?: "NO_TRIP"

            return "${safeTrip}_${busId}_${stopId}_${type}"
                .replace("/", "_")
                .replace(" ", "_")
                .replace(":", "_")
        }

        /**
         * Generate deterministic local notification ID.
         */
        private fun buildNotificationId(
            stopId: String,
            busId: String,
            tripId: String?,
            offset: Int
        ): Int {

            val key =
                "${tripId ?: "NO_TRIP"}:$busId:$stopId"

            /*
             * Avoid negative IDs.
             */
            val hash =
                key.hashCode() and 0x7fffffff

            return (hash % 100000) + offset
        }
    }

    // =========================================================================
    // Receiver
    // =========================================================================

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val pendingResult =
            goAsync()

        /*
         * Use a controlled scope for this receiver invocation.
         */
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO
            )

        scope.launch {

            try {

                val event =
                    GeofencingEvent.fromIntent(intent)

                if (event == null) {

                    Log.e(
                        TAG,
                        "GeofencingEvent is null"
                    )

                    return@launch
                }

                if (event.hasError()) {

                    Log.e(
                        TAG,
                        "Geofencing error code: ${event.errorCode}"
                    )

                    return@launch
                }

                val transition =
                    event.geofenceTransition

                val geofences =
                    event.triggeringGeofences
                        ?: emptyList()

                if (geofences.isEmpty()) {

                    Log.d(
                        TAG,
                        "No triggering geofences."
                    )

                    return@launch
                }

                for (geofence in geofences) {

                    val requestId =
                        geofence.requestId

                    if (requestId.isBlank()) {
                        continue
                    }

                    when (transition) {

                        Geofence.GEOFENCE_TRANSITION_ENTER -> {

                            Log.d(
                                TAG,
                                "ENTER: $requestId"
                            )

                            processEnter(
                                context = context,
                                requestId = requestId
                            )
                        }

                        Geofence.GEOFENCE_TRANSITION_EXIT -> {

                            Log.d(
                                TAG,
                                "EXIT: $requestId"
                            )

                            processExit(
                                context = context,
                                requestId = requestId
                            )
                        }

                        else -> {

                            Log.d(
                                TAG,
                                "Ignored transition=$transition"
                            )
                        }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unexpected geofence receiver error",
                    e
                )

            } finally {

                /*
                 * Release the receiver's temporary execution time.
                 */
                pendingResult.finish()

                scope.cancel()
            }
        }
    }

    // =========================================================================
    // ENTER
    // =========================================================================

    private suspend fun processEnter(
        context: Context,
        requestId: String
    ) {

        when {

            requestId.startsWith(
                "DRIVER_STOP:",
                ignoreCase = true
            ) -> {

                handleDriverNearStop(
                    requestId
                )
            }

            requestId.startsWith(
                "STUDENT_APPROACH:",
                ignoreCase = true
            ) -> {

                handleStudentApproach(
                    context,
                    requestId
                )
            }

            else -> {

                Log.w(
                    TAG,
                    "Unknown ENTER requestId=$requestId"
                )
            }
        }
    }

    // =========================================================================
    // EXIT
    // =========================================================================

    private suspend fun processExit(
        context: Context,
        requestId: String
    ) {

        when {

            requestId.startsWith(
                "DRIVER_STOP:",
                ignoreCase = true
            ) -> {

                handleDriverLeftStopArea(
                    requestId
                )
            }

            requestId.startsWith(
                "STUDENT_APPROACH:",
                ignoreCase = true
            ) -> {

                handleStudentLeftApproachArea(
                    context,
                    requestId
                )
            }

            else -> {

                Log.w(
                    TAG,
                    "Unknown EXIT requestId=$requestId"
                )
            }
        }
    }

    // =========================================================================
    // DRIVER ENTER
    // =========================================================================

    private suspend fun handleDriverNearStop(
        requestId: String
    ) {

        val data =
            parseDriverRequestId(requestId)
                ?: run {

                    Log.e(
                        TAG,
                        "Invalid DRIVER_STOP requestId=$requestId"
                    )

                    return
                }

        Log.d(
            TAG,
            "Driver entered proximity: " +
                "bus=${data.busId}, " +
                "stop=${data.stopName}"
        )

        /*
         * IMPORTANT:
         *
         * Geofence ENTER means NEAR.
         *
         * It does NOT mean ARRIVED.
         */
        markNear(
            stopId = data.stopId,
            busId = data.busId,
            stopName = data.stopName,
            tripId = data.tripId
        )
    }

    // =========================================================================
    // DRIVER EXIT
    // =========================================================================

    private suspend fun handleDriverLeftStopArea(
        requestId: String
    ) {

        val data =
            parseDriverRequestId(requestId)
                ?: run {

                    Log.e(
                        TAG,
                        "Invalid DRIVER_STOP requestId=$requestId"
                    )

                    return
                }

        val db =
            FirebaseFirestore.getInstance()

        val stopRef =
            db.collection(COLLECTION_STOPS)
                .document(data.stopId)

        val now =
            Timestamp.now()

        try {

            val snapshot =
                stopRef.get().await()

            val currentStatus =
                snapshot.getString("status")

            /*
             * EXIT does NOT automatically mean PASSED.
             *
             * If the bus has already been confirmed as ARRIVED,
             * do not downgrade it to LEFT_AREA.
             */
            if (
                currentStatus == STATUS_AT_STOP ||
                currentStatus == STATUS_PASSED
            ) {

                Log.d(
                    TAG,
                    "Ignoring EXIT downgrade for " +
                        "stop=${data.stopId}, " +
                        "status=$currentStatus"
                )

                return
            }

            val update =
                hashMapOf<String, Any>(
                    "lastGeofenceExit" to now,
                    "lastKnownBusId" to data.busId,
                    "updatedAt" to now
                )

            /*
             * Only update state to LEFT_AREA if the stop is currently
             * in an intermediate proximity state.
             */
            if (
                currentStatus == STATUS_NEAR ||
                currentStatus == STATUS_APPROACHING
            ) {
                update["status"] = STATUS_LEFT_AREA
            }

            stopRef
                .update(update)
                .await()

            Log.d(
                TAG,
                "Driver left proximity area: " +
                    "bus=${data.busId}, " +
                    "stop=${data.stopName}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to record driver EXIT",
                e
            )
        }
    }

    // =========================================================================
    // STUDENT ENTER
    // =========================================================================

    private fun handleStudentApproach(
        context: Context,
        requestId: String
    ) {

        val data =
            parseStudentRequestId(requestId)
                ?: run {

                    Log.e(
                        TAG,
                        "Invalid STUDENT_APPROACH requestId=$requestId"
                    )

                    return
                }

        /*
         * Broadcast to active UI components.
         */
        val broadcastIntent =
            Intent(ACTION_GEOFENCE_ALERT).apply {

                putExtra(
                    EXTRA_RADIUS,
                    data.radius
                )

                putExtra(
                    EXTRA_STOP_ID,
                    data.stopId
                )

                putExtra(
                    EXTRA_BUS_ID,
                    data.busId
                )

                putExtra(
                    EXTRA_STOP_NAME,
                    data.stopName
                )

                putExtra(
                    EXTRA_TRANSITION,
                    "ENTER"
                )

                if (!data.tripId.isNullOrBlank()) {

                    putExtra(
                        EXTRA_TRIP_ID,
                        data.tripId
                    )
                }

                /*
                 * Restrict broadcast to this application.
                 */
                setPackage(
                    context.packageName
                )
            }

        context.sendBroadcast(
            broadcastIntent
        )

        /*
         * Show a system notification so the student can be alerted
         * while the app is in background.
         */
        showStudentSystemNotification(
            context = context,
            stopId = data.stopId,
            busId = data.busId,
            tripId = data.tripId,
            title = "Bus Approaching!",
            message =
                "The bus is within " +
                    "${data.radius}m of " +
                    "${data.stopName}.",
            notificationOffset =
                NOTIFICATION_OFFSET_APPROACHING
        )

        Log.d(
            TAG,
            "Student approach processed: ${data.stopName}"
        )
    }

    // =========================================================================
    // STUDENT EXIT
    // =========================================================================

    private fun handleStudentLeftApproachArea(
        context: Context,
        requestId: String
    ) {

        val data =
            parseStudentRequestId(requestId)
                ?: run {

                    Log.e(
                        TAG,
                        "Invalid STUDENT_APPROACH requestId=$requestId"
                    )

                    return
                }

        val broadcastIntent =
            Intent(ACTION_GEOFENCE_ALERT).apply {

                putExtra(
                    EXTRA_RADIUS,
                    data.radius
                )

                putExtra(
                    EXTRA_STOP_ID,
                    data.stopId
                )

                putExtra(
                    EXTRA_BUS_ID,
                    data.busId
                )

                putExtra(
                    EXTRA_STOP_NAME,
                    data.stopName
                )

                putExtra(
                    EXTRA_TRANSITION,
                    "EXIT"
                )

                if (!data.tripId.isNullOrBlank()) {

                    putExtra(
                        EXTRA_TRIP_ID,
                        data.tripId
                    )
                }

                setPackage(
                    context.packageName
                )
            }

        context.sendBroadcast(
            broadcastIntent
        )

        Log.d(
            TAG,
            "Student left approach area: " +
                data.stopName
        )
    }

    // =========================================================================
    // SYSTEM NOTIFICATION
    // =========================================================================

    private fun showStudentSystemNotification(
        context: Context,
        stopId: String,
        busId: String,
        tripId: String?,
        title: String,
        message: String,
        notificationOffset: Int
    ) {

        /*
         * Android 13+ requires POST_NOTIFICATIONS permission.
         */
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
        ) {

            val permission =
                ContextCompat.checkSelfPermission(
                    context,
                    NOTIFICATION_PERMISSION
                )

            if (
                permission !=
                    PackageManager.PERMISSION_GRANTED
            ) {

                Log.w(
                    TAG,
                    "POST_NOTIFICATIONS permission not granted."
                )

                return
            }
        }

        val notificationManager =
            context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        createNotificationChannel(
            notificationManager
        )

        /*
         * Prevent notification spam.
         *
         * One notification per:
         *
         * trip + bus + stop + notification type
         */
        val notificationKey =
            "$notificationOffset:" +
                "${tripId ?: "NO_TRIP"}:" +
                "$busId:" +
                stopId

        val preferences =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val alreadyShown =
            preferences.getBoolean(
                KEY_PREFIX_ALERT + notificationKey,
                false
            )

        if (alreadyShown) {

            Log.d(
                TAG,
                "Notification already shown: $notificationKey"
            )

            return
        }

        val notificationId =
            buildNotificationId(
                stopId = stopId,
                busId = busId,
                tripId = tripId,
                offset = notificationOffset
            )

        val notification =
            NotificationCompat
                .Builder(
                    context,
                    CHANNEL_ID
                )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat.CATEGORY_EVENT
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

        try {

            notificationManager.notify(
                notificationId,
                notification
            )

            /*
             * Save only after notify succeeds.
             */
            preferences.edit()
                .putBoolean(
                    KEY_PREFIX_ALERT + notificationKey,
                    true
                )
                .apply()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to show system notification",
                e
            )
        }
    }

    // =========================================================================
    // NOTIFICATION CHANNEL
    // =========================================================================

    private fun createNotificationChannel(
        notificationManager: NotificationManager
    ) {

        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.O
        ) {
            return
        }

        val existingChannel =
            notificationManager
                .getNotificationChannel(
                    CHANNEL_ID
                )

        if (existingChannel != null) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    CHANNEL_DESCRIPTION

                enableVibration(true)
            }

        notificationManager
            .createNotificationChannel(
                channel
            )
    }

    // =========================================================================
    // DRIVER REQUEST ID PARSER
    // =========================================================================

    private fun parseDriverRequestId(
        requestId: String
    ): DriverGeofenceData? {

        /*
         * Supported:
         *
         * DRIVER_STOP:stopId:busId
         *
         * DRIVER_STOP:stopId:busId:stopName
         *
         * DRIVER_STOP:stopId:busId:stopName:tripId
         *
         * We split with limit=5 so a stop name containing ":" does not
         * destroy the basic fields.
         */
        val parts =
            requestId.split(
                ":",
                limit = 5
            )

        if (
            parts.size < 3 ||
            !parts[0].equals(
                "DRIVER_STOP",
                ignoreCase = true
            )
        ) {
            return null
        }

        val stopId =
            parts[1].trim()

        val busId =
            parts[2].trim()

        if (
            stopId.isEmpty() ||
            busId.isEmpty()
        ) {
            return null
        }

        /*
         * Preferred format:
         *
         * DRIVER_STOP:stopId:busId
         */
        if (parts.size == 3) {

            return DriverGeofenceData(
                stopId = stopId,
                busId = busId,
                stopName = stopId,
                tripId = null
            )
        }

        /*
         * Legacy format:
         *
         * DRIVER_STOP:stopId:busId:stopName
         */
        val remaining =
            parts.subList(
                3,
                parts.size
            )

        val combined =
            remaining.joinToString(":")

        /*
         * We intentionally do not try to guess whether the last part
         * is a tripId. The recommended format is to keep tripId elsewhere.
         */
        return DriverGeofenceData(
            stopId = stopId,
            busId = busId,
            stopName =
                combined.ifBlank {
                    stopId
                },
            tripId = null
        )
    }

    // =========================================================================
    // STUDENT REQUEST ID PARSER
    // =========================================================================

    private fun parseStudentRequestId(
        requestId: String
    ): StudentGeofenceData? {

        /*
         * Preferred:
         *
         * STUDENT_APPROACH:radius:stopId:busId
         *
         * Legacy:
         *
         * STUDENT_APPROACH:radius:stopId:busId:stopName
         */
        val parts =
            requestId.split(
                ":",
                limit = 5
            )

        if (
            parts.size < 4 ||
            !parts[0].equals(
                "STUDENT_APPROACH",
                ignoreCase = true
            )
        ) {
            return null
        }

        val radius =
            parts[1].trim()

        val stopId =
            parts[2].trim()

        val busId =
            parts[3].trim()

        if (
            radius.isEmpty() ||
            stopId.isEmpty() ||
            busId.isEmpty()
        ) {
            return null
        }

        val stopName =
            if (parts.size >= 5) {

                parts
                    .subList(
                        4,
                        parts.size
                    )
                    .joinToString(":")
                    .trim()
                    .ifBlank {
                        stopId
                    }

            } else {
                stopId
            }

        return StudentGeofenceData(
            radius = radius,
            stopId = stopId,
            busId = busId,
            stopName = stopName,
            tripId = null
        )
    }

    // =========================================================================
    // DATA CLASSES
    // =========================================================================

    private data class DriverGeofenceData(
        val stopId: String,
        val busId: String,
        val stopName: String,
        val tripId: String?
    )

    private data class StudentGeofenceData(
        val radius: String,
        val stopId: String,
        val busId: String,
        val stopName: String,
        val tripId: String?
    )
}
