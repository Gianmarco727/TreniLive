package com.trenilive.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.trenilive.app.MainActivity
import com.trenilive.app.R
import com.trenilive.app.data.LiveTrainManager
import com.trenilive.app.data.TrainStatus
import com.trenilive.app.data.ViaggiaTrenoResult
import com.trenilive.app.data.ViaggiaTrenoService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class TrainTrackerForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trackingJobs = ConcurrentHashMap<String, Job>()
    private var nativeMediaSession: MediaSession? = null

    companion object {
        const val CHANNEL_ID = "live_train_tracker_channel"
        const val ACTION_START_TRACKING = "com.trenilive.app.ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.trenilive.app.ACTION_STOP_TRACKING"
        const val ACTION_REFRESH_NOTIF = "com.trenilive.app.ACTION_REFRESH_NOTIF"

        const val EXTRA_TRAIN_NUMBER = "extra_train_number"
        const val EXTRA_STATION_ID = "extra_station_id"

        private fun getNotificationIdForTrain(trainNumber: String): Int {
            val clean = trainNumber.replace("[^0-9]".toRegex(), "")
            val parsed = clean.toIntOrNull()
            return if (parsed != null && parsed in 1..99999) {
                1000 + (parsed % 10000)
            } else {
                1000 + (abs(trainNumber.trim().hashCode()) % 10000)
            }
        }

        fun startService(context: Context, trainNumber: String, stationId: String? = null) {
            val intent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
                stationId?.let { putExtra(EXTRA_STATION_ID, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context, trainNumber: String) {
            val intent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                action = ACTION_STOP_TRACKING
                putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val liveManager = LiveTrainManager(this)
        if (liveManager.isMediaSessionBypassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setupMediaSessionBypass()
        }
    }

    private fun setupMediaSessionBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (nativeMediaSession == null) {
                    nativeMediaSession = MediaSession(this, "TreniLiveMediaBypass").apply {
                        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                        val state = PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                            .build()
                        setPlaybackState(state)
                        isActive = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseMediaSessionBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                nativeMediaSession?.isActive = false
                nativeMediaSession?.release()
                nativeMediaSession = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val trainNumber = intent?.getStringExtra(EXTRA_TRAIN_NUMBER)?.trim() ?: ""
        val stationId = intent?.getStringExtra(EXTRA_STATION_ID)?.trim()

        val liveManager = LiveTrainManager(this)
        if (liveManager.isMediaSessionBypassEnabled()) {
            setupMediaSessionBypass()
        } else {
            releaseMediaSessionBypass()
        }

        when (action) {
            ACTION_START_TRACKING -> {
                if (trainNumber.isNotBlank()) {
                    liveManager.setStoppedForToday(trainNumber, false)
                    startTrackingTrain(trainNumber, stationId)
                }
            }
            ACTION_STOP_TRACKING -> {
                if (trainNumber.isNotBlank()) {
                    liveManager.setStoppedForToday(trainNumber, true)
                    stopTrackingForTrain(trainNumber)
                }
            }
            ACTION_REFRESH_NOTIF -> {
                trackingJobs.keys.forEach { num ->
                    val job = trackingJobs[num]
                    if (job != null && job.isActive) {
                        serviceScope.launch {
                            updateNotificationForTrain(num, null, null)
                        }
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSessionBypass()
        serviceScope.cancel()
    }

    private fun startTrackingTrain(trainNumber: String, providedStationId: String?) {
        trackingJobs[trainNumber]?.cancel()

        val notifId = getNotificationIdForTrain(trainNumber)
        val initialNotif = buildNotification(
            trainNumber = trainNumber,
            title = "Inizio tracciamento Treno $trainNumber",
            shortContent = "Connessione ai servizi ViaggiaTreno in corso...",
            expandedContent = "Recupero posizione e ritardo in tempo reale per Treno $trainNumber...",
            progress = 0,
            chipText = "LIV",
            isInitial = true
        )

        startForeground(notifId, initialNotif)

        val job = serviceScope.launch {
            var currentStationId = providedStationId

            while (isActive) {
                try {
                    updateNotificationForTrain(trainNumber, currentStationId) { newStationId ->
                        currentStationId = newStationId
                    }
                } catch (e: Exception) {
                    Log.e("TrainTrackerService", "Errore tracciamento $trainNumber: ${e.message}")
                }
                delay(15_000) // Polling ogni 15s
            }
        }

        trackingJobs[trainNumber] = job
    }

    private fun stopTrackingForTrain(trainNumber: String) {
        trackingJobs[trainNumber]?.cancel()
        trackingJobs.remove(trainNumber)

        val notifId = getNotificationIdForTrain(trainNumber)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)

        if (trackingJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun updateNotificationForTrain(
        trainNumber: String,
        currentStationId: String?,
        onStationIdResolved: ((String) -> Unit)? = null
    ) {
        var stationId = currentStationId
        var timestamp: String? = null

        if (stationId.isNullOrBlank()) {
            when (val resolveRes = ViaggiaTrenoService.resolveTrain(trainNumber)) {
                is ViaggiaTrenoResult.Success -> {
                    stationId = resolveRes.data.second
                    timestamp = resolveRes.data.third
                    onStationIdResolved?.invoke(stationId)
                }
                is ViaggiaTrenoResult.Error -> {
                    updateNotificationError(trainNumber, resolveRes.message)
                    return
                }
            }
        }

        if (timestamp.isNullOrBlank() && !stationId.isNullOrBlank()) {
            when (val resolveRes = ViaggiaTrenoService.resolveTrain(trainNumber)) {
                is ViaggiaTrenoResult.Success -> {
                    stationId = resolveRes.data.second
                    timestamp = resolveRes.data.third
                }
                is ViaggiaTrenoResult.Error -> {
                    updateNotificationError(trainNumber, resolveRes.message)
                    return
                }
            }
        }

        val safeStationId = stationId ?: return
        val safeTimestamp = timestamp ?: return

        when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(trainNumber, safeStationId, safeTimestamp)) {
            is ViaggiaTrenoResult.Success -> {
                val status = statusRes.data
                ensureMediaSessionState(status.progressPercentage)
                val notification = buildNotificationFromStatus(trainNumber, status)
                notifySafely(trainNumber, notification)

                if (status.progressPercentage >= 100 || status.isCancelled) {
                    delay(30_000)
                    stopTrackingForTrain(trainNumber)
                }
            }
            is ViaggiaTrenoResult.Error -> {
                updateNotificationError(trainNumber, statusRes.message)
            }
        }
    }

    private fun updateNotificationError(trainNumber: String, message: String) {
        val notification = buildNotification(
            trainNumber = trainNumber,
            title = "Treno $trainNumber",
            shortContent = "Errore aggiornamento: $message",
            expandedContent = "Errore aggiornamento: $message",
            progress = 0,
            chipText = "ERR",
            isInitial = false
        )
        notifySafely(trainNumber, notification)
    }

    private fun buildNotificationFromStatus(trainNumber: String, status: TrainStatus): Notification {
        val delayText = when {
            status.isCancelled -> "• SOPPRESSO"
            status.delayMinutes > 0 -> "• +${status.delayMinutes} min"
            status.delayMinutes < 0 -> "• ${status.delayMinutes} min"
            else -> "• In orario"
        }

        val chipText = when {
            status.isCancelled -> "SOPPR"
            status.delayMinutes > 0 -> "+${status.delayMinutes}"
            status.delayMinutes < 0 -> "${status.delayMinutes}"
            else -> "OK"
        }

        val title = "${status.category} ${status.trainNumber} $delayText"
        val lastStationText = "Ultimo ril.: ${status.lastDetectedStation}"

        val nextStop = status.nextStop
        val nextStopText = if (status.isCancelled) {
            "Treno soppresso"
        } else if (status.progressPercentage >= 100) {
            "Treno giunto a destinazione (${status.destinationStationName})"
        } else if (nextStop != null) {
            val nextPlat = (nextStop.actualPlatform ?: nextStop.scheduledPlatform)?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }
            val timeStr = formatTime(nextStop.actualOrEstimatedTimeMs)
            val platStr = if (!nextPlat.isNullOrBlank()) " (Bin. $nextPlat)" else ""
            "Prossima: ${nextStop.stationName} ($timeStr)$platStr"
        } else {
            "Destinazione: ${status.destinationStationName}"
        }

        val shortContent = "$lastStationText • $nextStopText"
        val expandedContent = "$lastStationText\n$nextStopText"

        val nextStopTimestamp = nextStop?.actualOrEstimatedTimeMs ?: 0L

        if (nativeMediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val metadata = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, shortContent)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "TreniLive Tracker")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 100000L)
                    .build()
                nativeMediaSession?.setMetadata(metadata)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return buildNotification(
            trainNumber = trainNumber,
            title = title,
            shortContent = shortContent,
            expandedContent = expandedContent,
            progress = status.progressPercentage,
            chipText = chipText,
            whenTimestamp = nextStopTimestamp,
            isInitial = false
        )
    }

    @SuppressLint("NewApi")
    private fun buildNotification(
        trainNumber: String,
        title: String,
        shortContent: String,
        expandedContent: String,
        progress: Int,
        chipText: String = "",
        whenTimestamp: Long = 0L,
        isInitial: Boolean = false
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrainTrackerForegroundService::class.java).apply {
            action = ACTION_STOP_TRACKING
            putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
        }
        val notifId = getNotificationIdForTrain(trainNumber)
        val stopPendingIntent = PendingIntent.getService(
            this,
            notifId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putBoolean("android.promotedOngoing", true)
            if (chipText.isNotBlank()) {
                putString("android.shortCriticalText", chipText)
            }
            putString("android.subText", "Live Tracker")
            putBoolean("com.samsung.android.notification.live", true)
            putBoolean("com.samsung.android.notification.promoted", true)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_train_mono)
                .setContentTitle(title)
                .setContentText(shortContent)
                .setSubText("Live Activity")
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setProgress(100, progress, false)
                .setExtras(extras)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_train_mono),
                        "Apri App",
                        openAppPendingIntent
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_train_mono),
                        "Interrompi",
                        stopPendingIntent
                    ).build()
                )

            if (nativeMediaSession != null) {
                nativeMediaSession?.sessionToken?.let { token ->
                    val mediaStyle = Notification.MediaStyle().setMediaSession(token)
                    builder.setStyle(mediaStyle)
                }
            } else {
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                        val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()

                        val setProgressMethod = progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                        setProgressMethod.invoke(progressStyle, progress)

                        try {
                            val trainIcon = Icon.createWithResource(this, R.drawable.ic_progress_train3)
                            val setTrackerIconMethod = progressStyleClass.methods.firstOrNull {
                                it.name == "setProgressTrackerIcon" || it.name == "setTrackerIcon" || it.name == "setProgressPointIcon"
                            }
                            setTrackerIconMethod?.invoke(progressStyle, trainIcon)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }

                        // RECUPERA LE FERMATE MONITORATE SPECIFICHE PER QUESTO TRENO/TRATTA COMPONENTE
                        val liveManager = LiveTrainManager(this)
                        val activeConfig = liveManager.getLiveTrains().firstOrNull {
                            it.trainNumber == trainNumber || it.getEffectiveLegs().any { leg -> leg.trainNumber == trainNumber }
                        }
                        val currentLeg = activeConfig?.getEffectiveLegs()?.firstOrNull { it.trainNumber == trainNumber }
                        val monitoredStops = currentLeg?.monitoredStops?.ifEmpty { activeConfig.getEffectiveMonitoredStops() }
                            ?: activeConfig?.getEffectiveMonitoredStops() ?: emptyList()

                        if (monitoredStops.isNotEmpty()) {
                            try {
                                val pointClass = Class.forName("android.app.Notification\$ProgressStyle\$Point")
                                val pointsList = ArrayList<Any>()

                                for (stop in monitoredStops.take(4)) {
                                    val pct = stop.progressPercentage.coerceIn(0, 100)
                                    val pointObj = try {
                                        val pointConstructor = pointClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                                        pointConstructor.newInstance(pct)
                                    } catch (e: Exception) {
                                        val pointConstructor = pointClass.declaredConstructors.firstOrNull()
                                        pointConstructor?.newInstance()
                                    }

                                    if (pointObj != null) {
                                        try {
                                            val setPointProgress = pointClass.methods.firstOrNull { it.name == "setProgress" || it.name == "setPosition" }
                                            setPointProgress?.invoke(pointObj, pct)
                                        } catch (e: Throwable) {}

                                        try {
                                            val stopIcon = Icon.createWithResource(this, R.drawable.ic_train_mono)
                                            val setPointIcon = pointClass.methods.firstOrNull { it.name == "setIcon" }
                                            setPointIcon?.invoke(pointObj, stopIcon)
                                        } catch (e: Throwable) {}

                                        pointsList.add(pointObj)
                                    }
                                }

                                val setPointsMethod = progressStyleClass.methods.firstOrNull { it.name == "setProgressPoints" || it.name == "setPoints" }
                                if (setPointsMethod != null) {
                                    setPointsMethod.invoke(progressStyle, pointsList)
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                        }

                        val setStyleMethod = builder.javaClass.getMethod("setStyle", Notification.Style::class.java)
                        setStyleMethod.invoke(builder, progressStyle)

                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }

            builder.build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_train_mono)
                .setContentTitle(title)
                .setContentText(shortContent)
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .build()
        }

        return notification
    }

    private fun ensureMediaSessionState(progressPercentage: Int) {
        if (nativeMediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val state = PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                    .setState(
                        if (progressPercentage >= 100) PlaybackState.STATE_STOPPED else PlaybackState.STATE_PLAYING,
                        (progressPercentage * 1000).toLong(),
                        1.0f
                    )
                    .build()
                nativeMediaSession?.setPlaybackState(state)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun notifySafely(trainNumber: String, notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationIdForTrain(trainNumber)
        try {
            nm.notify(notifId, notification)
        } catch (e: Exception) {
            Log.e("TrainTrackerService", "Errore invio notifica per $trainNumber: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Activity In Viaggio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifiche live in tempo reale per lo stato dei treni con aggiornamento continuo."
                setShowBadge(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun formatTime(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0) return "--:--"
        val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
        return sdf.format(Date(timestampMs))
    }
}