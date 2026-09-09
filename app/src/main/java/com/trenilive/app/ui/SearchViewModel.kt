package com.trenilive.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.trenilive.app.data.RouteSolution
import com.trenilive.app.data.TrainStatus

class SearchViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var trainStatus by mutableStateOf<TrainStatus?>(null)
    var routeSolutions by mutableStateOf<List<RouteSolution>>(emptyList())
    var expandedSolutionId by mutableStateOf<String?>(null)
}