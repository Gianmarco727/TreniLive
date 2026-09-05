package com.example.myapplication.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
            content = "Ricerca dati in tempo reale in corso...",
            progress = 0
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
            content = "Errore aggiornamento: $message",
            progress = 0
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

        val title = "${status.category} ${status.trainNumber} $delayText"

        val nextStop = status.nextStop
        val content = if (status.isCancelled) {
            "Treno soppresso"
        } else if (status.progressPercentage >= 100) {
            "Treno giunto a destinazione (${status.destinationStationName})"
        } else if (nextStop != null) {
            val nextPlat = (nextStop.actualPlatform ?: nextStop.scheduledPlatform)?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }
            val timeStr = formatTime(nextStop.actualOrEstimatedTimeMs)
            val platStr = if (!nextPlat.isNullOrBlank()) " • Bin. $nextPlat" else ""
            "Prossima: ${nextStop.stationName} ($timeStr)$platStr"
        } else {
            "Ultimo rilevamento: ${status.lastDetectedStation}"
        }

        return buildNotification(
            title = title,
            content = content,
            progress = status.progressPercentage
        )
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .addAction(R.mipmap.ic_launcher, "Interrompi", stopPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        }

        return builder.build()
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica persistente per il monitoraggio live del treno"
                setShowBadge(false)
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
        const val CHANNEL_ID = "live_train_tracking_channel"
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