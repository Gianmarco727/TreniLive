package com.example.myapplication.service

import android.content.Context
import com.example.myapplication.data.LiveTrainManager
import java.util.Calendar

object LiveTrainScheduler {

    /**
     * Controlla i treni salvati nel Live Tracker e avvia il Foreground Service
     * per i treni abilitati che sono programmati per il giorno della settimana corrente.
     */
    fun checkAndStartScheduledTrains(context: Context) {
        val manager = LiveTrainManager(context)
        val liveTrains = manager.getLiveTrains()

        val calendar = Calendar.getInstance()
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        val activeToday = liveTrains.filter { it.isScheduledForDay(currentDayOfWeek) }

        activeToday.forEach { trainConfig ->
            TrainTrackerForegroundService.startService(
                context = context,
                trainNumber = trainConfig.trainNumber,
                stationId = trainConfig.originStationId
            )
        }
    }
}