package com.trenilive.app.data

import java.util.Calendar

data class MonitoredStop(
    val stationId: String = "",
    val stationName: String = "",
    val progressPercentage: Int = 0
)

data class LiveTrainLeg(
    val legIndex: Int = 0,
    val trainNumber: String = "",
    val category: String = "Treno",
    val originStationId: String = "",
    val originStationName: String = "",
    val destinationStationId: String = "",
    val destinationStationName: String = "",
    val scheduledDepartureTime: String = "",
    val scheduledArrivalTime: String = "",
    val monitoredStops: List<MonitoredStop> = emptyList()
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
    val monitoredStops: List<MonitoredStop> = emptyList(), // Fino a 4 fermate selezionate per i punti milestone sulla barra
    val legs: List<LiveTrainLeg> = emptyList() // Supporto tratte componenti per le soluzioni con cambi
) {
    /**
     * Restituisce la lista effettiva delle tratte. Se `legs` e vuoto (treno singolo retrocompatibile),
     * genera una singola tratta sintetica basata sui campi principali del `LiveTrainConfig`.
     */
    fun getEffectiveLegs(): List<LiveTrainLeg> {
        return if (legs.isNotEmpty()) {
            legs
        } else {
            listOf(
                LiveTrainLeg(
                    legIndex = 0,
                    trainNumber = trainNumber,
                    category = "Treno",
                    originStationId = originStationId,
                    originStationName = originStationName,
                    destinationStationId = "",
                    destinationStationName = destinationStationName,
                    scheduledDepartureTime = scheduledDepartureTime,
                    scheduledArrivalTime = "",
                    monitoredStops = monitoredStops
                )
            )
        }
    }

    /**
     * Restituisce la lista effettiva delle fermate monitorate combinando le fermate di tutte le tratte.
     */
    fun getEffectiveMonitoredStops(): List<MonitoredStop> {
        if (monitoredStops.isNotEmpty()) {
            return monitoredStops
        }
        val effLegs = getEffectiveLegs()
        return effLegs.flatMap { it.monitoredStops }.distinctBy { it.stationId }.take(4)
    }

    /**
     * Restituisce un etichetta riassuntiva dei treni (es. "16824 + 9430" se con cambio).
     */
    fun getDisplayTrainNumbers(): String {
        val effLegs = getEffectiveLegs()
        return if (effLegs.size > 1) {
            effLegs.joinToString(" + ") { it.trainNumber }
        } else {
            trainNumber
        }
    }

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