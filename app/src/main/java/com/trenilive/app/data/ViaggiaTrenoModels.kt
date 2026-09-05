package com.trenilive.app.data

data class TrainStop(
    val stationName: String,
    val stationId: String,
    val isPassed: Boolean,
    val scheduledTimeMs: Long?,
    val actualOrEstimatedTimeMs: Long?,
    val delayMinutes: Int,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val isCancelled: Boolean = false
)

data class TrainStatus(
    val trainNumber: String,
    val category: String,
    val departureStationId: String,
    val timestamp: String,
    val delayMinutes: Int,
    val lastDetectedStation: String,
    val isCancelled: Boolean,
    val cancellationReason: String?,
    val stops: List<TrainStop>,
    val progressPercentage: Int, // 0 to 100
    val nextStop: TrainStop?,
    val originStationName: String,
    val destinationStationName: String
)

data class StationInfo(
    val name: String,
    val id: String
)

data class StationDeparture(
    val trainNumber: String,
    val category: String,
    val destination: String,
    val departureTimeFormatted: String,
    val delayMinutes: Int,
    val originStationId: String,
    val departureTimestampMs: Long,
    val platform: String?
)

sealed class ViaggiaTrenoResult<out T> {
    data class Success<out T>(val data: T) : ViaggiaTrenoResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : ViaggiaTrenoResult<Nothing>()
}