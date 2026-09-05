package com.trenilive.app.data

import android.content.Context
import android.content.SharedPreferences

class FavoritesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("train_favorites", Context.MODE_PRIVATE)

    fun getFavoriteTrains(): Set<String> {
        val set = prefs.getStringSet("favorite_numbers", null) ?: emptySet()
        return HashSet(set)
    }

    fun isFavorite(trainNumber: String): Boolean {
        if (trainNumber.isBlank()) return false
        return getFavoriteTrains().contains(trainNumber.trim())
    }

    fun toggleFavorite(trainNumber: String): Boolean {
        val trimmed = trainNumber.trim()
        if (trimmed.isBlank()) return false

        val current = getFavoriteTrains().toMutableSet()
        val newState: Boolean
        if (current.contains(trimmed)) {
            current.remove(trimmed)
            newState = false
        } else {
            current.add(trimmed)
            newState = true
        }

        prefs.edit().putStringSet("favorite_numbers", HashSet(current)).commit()
        return newState
    }
}