package com.trenilive.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LiveTrainManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMediaSessionBypassEnabled(): Boolean {
        return prefs.getBoolean(KEY_MEDIA_SESSION_BYPASS, false)
    }

    fun setMediaSessionBypassEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_SESSION_BYPASS, enabled).commit()
    }

    fun isSamsungHintDismissed(): Boolean {
        return prefs.getBoolean(KEY_SAMSUNG_HINT_DISMISSED, false)
    }

    fun setSamsungHintDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_SAMSUNG_HINT_DISMISSED, dismissed).commit()
    }

    fun setStoppedForToday(trainNumber: String, isStopped: Boolean) {
        val clean = trainNumber.trim()
        if (clean.isBlank()) return
        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date())
        val key = "stopped_date_$clean"
        if (isStopped) {
            prefs.edit().putString(key, todayDateStr).commit()
        } else {
            prefs.edit().remove(key).commit()
        }
    }

    fun isStoppedForToday(trainNumber: String): Boolean {
        val clean = trainNumber.trim()
        if (clean.isBlank()) return false
        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date())
        val savedDate = prefs.getString("stopped_date_$clean", null)
        return savedDate == todayDateStr
    }

    fun getLiveTrains(): List<LiveTrainConfig> {
        val jsonString = prefs.getString(KEY_LIVE_TRAINS, "[]") ?: "[]"
        val list = mutableListOf<LiveTrainConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val num = obj.optString("trainNumber", "")
                val originId = obj.optString("originStationId", "")
                val originName = obj.optString("originStationName", "")
                val destName = obj.optString("destinationStationName", "")
                val time = obj.optString("scheduledDepartureTime", "")
                val isEnabled = obj.optBoolean("isEnabled", true)

                val daysSet = mutableSetOf<Int>()
                val daysArray = obj.optJSONArray("daysOfWeek")
                if (daysArray != null) {
                    for (j in 0 until daysArray.length()) {
                        daysSet.add(daysArray.getInt(j))
                    }
                }

                val monitoredList = mutableListOf<MonitoredStop>()
                val stopsArray = obj.optJSONArray("monitoredStops")
                if (stopsArray != null) {
                    for (k in 0 until stopsArray.length()) {
                        val sObj = stopsArray.getJSONObject(k)
                        monitoredList.add(
                            MonitoredStop(
                                stationId = sObj.optString("stationId", ""),
                                stationName = sObj.optString("stationName", ""),
                                progressPercentage = sObj.optInt("progressPercentage", 0)
                            )
                        )
                    }
                }

                if (num.isNotBlank()) {
                    list.add(
                        LiveTrainConfig(
                            id = id,
                            trainNumber = num,
                            daysOfWeek = daysSet,
                            originStationId = originId,
                            originStationName = originName,
                            destinationStationName = destName,
                            scheduledDepartureTime = time,
                            isEnabled = isEnabled,
                            monitoredStops = monitoredList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveLiveTrain(config: LiveTrainConfig): List<LiveTrainConfig> {
        val currentList = getLiveTrains().toMutableList()
        val index = currentList.indexOfFirst { it.trainNumber == config.trainNumber || it.id == config.id }
        if (index >= 0) {
            currentList[index] = config
        } else {
            currentList.add(config)
        }
        persistList(currentList)
        // Se salviamo un treno abilitato, azzeriamo l'eventuale flag di stop per oggi
        if (config.isEnabled) {
            setStoppedForToday(config.trainNumber, false)
        }
        return currentList
    }

    fun toggleTrainEnabled(id: String): List<LiveTrainConfig> {
        val currentList = getLiveTrains().map {
            if (it.id == id || it.trainNumber == id) {
                val newEnabled = !it.isEnabled
                if (!newEnabled) {
                    setStoppedForToday(it.trainNumber, true)
                } else {
                    setStoppedForToday(it.trainNumber, false)
                }
                it.copy(isEnabled = newEnabled)
            } else it
        }
        persistList(currentList)
        return currentList
    }

    fun removeLiveTrain(id: String): List<LiveTrainConfig> {
        val target = getLiveTrains().firstOrNull { it.id == id || it.trainNumber == id }
        if (target != null) {
            setStoppedForToday(target.trainNumber, true)
        }
        val currentList = getLiveTrains().filterNot { it.id == id || it.trainNumber == id }
        persistList(currentList)
        return currentList
    }

    private fun persistList(list: List<LiveTrainConfig>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("trainNumber", item.trainNumber)
                put("originStationId", item.originStationId)
                put("originStationName", item.originStationName)
                put("destinationStationName", item.destinationStationName)
                put("scheduledDepartureTime", item.scheduledDepartureTime)
                put("isEnabled", item.isEnabled)

                val daysArray = JSONArray()
                item.daysOfWeek.forEach { daysArray.put(it) }
                put("daysOfWeek", daysArray)

                val stopsArray = JSONArray()
                item.monitoredStops.forEach { stop ->
                    val sObj = JSONObject().apply {
                        put("stationId", stop.stationId)
                        put("stationName", stop.stationName)
                        put("progressPercentage", stop.progressPercentage)
                    }
                    stopsArray.put(sObj)
                }
                put("monitoredStops", stopsArray)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_LIVE_TRAINS, jsonArray.toString()).commit()
    }

    companion object {
        private const val PREFS_NAME = "live_train_tracker_prefs"
        private const val KEY_LIVE_TRAINS = "saved_live_trains"
        private const val KEY_MEDIA_SESSION_BYPASS = "key_media_session_bypass"
        private const val KEY_SAMSUNG_HINT_DISMISSED = "key_samsung_hint_dismissed"
    }
}