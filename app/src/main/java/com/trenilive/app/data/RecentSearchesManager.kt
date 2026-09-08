package com.trenilive.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RecentRouteSearch(
    val originName: String,
    val destinationName: String
)

class RecentSearchesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRecentSearches(): List<RecentRouteSearch> {
        val jsonString = prefs.getString(KEY_RECENT_SEARCHES, "[]") ?: "[]"
        val list = mutableListOf<RecentRouteSearch>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val origin = obj.optString("originName", "")
                val dest = obj.optString("destinationName", "")
                if (origin.isNotBlank() && dest.isNotBlank()) {
                    list.add(RecentRouteSearch(origin, dest))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addRecentSearch(originName: String, destinationName: String) {
        val cleanOrigin = originName.trim()
        val cleanDest = destinationName.trim()
        if (cleanOrigin.isBlank() || cleanDest.isBlank()) return

        val currentList = getRecentSearches().toMutableList()
        // Rimuovi eventuali duplicati
        currentList.removeAll {
            it.originName.equals(cleanOrigin, ignoreCase = true) &&
                    it.destinationName.equals(cleanDest, ignoreCase = true)
        }
        // Aggiungi in cima
        currentList.add(0, RecentRouteSearch(cleanOrigin, cleanDest))

        // Mantieni solo le ultime 5
        val limitedList = currentList.take(5)

        val jsonArray = JSONArray()
        limitedList.forEach { item ->
            val obj = JSONObject().apply {
                put("originName", item.originName)
                put("destinationName", item.destinationName)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_SEARCHES, jsonArray.toString()).apply()
    }

    fun removeRecentSearch(originName: String, destinationName: String) {
        val currentList = getRecentSearches().filterNot {
            it.originName.equals(originName, ignoreCase = true) &&
                    it.destinationName.equals(destinationName, ignoreCase = true)
        }
        val jsonArray = JSONArray()
        currentList.forEach { item ->
            val obj = JSONObject().apply {
                put("originName", item.originName)
                put("destinationName", item.destinationName)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_SEARCHES, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "recent_searches_prefs"
        private const val KEY_RECENT_SEARCHES = "key_recent_searches"
    }
}