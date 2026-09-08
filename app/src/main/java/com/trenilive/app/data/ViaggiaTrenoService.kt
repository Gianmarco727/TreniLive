package com.trenilive.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrainLeg(
    val legIndex: Int = 0,
    val trainNumber: String = "",
    val category: String = "Treno",
    val originStationId: String = "",
    val originStationName: String = "",
    val destinationStationId: String = "",
    val destinationStationName: String = "",
    val departureTimeFormatted: String = "--:--",
    val arrivalTimeFormatted: String = "--:--",
    val departureTimestampMs: Long = 0L,
    val arrivalTimestampMs: Long = 0L,
    val delayMinutes: Int = 0,
    val platform: String? = null
)

data class RouteSolution(
    val id: String = UUID.randomUUID().toString(),
    val originStationId: String = "",
    val originStationName: String = "",
    val destinationStationId: String = "",
    val destinationStationName: String = "",
    val departureTimeFormatted: String = "--:--",
    val arrivalTimeFormatted: String = "--:--",
    val totalDurationFormatted: String = "",
    val departureTimestampMs: Long = 0L,
    val arrivalTimestampMs: Long = 0L,
    val legs: List<TrainLeg> = emptyList()
) {
    val numberOfTransfers: Int get() = (legs.size - 1).coerceAtLeast(0)

    fun toStationDeparture(): StationDeparture {
        val firstLeg = legs.firstOrNull()
        return StationDeparture(
            trainNumber = legs.joinToString(" + ") { it.trainNumber },
            category = firstLeg?.category ?: "Treno",
            destination = destinationStationName,
            departureTimeFormatted = departureTimeFormatted,
            delayMinutes = firstLeg?.delayMinutes ?: 0,
            originStationId = originStationId,
            departureTimestampMs = departureTimestampMs,
            platform = firstLeg?.platform
        )
    }
}

object ViaggiaTrenoService {

    private const val BASE_URL = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
    private const val CONNECT_TIMEOUT = 10000 // 10s
    private const val READ_TIMEOUT = 10000 // 10s

    private val AUTOCOMPLETE_REGEX = """(\d+)-([A-Z0-9]+)-(\d+)""".toRegex()

    private fun normalizeStationName(name: String): String {
        return name.trim().uppercase()
            .replace("CENTRALE", "C.LE")
            .replace("SANTA MARIA NOVELLA", "S.M.N.")
            .replace("SANTA LUCIA", "S.LUCIA")
            .replace("SAN ", "S. ")
            .replace("PORTA ", "P. ")
            .replace("[^A-Z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    /**
     * Confronta due stazioni per nome o ID gestendo abbreviazioni Trenitalia e codici stazione AV.
     */
    fun matchesStation(stopName: String, stopId: String, queryName: String, queryId: String): Boolean {
        val cleanStopId = stopId.removePrefix("S").removePrefix("s").trim()
        val cleanQueryId = queryId.removePrefix("S").removePrefix("s").trim()

        if (cleanStopId.isNotBlank() && cleanQueryId.isNotBlank()) {
            if (cleanStopId == cleanQueryId) return true
            if (cleanStopId.length >= 3 && cleanQueryId.length >= 3 && cleanStopId.take(3) == cleanQueryId.take(3)) {
                return true
            }
        }

        val normStop = normalizeStationName(stopName)
        val normQuery = normalizeStationName(queryName)

        if (normStop == normQuery) return true
        if (normStop.contains(normQuery) || normQuery.contains(normStop)) return true

        val mainWordQuery = normQuery.split(" ").firstOrNull { it.length > 2 } ?: normQuery
        val mainWordStop = normStop.split(" ").firstOrNull { it.length > 2 } ?: normStop

        return mainWordStop == mainWordQuery
    }

    /**
     * Risolve il numero del treno restituendo l'ID della stazione di partenza e il timestamp.
     * Endpoint: cercaNumeroTrenoTrenoAutocomplete/{NUMERO_TRENO}
     */
    suspend fun resolveTrain(trainNumber: String): ViaggiaTrenoResult<Triple<String, String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanNumber = trainNumber.trim()
                val url = URL("$BASE_URL/cercaNumeroTrenoTrenoAutocomplete/$cleanNumber")
                val responseText = httpGet(url)

                if (responseText.isBlank()) {
                    return@withContext ViaggiaTrenoResult.Error("Treno $cleanNumber non trovato.")
                }

                val match = AUTOCOMPLETE_REGEX.find(responseText)
                    ?: return@withContext ViaggiaTrenoResult.Error("Impossibile trovare il treno $cleanNumber. Verifica il numero inserito.")

                val num = match.groupValues[1]
                val stationId = match.groupValues[2]
                val timestamp = match.groupValues[3]

                ViaggiaTrenoResult.Success(Triple(num, stationId, timestamp))
            } catch (e: Exception) {
                ViaggiaTrenoResult.Error("Errore durante la ricerca del treno: ${e.localizedMessage}", e)
            }
        }

