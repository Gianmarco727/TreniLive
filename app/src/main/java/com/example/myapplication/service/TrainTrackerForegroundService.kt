package com.example.myapplication.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            chipText = "LIVETRAIN"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notifySafely(notificationManager, NOTIFICATION_ID, notification)

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
            chipText = "ERR"
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifySafely(notificationManager, NOTIFICATION_ID, notification)
    }

    private fun buildNotificationFromStatus(status: TrainStatus): Notification {
        val delayText = when {
            status.isCancelled -> "• SOPPRESSO"
            status.delayMinutes > 0 -> "• +${status.delayMinutes} min"
            status.delayMinutes < 0 -> "• ${status.delayMinutes} min"
            else -> "• In orario"
        }

        val chipText = when {
            status.isCancelled -> "SOPPR."
            status.delayMinutes > 0 -> "+${status.delayMinutes}m"
            status.delayMinutes < 0 -> "${status.delayMinutes}m"
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

        return buildNotification(
            title = title,
            shortContent = shortContent,
            expandedContent = expandedContent,
            progress = status.progressPercentage,
            chipText = chipText,
            whenTimestamp = nextStopTimestamp
        )
    }

    private fun buildNotification(
        title: String,
        shortContent: String,
        expandedContent: String,
        progress: Int,
        chipText: String = "",
        whenTimestamp: Long = 0L
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
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(shortContent)
                .setSubText("Live Activity")
                .setStyle(Notification.BigTextStyle().bigText(expandedContent))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, false)
                .setExtras(extras)
                .setContentIntent(openAppPendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                        "Interrompi",
                        stopPendingIntent
                    ).build()
                )

            if (whenTimestamp > 0) {
                builder.setWhen(whenTimestamp)
                builder.setShowWhen(true)
            }

            // Iniezione dei metodi nativi di Android 15/16/17 e Samsung One UI nel Builder prima del .build()
            try {
                val setPromotedMethod = builder.javaClass.methods.firstOrNull { it.name == "setRequestPromotedOngoing" }
                setPromotedMethod?.invoke(builder, true)
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            try {
                val setShortTextMethod = builder.javaClass.methods.firstOrNull { it.name == "setShortCriticalText" }
                setShortTextMethod?.invoke(builder, chipText)
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            // Supporto Android 16/17 ProgressStyle
            try {
                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                val setProgressMethod = progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                setProgressMethod.invoke(progressStyle, progress)

                val setStyleMethod = builder.javaClass.getMethod("setStyle", Class.forName("android.app.Notification\$Style"))
                setStyleMethod.invoke(builder, progressStyle)
            } catch (e: Throwable) {
                // In caso di versioni precedenti, mantiene il BigTextStyle già impostato
            }

            val notification = builder.build()
            notification.extras.putBoolean("android.requestPromotedOngoing", true)
            notification.extras.putBoolean("android.promotedOngoing", true)
            if (chipText.isNotBlank()) {
                notification.extras.putString("android.shortCriticalText", chipText)
            }
            return notification
        } else {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(shortContent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedContent))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, false)
                .addExtras(extras)
                .setContentIntent(openAppPendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Interrompi", stopPendingIntent)

            if (whenTimestamp > 0) {
                builder.setWhen(whenTimestamp)
                builder.setShowWhen(true)
            }

            return builder.build()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(notificationManager: NotificationManager, id: Int, notification: Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopTracking() {
        isTracking = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifica per il tracciamento live del treno con supporto a pillole e capsule nella barra di stato"
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
        const val CHANNEL_ID = "live_train_tracking_channel_v2"
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