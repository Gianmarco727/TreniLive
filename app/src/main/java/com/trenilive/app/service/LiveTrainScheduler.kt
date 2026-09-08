package com.trenilive.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trenilive.app.data.LiveTrainConfig
import com.trenilive.app.data.LiveTrainLeg
import com.trenilive.app.data.LiveTrainManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trainNumber = intent.getStringExtra(LiveTrainScheduler.EXTRA_TRAIN_NUMBER)
        val stationId = intent.getStringExtra(LiveTrainScheduler.EXTRA_STATION_ID)

        Log.d("LiveTrainScheduler", "AlarmReceiver scattato per treno $trainNumber!")

        if (!trainNumber.isNullOrBlank()) {
            val liveManager = LiveTrainManager(context)
            // Se l'utente non lo aveva interrotto per oggi, avvia il tracciamento
            if (!liveManager.isStoppedForToday(trainNumber)) {
                TrainTrackerForegroundService.startService(
                    context = context,
                    trainNumber = trainNumber,
                    stationId = stationId
                )
            }
        }
        LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)
    }
}

object LiveTrainScheduler {

    const val ACTION_START_TRAIN_ALARM = "com.trenilive.app.ACTION_START_TRAIN_ALARM"
    const val EXTRA_TRAIN_NUMBER = "extra_train_number"
    const val EXTRA_STATION_ID = "extra_station_id"

    private fun getAlarmReqCode(trainNum: String): Int {
        val parsed = trainNum.trim().toIntOrNull()
        return if (parsed != null && parsed in 1..99999) {
            2000 + (parsed % 10000)
        } else {
            2000 + (abs(trainNum.trim().hashCode()) % 10000)
        }
    }

    private fun parseDepartureTime(timeStr: String): Pair<Int, Int>? {
        if (timeStr.isBlank() || !timeStr.contains(":")) return null
        return try {
            val parts = timeStr.trim().split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            Pair(h, m)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calcola il prossimo orario di avvio con 15 minuti di preavviso per una specifica tratta.
     */
    fun calculateNextAlarmTimeMsForLeg(leg: LiveTrainLeg, config: LiveTrainConfig): Long? {
        if (!config.isEnabled || config.daysOfWeek.isEmpty()) return null

        val (hour, minute) = parseDepartureTime(leg.scheduledDepartureTime) ?: Pair(6, 0)
        val nowMs = System.currentTimeMillis()

        for (dayOffset in 0..7) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // Preavviso di 15 minuti prima della partenza
                add(Calendar.MINUTE, -15)
            }

            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            if (config.isScheduledForDay(dayOfWeek) && cal.timeInMillis > nowMs) {
                return cal.timeInMillis
            }
        }

        return null
    }

    fun calculateNextAlarmTimeMs(config: LiveTrainConfig): Long? {
        val firstLeg = config.getEffectiveLegs().firstOrNull() ?: return null
        return calculateNextAlarmTimeMsForLeg(firstLeg, config)
    }

    /**
     * Verifica se una specifica tratta è nella sua finestra di viaggio attuale.
     */
    fun isLegInActiveWindow(leg: LiveTrainLeg, config: LiveTrainConfig, context: Context): Boolean {
        if (!config.isEnabled || config.daysOfWeek.isEmpty()) return false

        val liveManager = LiveTrainManager(context)
        if (liveManager.isStoppedForToday(leg.trainNumber)) return false

        val (hour, minute) = parseDepartureTime(leg.scheduledDepartureTime) ?: return true
        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)

        if (!config.isScheduledForDay(currentDayOfWeek)) return false

        val departureCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val windowStartMs = departureCal.timeInMillis - 15 * 60 * 1000L // 15 min prima
        val windowEndMs = departureCal.timeInMillis + 5 * 60 * 60 * 1000L // Finestra fino a 5 ore dopo per il viaggio

        val nowMs = now.timeInMillis
        return nowMs in windowStartMs..windowEndMs
    }

    fun isTrainInActiveWindow(config: LiveTrainConfig, context: Context): Boolean {
        return config.getEffectiveLegs().any { isLegInActiveWindow(it, config, context) }
    }

    /**
     * Programma gli allarmi esatti con AlarmManager ed avvia il tracciamento per ogni tratta componente
     * dei treni attivi nella loro finestra di partenza odierna.
     */
    fun scheduleAlarmsAndCheckActiveTrains(context: Context) {
        val manager = LiveTrainManager(context)
        val liveTrains = manager.getLiveTrains()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        liveTrains.filter { it.isEnabled }.forEach { config ->
            config.getEffectiveLegs().forEach { leg ->
                // 1. Se la specifica tratta è nella sua finestra di viaggio odierna, avvia subito il tracciamento
                if (isLegInActiveWindow(leg, config, context)) {
                    Log.d("LiveTrainScheduler", "Treno ${leg.trainNumber} (Tratta ${leg.legIndex + 1}) nella finestra attiva: avvio servizio tracciamento.")
                    TrainTrackerForegroundService.startService(
                        context = context,
                        trainNumber = leg.trainNumber,
                        stationId = leg.originStationId
                    )
                }

                // 2. Programma l'allarme per il prossimo orario di avvio con preavviso per la specifica tratta
                val nextAlarmMs = calculateNextAlarmTimeMsForLeg(leg, config)
                if (nextAlarmMs != null && alarmManager != null) {
                    val intent = Intent(context, AlarmReceiver::class.java).apply {
                        action = ACTION_START_TRAIN_ALARM
                        putExtra(EXTRA_TRAIN_NUMBER, leg.trainNumber)
                        putExtra(EXTRA_STATION_ID, leg.originStationId)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        getAlarmReqCode(leg.trainNumber),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    try {
                        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            alarmManager.canScheduleExactAlarms()
                        } else true

                        if (canScheduleExact) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    nextAlarmMs,
                                    pendingIntent
                                )
                            } else {
                                alarmManager.setExact(
                                    AlarmManager.RTC_WAKEUP,
                                    nextAlarmMs,
                                    pendingIntent
                                )
                            }
                        } else {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                nextAlarmMs,
                                pendingIntent
                            )
                        }
                        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(Date(nextAlarmMs))
                        Log.d("LiveTrainScheduler", "Alarm programmato con successo per Treno ${leg.trainNumber} alle $formattedDate")
                    } catch (e: Exception) {
                        Log.e("LiveTrainScheduler", "Errore impostazione allarme esatto: ${e.message}, tentato fallback setAndAllowWhileIdle")
                        try {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                nextAlarmMs,
                                pendingIntent
                            )
                        } catch (e2: Exception) {
                            e2.printStackTrace()
                        }
                    }
                }
            }
        }
    }
}