package com.example.myapplication.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ViaggiaTrenoService {

    private const val BASE_URL = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
    private const val CONNECT_TIMEOUT = 10000 // 10s
    private const val READ_TIMEOUT = 10000 // 10s

    private val AUTOCOMPLETE_REGEX = """(\d+)-([A-Z0-9]+)-(\d+)""".toRegex()

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

            val stops = mutableListOf<TrainStop>()
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

                    stops.add(
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
                    val timeFormatted = obj.optString("compOrarioPartenza", "--:--")
                    val delay = obj.optInt("ritardo", 0)
                    val originId = obj.optString("codOrigine", stationId)
                    val departureTimestampMs = obj.optLong("dataPartenzaTreno", 0L)
                    val (scheduledPlat, actualPlat) = extractPlatform(obj)
                    val platform = actualPlat ?: scheduledPlat

                    if (num.isNotBlank()) {
                        departures.add(
                            StationDeparture(
                                trainNumber = num,
                                category = cat,
                                destination = dest,
                                departureTimeFormatted = timeFormatted,
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

    /**
     * Verifica se un treno viaggia nella DIREZIONE CORRETTA (stazione di partenza PRIMA della stazione di arrivo).
     */
    private suspend fun isTrainInCorrectDirection(
        trainNumber: String,
        originStationId: String,
        departureTimestampMs: Long,
        originNameQuery: String,
        destinationNameQuery: String
    ): Boolean {
        var stops: List<TrainStop>? = null

        if (departureTimestampMs > 0) {
            val statusRes = fetchTrainStatus(trainNumber, originStationId, departureTimestampMs.toString())
            if (statusRes is ViaggiaTrenoResult.Success) {
                stops = statusRes.data.stops
            }
        }

        if (stops.isNullOrEmpty()) {
            val resolveRes = resolveTrain(trainNumber)
            if (resolveRes is ViaggiaTrenoResult.Success) {
                val (num, depId, ts) = resolveRes.data
                val statusRes = fetchTrainStatus(num, depId, ts)
                if (statusRes is ViaggiaTrenoResult.Success) {
                    stops = statusRes.data.stops
                }
            }
        }

        if (stops.isNullOrEmpty()) return false

        val cleanOrigin = originNameQuery.trim().lowercase()
        val cleanDest = destinationNameQuery.trim().lowercase()

        val originIdx = stops.indexOfFirst {
            it.stationId == originStationId ||
                    it.stationName.lowercase().contains(cleanOrigin) ||
                    (cleanOrigin.length >= 4 && it.stationName.lowercase().contains(cleanOrigin.take(4)))
        }

        val destIdx = stops.indexOfFirst {
            it.stationName.lowercase().contains(cleanDest) ||
                    (cleanDest.length >= 4 && it.stationName.lowercase().contains(cleanDest.take(4)))
        }

        return originIdx >= 0 && destIdx >= 0 && originIdx < destIdx
    }

    /**
     * Cerca i treni disponibili tra una stazione di partenza e una di arrivo per la data/ora indicata.
     */
    suspend fun fetchRouteSolutions(
        originStationId: String,
        originNameQuery: String,
        destinationQuery: String,
        date: Date = Date(),
        minSolutions: Int = 5
    ): ViaggiaTrenoResult<List<StationDeparture>> = withContext(Dispatchers.IO) {
        try {
            val validSolutions = mutableListOf<StationDeparture>()
            val processedTrainNumbers = mutableSetOf<String>()

            var currentSearchDate = date
            var attempts = 0

            while (validSolutions.size < minSolutions && attempts < 6) {
                val departuresRes = fetchStationDepartures(originStationId, currentSearchDate)
                if (departuresRes is ViaggiaTrenoResult.Error) {
                    if (validSolutions.isNotEmpty()) break
                    return@withContext departuresRes
                }

                val rawDepartures = (departuresRes as ViaggiaTrenoResult.Success).data
                if (rawDepartures.isEmpty()) break

                for (dep in rawDepartures) {
                    if (processedTrainNumbers.contains(dep.trainNumber)) continue
                    processedTrainNumbers.add(dep.trainNumber)

                    val isMatch = isTrainInCorrectDirection(
                        trainNumber = dep.trainNumber,
                        originStationId = originStationId,
                        departureTimestampMs = dep.departureTimestampMs,
                        originNameQuery = originNameQuery,
                        destinationNameQuery = destinationQuery
                    )

                    if (isMatch) {
                        validSolutions.add(dep)
                        if (validSolutions.size >= minSolutions) break
                    }
                }

                currentSearchDate = Date(currentSearchDate.time + 60 * 60 * 1000L)
                attempts++
            }

            ViaggiaTrenoResult.Success(validSolutions)
        } catch (e: Exception) {
            ViaggiaTrenoResult.Error("Errore ricerca tratta: ${e.localizedMessage}", e)
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
            if (responseCode in 200..299) {
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