    /**
     * Formatta un timestamp in millisecondi nell'ora locale italiana ("HH:mm").
     */
    fun formatTimestampToLocalTime(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0) return "--:--"
        val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
        return sdf.format(Date(timestampMs))
    }

    private fun parseTimeHoursMinutes(timeStr: String): Pair<Int, Int>? {
        if (timeStr.isBlank() || !timeStr.contains(":")) return null
        return try {
            val parts = timeStr.trim().split(":")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converte numeri romani per i binari programmati (es. "I" -> "1", "III" -> "3") in numeri arabi.
     */
    private fun formatPlatformNumber(platformStr: String): String {
        val trimmed = platformStr.trim().uppercase()
        return when (trimmed) {
            "I" -> "1"
            "II" -> "2"
            "III" -> "3"
            "IV" -> "4"
            "V" -> "5"
            "VI" -> "6"
            "VII" -> "7"
            "VIII" -> "8"
            "IX" -> "9"
            "X" -> "10"
            "XI" -> "11"
            "XII" -> "12"
            "XIII" -> "13"
            "XIV" -> "14"
            "XV" -> "15"
            "XVI" -> "16"
            "XVII" -> "17"
            "XVIII" -> "18"
            "XIX" -> "19"
            "XX" -> "20"
            else -> platformStr.trim()
        }
    }

    /**
     * Estrae in modo sicuro il valore del binario evitando stringhe "null" o chiavi assenti.
     */
    private fun extractPlatform(stopObj: JSONObject): Pair<String?, String?> {
        fun safeString(key: String): String? {
            if (!stopObj.has(key) || stopObj.isNull(key)) return null
            val str = stopObj.optString(key, "").trim()
            if (str.isEmpty() || str.equals("null", ignoreCase = true)) return null
            return formatPlatformNumber(str)
        }

        val progPartenza = safeString("binarioProgrammatoPartenzaDescrizione")
        val progArrivo = safeString("binarioProgrammatoArrivoDescrizione")
        val effPartenza = safeString("binarioEffettivoPartenzaDescrizione")
        val effArrivo = safeString("binarioEffettivoArrivoDescrizione")

        val scheduled = progPartenza ?: progArrivo
        val actual = effPartenza ?: effArrivo

        return Pair(scheduled, actual)
    }

    /**
     * Rileva i giorni della settimana in cui il treno circola effettivamente interrogando i 7 giorni prossimi all'orario di partenza preciso del treno.
     */
    suspend fun detectRunningDaysForTrain(trainNumber: String): Set<Int> = withContext(Dispatchers.IO) {
        val cleanNum = trainNumber.trim()
        val allDays = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        if (cleanNum.isBlank()) return@withContext allDays

        val resolveRes = resolveTrain(cleanNum)
        if (resolveRes !is ViaggiaTrenoResult.Success) {
            return@withContext allDays
        }

        val (num, originStationId, timestamp) = resolveRes.data
        val statusRes = fetchTrainStatus(num, originStationId, timestamp)
        val status = (statusRes as? ViaggiaTrenoResult.Success)?.data

        val depTimeMs = status?.stops?.firstOrNull()?.scheduledTimeMs ?: System.currentTimeMillis()
        val depCal = Calendar.getInstance().apply { timeInMillis = depTimeMs }
        val depHour = depCal.get(Calendar.HOUR_OF_DAY)
        val depMinute = depCal.get(Calendar.MINUTE)

        val nowMs = System.currentTimeMillis()

        try {
            val daysJobs = (0..6).map { dayOffset ->
                async {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                        set(Calendar.HOUR_OF_DAY, depHour)
                        set(Calendar.MINUTE, depMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

                    val departuresRes = fetchStationDepartures(originStationId, cal.time)
                    val isRunning = if (departuresRes is ViaggiaTrenoResult.Success) {
                        departuresRes.data.any { it.trainNumber == num }
                    } else false

                    if (isRunning) dayOfWeek else null
                }
            }

            val detectedDays = daysJobs.awaitAll().filterNotNull().toSet()
            if (detectedDays.isNotEmpty()) detectedDays else allDays
        } catch (e: Exception) {
            allDays
        }
    }

    /**
     * Recupera lo stato in tempo reale del treno data la stazione di partenza, il numero e il timestamp.
     * Endpoint: andamentoTreno/{ID_STAZIONE}/{NUMERO_TRENO}/{TIMESTAMP}
     */
    suspend fun fetchTrainStatus(
        trainNumber: String,
        departureStationId: String,
        timestamp: String
    ): ViaggiaTrenoResult<TrainStatus> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/andamentoTreno/$departureStationId/$trainNumber/$timestamp")
            val jsonString = httpGet(url)

            if (jsonString.isBlank()) {
                return@withContext ViaggiaTrenoResult.Error("Impossibile recuperare i dati del treno.")
            }

            val json = JSONObject(jsonString)

            val category = json.optString("categoria", "").ifBlank { "Treno" }
            val delayMinutes = json.optInt("ritardo", 0)
            val rawLastStation = json.optString("stazioneUltimoRilevamento", "")
            val lastDetectedStation = if (rawLastStation.isBlank() || rawLastStation == "--") {
                "Non ancora partito"
            } else {
                rawLastStation
            }

            val isCancelled = json.optBoolean("subCancellato", false) || json.optInt("provvedimento", 0) == 1
            val cancellationReason = if (isCancelled) {
                json.optString("esito", "Treno soppresso")
            } else null

            val originStationName = json.optString("origine", "Partenza")
            val destinationStationName = json.optString("destinazione", "Arrivo")

            val rawStops = mutableListOf<TrainStop>()
            val fermateArray = json.optJSONArray("fermate")

            if (fermateArray != null) {
                for (i in 0 until fermateArray.length()) {
                    val stopObj = fermateArray.getJSONObject(i)
                    val name = stopObj.optString("stazione", "Stazione $i")
                    val id = stopObj.optString("id", "")

                    val clStatus = stopObj.optString("clStatus", "")
                    val isPassed = clStatus == "S" ||
                            stopObj.optLong("partenzaReale", 0L) > 0 ||
                            stopObj.optLong("arrivoReale", 0L) > 0

                    val progPartenza = stopObj.optLong("programmata", 0L).takeIf { it > 0 }
                    val progArrivo = stopObj.optLong("programmataArrivo", 0L).takeIf { it > 0 }
                    val scheduledTime = progPartenza ?: progArrivo

                    val partReale = stopObj.optLong("partenzaReale", 0L).takeIf { it > 0 }
                    val arrReale = stopObj.optLong("arrivoReale", 0L).takeIf { it > 0 }
                    val eff = stopObj.optLong("effettiva", 0L).takeIf { it > 0 }
                    val actualTime = partReale ?: arrReale ?: eff ?: scheduledTime

                    val stopDelay = stopObj.optInt("ritardo", delayMinutes)

                    val (scheduledPlatform, actualPlatform) = extractPlatform(stopObj)
                    val stopCancelled = stopObj.optBoolean("fermataSoppressa", false)

                    rawStops.add(
                        TrainStop(
                            stationName = name,
                            stationId = id,
                            isPassed = isPassed,
                            scheduledTimeMs = scheduledTime,
                            actualOrEstimatedTimeMs = actualTime,
                            delayMinutes = stopDelay,
                            scheduledPlatform = scheduledPlatform,
                            actualPlatform = actualPlatform,
                            isCancelled = stopCancelled
                        )
                    )
                }
            }

            // Normalizzazione stato fermate: se una stazione successiva risulta superata, tutte quelle precedenti sono superate!
            val lastPassedIdx = rawStops.indexOfLast { it.isPassed }
            val stops = if (lastPassedIdx >= 0) {
                rawStops.mapIndexed { idx, stop ->
                    if (idx <= lastPassedIdx) stop.copy(isPassed = true) else stop
                }
            } else {
                rawStops
            }

            val totalStops = stops.size
            val passedCount = stops.count { it.isPassed }
            val progressPercentage = when {
                totalStops <= 1 -> 0
                passedCount >= totalStops -> 100
                else -> ((passedCount.toFloat() / (totalStops - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
            }

            val nextStop = stops.firstOrNull { !it.isPassed } ?: stops.lastOrNull()

            val trainStatus = TrainStatus(
                trainNumber = trainNumber,
                category = category,
                departureStationId = departureStationId,
                timestamp = timestamp,
                delayMinutes = delayMinutes,
                lastDetectedStation = lastDetectedStation,
                isCancelled = isCancelled,
                cancellationReason = cancellationReason,
                stops = stops,
                progressPercentage = progressPercentage,
                nextStop = nextStop,
                originStationName = originStationName,
                destinationStationName = destinationStationName
            )

            ViaggiaTrenoResult.Success(trainStatus)
        } catch (e: Exception) {
            ViaggiaTrenoResult.Error("Errore durante il recupero dell'andamento: ${e.localizedMessage}", e)
        }
    }

    /**
     * Recupera lo stato di una specifica partenza (gestendo anche date future).
     */
    suspend fun fetchTrainStatusForDeparture(
        trainNumber: String,
        departureStationId: String,
        departureTimestampMs: Long
    ): ViaggiaTrenoResult<TrainStatus> = withContext(Dispatchers.IO) {
        if (departureTimestampMs > 0) {
            val liveResult = fetchTrainStatus(trainNumber, departureStationId, departureTimestampMs.toString())
            if (liveResult is ViaggiaTrenoResult.Success) {
                return@withContext liveResult
            }
        }

        val resolveRes = resolveTrain(trainNumber)
        if (resolveRes is ViaggiaTrenoResult.Success) {
            val (num, depId, ts) = resolveRes.data
            val statusRes = fetchTrainStatus(num, depId, ts)
            if (statusRes is ViaggiaTrenoResult.Success) {
                val template = statusRes.data
                val formattedDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(departureTimestampMs))

                val futureStops = template.stops.map { stop ->
                    stop.copy(
                        isPassed = false,
                        delayMinutes = 0
                    )
                }

                val futureStatus = template.copy(
                    departureStationId = departureStationId,
                    timestamp = departureTimestampMs.toString(),
                    delayMinutes = 0,
                    lastDetectedStation = "Programmato per il $formattedDate",
                    stops = futureStops,
                    progressPercentage = 0,
                    nextStop = futureStops.firstOrNull()
                )

                return@withContext ViaggiaTrenoResult.Success(futureStatus)
            }
        }

        ViaggiaTrenoResult.Error("Impossibile recuperare i dettagli per il treno $trainNumber.")
    }

    /**
     * Suggerimento e autocompletamento stazioni da query testuale.
     * Endpoint: autocompletaStazione/{QUERY}
     */
    suspend fun autocompleteStation(query: String): ViaggiaTrenoResult<List<StationInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanQuery = query.trim()
                if (cleanQuery.length < 2) {
                    return@withContext ViaggiaTrenoResult.Success(emptyList())
                }

                val url = URL("$BASE_URL/autocompletaStazione/${cleanQuery.uppercase()}")
                val responseText = httpGet(url)

                val list = mutableListOf<StationInfo>()
                responseText.lines().forEach { line ->
                    if (line.isNotBlank() && line.contains("|")) {
                        val parts = line.split("|")
                        if (parts.size >= 2) {
                            list.add(StationInfo(name = parts[0].trim(), id = parts[1].trim()))
                        }
                    }
                }

                ViaggiaTrenoResult.Success(list)
            } catch (e: Exception) {
                ViaggiaTrenoResult.Error("Errore ricerca stazioni: ${e.localizedMessage}", e)
            }
        }

    /**
     * Recupera il tabellone delle partenze per una determinata stazione a partire da una certa data/ora.
     * Endpoint: partenze/{ID_STAZIONE}/{TIMESTAMP_FORMATTATO}
     */
    suspend fun fetchStationDepartures(
        stationId: String,
        date: Date = Date()
    ): ViaggiaTrenoResult<List<StationDeparture>> =
        withContext(Dispatchers.IO) {
            try {
                val dateStr = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).format(date)
                val formattedDateStr = dateStr.replace(" ", "%20")
                val url = URL("$BASE_URL/partenze/$stationId/$formattedDateStr")

                val jsonString = httpGet(url)
                if (jsonString.isBlank() || jsonString == "Error") {
                    return@withContext ViaggiaTrenoResult.Success(emptyList())
                }

                val jsonArray = JSONArray(jsonString)
                val departures = mutableListOf<StationDeparture>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val num = obj.optString("numeroTreno", "")
                    val cat = obj.optString("categoria", "REG")
                    val dest = obj.optString("destinazione", "Destinazione sconosciuta")
                    val delay = obj.optInt("ritardo", 0)
                    val originId = obj.optString("codOrigine", stationId)
                    val (scheduledPlat, actualPlat) = extractPlatform(obj)
                    val platform = actualPlat ?: scheduledPlat

                    val compTime = obj.optString("compOrarioPartenza", "").trim()
                    val orarioPartenzaMs = obj.optLong("orarioPartenza", 0L)
                    val dataPartenzaTrenoMs = obj.optLong("dataPartenzaTreno", 0L)

                    val departureTimeFormatted = if (compTime.isNotBlank() && compTime != "--:--") {
                        compTime
                    } else if (orarioPartenzaMs > 0 && orarioPartenzaMs != dataPartenzaTrenoMs) {
                        formatTimestampToLocalTime(orarioPartenzaMs)
                    } else {
                        "--:--"
                    }

                    val departureTimestampMs = if (orarioPartenzaMs > 0 && orarioPartenzaMs != dataPartenzaTrenoMs) {
                        orarioPartenzaMs
                    } else {
                        val (h, m) = parseTimeHoursMinutes(departureTimeFormatted) ?: Pair(0, 0)
                        if (dataPartenzaTrenoMs > 0) {
                            dataPartenzaTrenoMs + (h * 3600 + m * 60) * 1000L
                        } else {
                            date.time
                        }
                    }

                    if (num.isNotBlank()) {
                        departures.add(
                            StationDeparture(
                                trainNumber = num,
                                category = cat,
                                destination = dest,
                                departureTimeFormatted = departureTimeFormatted,
                                delayMinutes = delay,
                                originStationId = originId,
                                departureTimestampMs = departureTimestampMs,
                                platform = platform
                            )
                        )
                    }
                }

                ViaggiaTrenoResult.Success(departures)
            } catch (e: Exception) {
                ViaggiaTrenoResult.Error("Errore tabellone partenze: ${e.localizedMessage}", e)
            }
        }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Cerca i treni e le soluzioni con o senza cambi tra una stazione di partenza e una di arrivo.
     * Se esistono treni diretti, li restituisce subito evitando percorsi a ritroso o inutili coincidenze.
     */
    suspend fun fetchRouteSolutionsWithTransfers(
        originStationId: String,
        originNameQuery: String,
        destinationQuery: String,
        date: Date = Date(),
        minSolutions: Int = 5
    ): ViaggiaTrenoResult<List<RouteSolution>> = withContext(Dispatchers.IO) {
        try {
            val solutions = mutableListOf<RouteSolution>()
            val processedSolutionKeys = mutableSetOf<String>()

            var currentSearchDate = date
            var attempts = 0

            val cleanOriginName = originNameQuery.trim()
            var cleanDestName = destinationQuery.trim()
            var destStationId = ""

            when (val destAuto = autocompleteStation(cleanDestName)) {
                is ViaggiaTrenoResult.Success -> {
                    val found = destAuto.data.firstOrNull()
                    if (found != null) {
                        destStationId = found.id
                        cleanDestName = found.name
                    }
                }
                is ViaggiaTrenoResult.Error -> {}
            }

            while (solutions.size < minSolutions && attempts < 4) {
                val departuresRes = fetchStationDepartures(originStationId, currentSearchDate)
                if (departuresRes is ViaggiaTrenoResult.Error) {
                    if (solutions.isNotEmpty()) break
                    return@withContext departuresRes
                }

                val rawDepartures = (departuresRes as ViaggiaTrenoResult.Success).data
                if (rawDepartures.isEmpty()) {
                    currentSearchDate = Date(currentSearchDate.time + 60 * 60 * 1000L)
                    attempts++
                    continue
                }

                // 1. RECUPERA LO STATO DEI TRENI DI PARTENZA IN PARALLELO
                val departuresWithStatus = rawDepartures.take(15).map { dep ->
                    async {
                        val statusRes = if (isSameDay(currentSearchDate, Date())) {
                            resolveTrain(dep.trainNumber).let { resolve ->
                                if (resolve is ViaggiaTrenoResult.Success) {
                                    fetchTrainStatus(resolve.data.first, resolve.data.second, resolve.data.third)
                                } else null
                            }
                        } else {
                            fetchTrainStatusForDeparture(dep.trainNumber, originStationId, dep.departureTimestampMs)
                        }

                        val status = (statusRes as? ViaggiaTrenoResult.Success)?.data
                        if (status != null) Pair(dep, status) else null
                    }
                }.awaitAll().filterNotNull()

                // PASSAGGIO 1: RICERCA SOLUZIONI DIRETTE
                for ((dep, status) in departuresWithStatus) {
                    val stops = status.stops
                    if (stops.isEmpty()) continue

                    val originIdx = stops.indexOfFirst { stop ->
                        matchesStation(stop.stationName, stop.stationId, cleanOriginName, originStationId)
                    }
                    if (originIdx < 0) continue

                    val boardingStop = stops[originIdx]

                    if (isSameDay(currentSearchDate, Date())) {
                        if (status.isCancelled || status.progressPercentage >= 100 || boardingStop.isPassed) continue
                    }

                    val destIdx = stops.indexOfFirst { stop ->
                        matchesStation(stop.stationName, stop.stationId, cleanDestName, destStationId)
                    }

                    if (destIdx > originIdx) {
                        val alightingStop = stops[destIdx]
                        val key = "${dep.trainNumber}_${boardingStop.scheduledTimeMs}"

                        if (!processedSolutionKeys.contains(key)) {
                            processedSolutionKeys.add(key)

                            val depTimeStr = formatTimestampToLocalTime(boardingStop.actualOrEstimatedTimeMs ?: boardingStop.scheduledTimeMs)
                            val arrTimeStr = formatTimestampToLocalTime(alightingStop.actualOrEstimatedTimeMs ?: alightingStop.scheduledTimeMs)
                            val depMs = boardingStop.actualOrEstimatedTimeMs ?: boardingStop.scheduledTimeMs ?: dep.departureTimestampMs
                            val arrMs = alightingStop.actualOrEstimatedTimeMs ?: alightingStop.scheduledTimeMs ?: depMs

                            val durationMinutes = ((arrMs - depMs) / (1000 * 60)).coerceAtLeast(0)
                            val durationStr = "${durationMinutes / 60}h ${durationMinutes % 60}m"

                            val singleLeg = TrainLeg(
                                legIndex = 0,
                                trainNumber = dep.trainNumber,
                                category = dep.category,
                                originStationId = originStationId,
                                originStationName = boardingStop.stationName,
                                destinationStationId = alightingStop.stationId,
                                destinationStationName = alightingStop.stationName,
                                departureTimeFormatted = depTimeStr,
                                arrivalTimeFormatted = arrTimeStr,
                                departureTimestampMs = depMs,
                                arrivalTimestampMs = arrMs,
                                delayMinutes = status.delayMinutes,
                                platform = dep.platform
                            )

                            solutions.add(
                                RouteSolution(
                                    originStationId = originStationId,
                                    originStationName = boardingStop.stationName,
                                    destinationStationId = alightingStop.stationId,
                                    destinationStationName = alightingStop.stationName,
                                    departureTimeFormatted = depTimeStr,
                                    arrivalTimeFormatted = arrTimeStr,
                                    totalDurationFormatted = durationStr,
                                    departureTimestampMs = depMs,
                                    arrivalTimestampMs = arrMs,
                                    legs = listOf(singleLeg)
                                )
                            )
                        }
                    }
                }

                // SE ABBIAMO TROVATO SOLUZIONI DIRETTE, LE RESTITUIMO SUBITO SENZA CERCARE CAMBI A RITROSO!
                if (solutions.isNotEmpty()) {
                    break
                }

                // PASSAGGIO 2: SE NON CI SONO TRENI DIRETTI, CERCA SOLUZIONI CON CAMBIO (ES. CONEGLIANO ➔ BOLOGNA)
                for ((dep, status) in departuresWithStatus) {
                    val stops = status.stops
                    if (stops.isEmpty()) continue

                    val originIdx = stops.indexOfFirst { stop ->
                        matchesStation(stop.stationName, stop.stationId, cleanOriginName, originStationId)
                    }
                    if (originIdx < 0) continue

                    val boardingStop = stops[originIdx]

                    if (isSameDay(currentSearchDate, Date())) {
                        if (status.isCancelled || status.progressPercentage >= 100 || boardingStop.isPassed) continue
                    }

                    // Prendi solo le fermate SUCCESSIVE a quella di origine per evitare percorsi a ritroso
                    val candidateJunctionStops = stops.drop(originIdx + 1).take(6)

                    for (junctionStop in candidateJunctionStops) {
                        val leg1ArrMs = junctionStop.actualOrEstimatedTimeMs ?: junctionStop.scheduledTimeMs ?: continue
                        val junctionStationId = junctionStop.stationId

                        val transferDate = Date(leg1ArrMs + 4 * 60 * 1000L)
                        val leg2DeparturesRes = fetchStationDepartures(junctionStationId, transferDate)
                        if (leg2DeparturesRes !is ViaggiaTrenoResult.Success) continue

                        val candidateLeg2Departures = leg2DeparturesRes.data.take(10)

                        val leg2DeparturesWithStatus = candidateLeg2Departures.map { leg2Dep ->
                            async {
                                if (leg2Dep.trainNumber == dep.trainNumber) return@async null

                                val leg2StatusRes = if (isSameDay(transferDate, Date())) {
                                    resolveTrain(leg2Dep.trainNumber).let { resolve ->
                                        if (resolve is ViaggiaTrenoResult.Success) {
                                            fetchTrainStatus(resolve.data.first, resolve.data.second, resolve.data.third)
                                        } else null
                                    }
                                } else {
                                    fetchTrainStatusForDeparture(leg2Dep.trainNumber, junctionStationId, leg2Dep.departureTimestampMs)
                                }

                                val leg2Status = (leg2StatusRes as? ViaggiaTrenoResult.Success)?.data
                                if (leg2Status != null) Pair(leg2Dep, leg2Status) else null
                            }
                        }.awaitAll().filterNotNull()

                        for ((leg2Dep, leg2Status) in leg2DeparturesWithStatus) {
                            val leg2Stops = leg2Status.stops
                            if (leg2Stops.isEmpty()) continue

                            val leg2JunctionIdx = leg2Stops.indexOfFirst { s ->
                                matchesStation(s.stationName, s.stationId, junctionStop.stationName, junctionStationId)
                            }
                            val leg2DestIdx = leg2Stops.indexOfFirst { s ->
                                matchesStation(s.stationName, s.stationId, cleanDestName, destStationId)
                            }

                            if (leg2JunctionIdx >= 0 && leg2DestIdx > leg2JunctionIdx) {
                                val leg2Boarding = leg2Stops[leg2JunctionIdx]
                                val leg2Alighting = leg2Stops[leg2DestIdx]

                                val leg2DepMs = leg2Boarding.actualOrEstimatedTimeMs ?: leg2Boarding.scheduledTimeMs ?: leg2Dep.departureTimestampMs

                                val transferWaitMinutes = ((leg2DepMs - leg1ArrMs) / (1000 * 60))
                                if (transferWaitMinutes in 4..120) {
                                    val solutionKey = "${dep.trainNumber}_${leg2Dep.trainNumber}_${boardingStop.scheduledTimeMs}"

                                    if (!processedSolutionKeys.contains(solutionKey)) {
                                        processedSolutionKeys.add(solutionKey)

                                        val leg1DepStr = formatTimestampToLocalTime(boardingStop.actualOrEstimatedTimeMs ?: boardingStop.scheduledTimeMs)
                                        val leg1ArrStr = formatTimestampToLocalTime(leg1ArrMs)
                                        val leg1DepMs = boardingStop.actualOrEstimatedTimeMs ?: boardingStop.scheduledTimeMs ?: dep.departureTimestampMs

                                        val leg2DepStr = formatTimestampToLocalTime(leg2DepMs)
                                        val leg2ArrMs = leg2Alighting.actualOrEstimatedTimeMs ?: leg2Alighting.scheduledTimeMs ?: leg2DepMs
                                        val leg2ArrStr = formatTimestampToLocalTime(leg2ArrMs)

                                        val totalDurationMinutes = ((leg2ArrMs - leg1DepMs) / (1000 * 60)).coerceAtLeast(0)
                                        val durationStr = "${totalDurationMinutes / 60}h ${totalDurationMinutes % 60}m"

                                        val leg1 = TrainLeg(
                                            legIndex = 0,
                                            trainNumber = dep.trainNumber,
                                            category = dep.category,
                                            originStationId = originStationId,
                                            originStationName = boardingStop.stationName,
                                            destinationStationId = junctionStationId,
                                            destinationStationName = junctionStop.stationName,
                                            departureTimeFormatted = leg1DepStr,
                                            arrivalTimeFormatted = leg1ArrStr,
                                            departureTimestampMs = leg1DepMs,
                                            arrivalTimestampMs = leg1ArrMs,
                                            delayMinutes = status.delayMinutes,
                                            platform = dep.platform
                                        )

                                        val leg2 = TrainLeg(
                                            legIndex = 1,
                                            trainNumber = leg2Dep.trainNumber,
                                            category = leg2Dep.category,
                                            originStationId = junctionStationId,
                                            originStationName = junctionStop.stationName,
                                            destinationStationId = leg2Alighting.stationId,
                                            destinationStationName = leg2Alighting.stationName,
                                            departureTimeFormatted = leg2DepStr,
                                            arrivalTimeFormatted = leg2ArrStr,
                                            departureTimestampMs = leg2DepMs,
                                            arrivalTimestampMs = leg2ArrMs,
                                            delayMinutes = leg2Status.delayMinutes,
                                            platform = leg2Dep.platform
                                        )

                                        solutions.add(
                                            RouteSolution(
                                                originStationId = originStationId,
                                                originStationName = boardingStop.stationName,
                                                destinationStationId = leg2Alighting.stationId,
                                                destinationStationName = leg2Alighting.stationName,
                                                departureTimeFormatted = leg1DepStr,
                                                arrivalTimeFormatted = leg2ArrStr,
                                                totalDurationFormatted = durationStr,
                                                departureTimestampMs = leg1DepMs,
                                                arrivalTimestampMs = leg2ArrMs,
                                                legs = listOf(leg1, leg2)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (solutions.size >= minSolutions) break
                    }
                }

                currentSearchDate = Date(currentSearchDate.time + 60 * 60 * 1000L)
                attempts++
            }

            val sortedSolutions = solutions.sortedBy { it.departureTimestampMs }
            ViaggiaTrenoResult.Success(sortedSolutions)
        } catch (e: Exception) {
            ViaggiaTrenoResult.Error("Errore ricerca tratta: ${e.localizedMessage}", e)
        }
    }

    /**
     * Cerca i treni disponibili mantenendo la firma per compatibilità.
     */
    suspend fun fetchRouteSolutions(
        originStationId: String,
        originNameQuery: String,
        destinationQuery: String,
        date: Date = Date(),
        minSolutions: Int = 5
    ): ViaggiaTrenoResult<List<StationDeparture>> = withContext(Dispatchers.IO) {
        when (val res = fetchRouteSolutionsWithTransfers(originStationId, originNameQuery, destinationQuery, date, minSolutions)) {
            is ViaggiaTrenoResult.Success -> {
                val list = res.data.map { it.toStationDeparture() }
                ViaggiaTrenoResult.Success(list)
            }
            is ViaggiaTrenoResult.Error -> ViaggiaTrenoResult.Error(res.message, res.cause)
        }
    }

    private fun httpGet(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "*/*")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode in 200..200) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
            } else {
                ""
            }
        } finally {
            connection.disconnect()
        }
    }
}