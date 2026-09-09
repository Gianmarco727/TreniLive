package com.trenilive.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class FavoritesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("train_favorites", Context.MODE_PRIVATE)

    fun getFavoriteTrains(): List<String> {
        val jsonStr = prefs.getString("favorite_numbers_ordered", null)
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Retro-compatibilita per migrare i vecchi preferiti salvati in HashSet senza ordine
        val oldSet = prefs.getStringSet("favorite_numbers", null)
        if (!oldSet.isNullOrEmpty()) {
            val migratedList = oldSet.toList()
            saveFavoriteList(migratedList)
            prefs.edit().remove("favorite_numbers").commit()
            return migratedList
        }

        return emptyList()
    }

    private fun saveFavoriteList(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs.edit().putString("favorite_numbers_ordered", array.toString()).commit()
    }

    fun isFavorite(trainNumber: String): Boolean {
        if (trainNumber.isBlank()) return false
        return getFavoriteTrains().contains(trainNumber.trim())
    }

    fun toggleFavorite(trainNumber: String): Boolean {
        val trimmed = trainNumber.trim()
        if (trimmed.isBlank()) return false

        val current = getFavoriteTrains().toMutableList()
        val newState: Boolean
        if (current.contains(trimmed)) {
            current.remove(trimmed)
            newState = false
        } else {
            // Gli ultimi preferiti aggiunti devono stare in testa davanti alla lista
            current.add(0, trimmed)
            newState = true
        }

        saveFavoriteList(current)
        return newState
    }
}