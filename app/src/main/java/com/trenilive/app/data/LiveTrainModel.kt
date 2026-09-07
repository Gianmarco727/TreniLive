package com.trenilive.app.data

import java.util.Calendar

data class MonitoredStop(
    val stationId: String = "",
    val stationName: String = "",
    val progressPercentage: Int = 0
)

data class LiveTrainConfig(
    val id: String,
    val trainNumber: String,
    val daysOfWeek: Set<Int>, // Java Calendar values: Calendar.SUNDAY (1), MONDAY (2), ..., SATURDAY (7)
    val originStationId: String = "",
    val originStationName: String = "",
    val destinationStationName: String = "",
    val scheduledDepartureTime: String = "",
    val isEnabled: Boolean = true,
    val monitoredStops: List<MonitoredStop> = emptyList() // Fino a 4 fermate selezionate per i punti milestone sulla barra
) {
    fun isScheduledForDay(calendarDayOfWeek: Int): Boolean {
        return isEnabled && daysOfWeek.contains(calendarDayOfWeek)
    }

    fun getDaysFormatted(): String {
        if (daysOfWeek.size == 7) return "Tutti i giorni"
        if (daysOfWeek.size == 5 && daysOfWeek.containsAll(setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY))) {
            return "Lun - Ven"
        }
        if (daysOfWeek.size == 2 && daysOfWeek.containsAll(setOf(Calendar.SATURDAY, Calendar.SUNDAY))) {
            return "Fine settimana"
        }
        val dayNames = mapOf(
            Calendar.MONDAY to "Lun",
            Calendar.TUESDAY to "Mar",
            Calendar.WEDNESDAY to "Mer",
            Calendar.THURSDAY to "Gio",
            Calendar.FRIDAY to "Ven",
            Calendar.SATURDAY to "Sab",
            Calendar.SUNDAY to "Dom"
        )
        val orderedDays = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        return orderedDays.filter { daysOfWeek.contains(it) }
            .mapNotNull { dayNames[it] }
            .joinToString(", ")
            .ifEmpty { "Nessun giorno" }
    }
}