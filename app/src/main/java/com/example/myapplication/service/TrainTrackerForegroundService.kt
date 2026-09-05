package com.example.myapplication.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.data.LiveTrainManager
import com.example.myapplication.data.TrainStatus
import com.example.myapplication.data.ViaggiaTrenoResult
import com.example.myapplication.data.ViaggiaTrenoService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class TrainTrackerForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var activeTrainNumber: String? = null
    private var activeStationId: String? = null
    private var activeTimestamp: String? = null
    private var isTracking = false

    private var nativeMediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    private fun initMediaSession() {
        val liveManager = LiveTrainManager(this)
        if (liveManager.isMediaSessionBypassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                nativeMediaSession = MediaSession(this, "TreniLiveMediaSession").apply {
                    isActive = true
                    // Attivato soltanto se abilitato da Opzioni Sviluppatore
                    val state = PlaybackState.Builder()
                        .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                        .setActions(0L)
                        .build()
                    setPlaybackState(state)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            nativeMediaSession = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRACKING

        when (action) {
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
            ACTION_START_TRACKING -> {
                val trainNumber = intent?.getStringExtra(EXTRA_TRAIN_NUMBER)
                val stationId = intent?.getStringExtra(EXTRA_STATION_ID)
                val timestamp = intent?.getStringExtra(EXTRA_TIMESTAMP)

                if (!trainNumber.isNullOrBlank()) {
                    activeTrainNumber = trainNumber
                    activeStationId = stationId
                    activeTimestamp = timestamp
                    startTracking()
                } else {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        val trainNumber = activeTrainNumber ?: return
        isTracking = true

        val initialNotification = buildNotification(
            title = "Tracciamento Treno $trainNumber",
            shortContent = "Ricerca dati in tempo reale in corso...",
            expandedContent = "Ricerca dati in tempo reale in corso...",
            progress = 0,
            chipText = "OK",
            isInitial = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch {
            while (isActive && isTracking) {
                updateTrainStatus()
                delay(60_000) // Polling ogni 60 secondi
            }
        }
    }

    private suspend fun updateTrainStatus() {
        val trainNumber = activeTrainNumber ?: return

        var stationId = activeStationId
        var timestamp = activeTimestamp

        if (stationId.isNullOrBlank() || timestamp.isNullOrBlank()) {
            when (val resolveRes = ViaggiaTrenoService.resolveTrain(trainNumber)) {
                is ViaggiaTrenoResult.Success -> {
                    stationId = resolveRes.data.second
                    timestamp = resolveRes.data.third
                    activeStationId = stationId
                    activeTimestamp = timestamp
                }
                is ViaggiaTrenoResult.Error -> {
                    updateNotificationError(resolveRes.message)
                    return
                }
            }
        }

        val safeStationId = stationId ?: return
        val safeTimestamp = timestamp ?: return

        when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(trainNumber, safeStationId, safeTimestamp)) {
            is ViaggiaTrenoResult.Success -> {
                val status = statusRes.data
                val notification = buildNotificationFromStatus(status)
                notifySafely(notification)

                if (status.progressPercentage >= 100 || status.isCancelled) {
                    delay(30_000)
                    stopTracking()
                }
            }
            is ViaggiaTrenoResult.Error -> {
                updateNotificationError(statusRes.message)
            }
        }
    }

    private fun updateNotificationError(message: String) {
        val notification = buildNotification(
            title = "Treno ${activeTrainNumber ?: ""}",
            shortContent = "Errore aggiornamento: $message",
            expandedContent = "Errore aggiornamento: $message",
            progress = 0,
            chipText = "ERR",
            isInitial = false
        )
        notifySafely(notification)
    }

    private fun buildNotificationFromStatus(status: TrainStatus): Notification {
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
                    .build()
                nativeMediaSession?.setMetadata(metadata)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return buildNotification(
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
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
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

            // Se l'opzione MediaSession Bypass è stata attivata dalle Opzioni Sviluppatore
            nativeMediaSession?.sessionToken?.let { token ->
                val mediaStyle = Notification.MediaStyle().setMediaSession(token)
                builder.setStyle(mediaStyle)
            } ?: run {
                builder.setStyle(Notification.BigTextStyle().bigText(expandedContent))
            }

            if (!isInitial) {
                builder.setOnlyAlertOnce(true)
            }

            // Orario prossima fermata e cronometro come da specifiche ufficiali Google Live Updates
            if (whenTimestamp > System.currentTimeMillis()) {
                builder.setWhen(whenTimestamp)
                builder.setShowWhen(true)
                builder.setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true)
                }
            } else if (whenTimestamp > 0) {
                builder.setWhen(whenTimestamp)
                builder.setShowWhen(true)
            }

            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    builder.setRequestPromotedOngoing(true)
                } catch (e: Throwable) {
                    extras.putBoolean("android.requestPromotedOngoing", true)
                }

                if (chipText.isNotBlank()) {
                    try {
                        builder.setShortCriticalText(chipText)
                    } catch (e: Throwable) {
                        extras.putString("android.shortCriticalText", chipText)
                    }
                }
            } else {
                try {
                    val setPromotedMethod = builder.javaClass.methods.firstOrNull { it.name == "setRequestPromotedOngoing" }
                    setPromotedMethod?.invoke(builder, true)
                } catch (e: Throwable) {
                    extras.putBoolean("android.requestPromotedOngoing", true)
                }

                try {
                    val setShortTextMethod = builder.javaClass.methods.firstOrNull { it.name == "setShortCriticalText" }
                    setShortTextMethod?.invoke(builder, chipText)
                } catch (e: Throwable) {
                    extras.putString("android.shortCriticalText", chipText)
                }
            }

            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                    val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                    val setProgressMethod = progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    setProgressMethod.invoke(progressStyle, progress)

                    val setStyleMethod = builder.javaClass.getMethod("setStyle", Class.forName("android.app.Notification\$Style"))
                    setStyleMethod.invoke(builder, progressStyle)
                } catch (e: Throwable) {
                    builder.setStyle(Notification.BigTextStyle().bigText(expandedContent))
                }
            } else {
                builder.setStyle(Notification.BigTextStyle().bigText(expandedContent))
            }

            val notif = builder.build()
            notif.extras.putBoolean("android.requestPromotedOngoing", true)
            notif.extras.putBoolean("android.promotedOngoing", true)
            notif.extras.putBoolean("com.samsung.android.notification.live", true)
            notif.extras.putBoolean("com.samsung.android.notification.promoted", true)
            if (chipText.isNotBlank()) {
                notif.extras.putString("android.shortCriticalText", chipText)
            }
            notif
        } else {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_train_mono)
                .setContentTitle(title)
                .setContentText(shortContent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedContent))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setProgress(100, progress, false)
                .addExtras(extras)
                .setDeleteIntent(stopPendingIntent)
                .setContentIntent(openAppPendingIntent)
                .addAction(R.drawable.ic_train_mono, "Apri App", openAppPendingIntent)
                .addAction(R.drawable.ic_train_mono, "Interrompi", stopPendingIntent)

            if (!isInitial) {
                builder.setOnlyAlertOnce(true)
            }

            if (whenTimestamp > 0) {
                builder.setWhen(whenTimestamp)
                builder.setShowWhen(true)
                if (whenTimestamp > System.currentTimeMillis()) {
                    builder.setUsesChronometer(true)
                }
            }

            builder.build()
        }

        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

        return notification
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopTracking() {
        isTracking = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                nativeMediaSession?.isActive = false
                nativeMediaSession?.release()
                nativeMediaSession = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                nativeMediaSession?.isActive = false
                nativeMediaSession?.release()
                nativeMediaSession = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifica ad alta priorità per il tracciamento live del treno e supporto a capsule nella barra di stato"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatTime(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0) return "--:--"
        val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
        return sdf.format(Date(timestampMs))
    }

    companion object {
        const val CHANNEL_ID = "live_train_tracking_channel_v12"
        const val CHANNEL_NAME = "Tracciamento Treni Live"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TRACKING = "com.example.myapplication.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.myapplication.STOP_TRACKING"

        const val EXTRA_TRAIN_NUMBER = "extra_train_number"
        const val EXTRA_STATION_ID = "extra_station_id"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        fun startService(context: Context, trainNumber: String, stationId: String? = null, timestamp: String? = null) {
            val intent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
                putExtra(EXTRA_STATION_ID, stationId)
                putExtra(EXTRA_TIMESTAMP, timestamp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }
}