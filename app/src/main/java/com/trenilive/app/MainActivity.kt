package com.trenilive.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.trenilive.app.data.*
import com.trenilive.app.service.LiveTrainScheduler
import com.trenilive.app.service.TrainTrackerForegroundService
import com.trenilive.app.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainTabScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainTabScreen(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Cerca treni", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Live tracker", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 0) 1f else 0f)
                    .graphicsLayer {
                        alpha = if (selectedTab == 0) 1f else 0f
                    }
            ) {
                TrainTrackerScreen(onSwitchToLiveTracker = { selectedTab = 1 })
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 1) 1f else 0f)
                    .graphicsLayer {
                        alpha = if (selectedTab == 1) 1f else 0f
                    }
            ) {
                LiveTrackerScreen(isVisible = selectedTab == 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrainTrackerScreen(
    modifier: Modifier = Modifier,
    onSwitchToLiveTracker: () -> Unit = {}
) {
    val context = LocalContext.current
    val favoritesManager = remember { FavoritesManager(context) }
    val recentSearchesManager = remember { RecentSearchesManager(context) }

    var favoriteList by remember { mutableStateOf(favoritesManager.getFavoriteTrains()) }
    var recentSearches by remember { mutableStateOf(recentSearchesManager.getRecentSearches()) }

    var trainNumberInput by rememberSaveable { mutableStateOf("") }
    var favoriteToRemove by remember { mutableStateOf<String?>(null) }

    var originQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var selectedOriginStation by remember { mutableStateOf<StationInfo?>(null) }
    var originSuggestions by remember { mutableStateOf<List<StationInfo>>(emptyList()) }

    var destinationQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var selectedDestinationStation by remember { mutableStateOf<StationInfo?>(null) }
    var destinationSuggestions by remember { mutableStateOf<List<StationInfo>>(emptyList()) }

    var selectedDateMs by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var trainStatus by remember { mutableStateOf<TrainStatus?>(null) }
    var routeSolutions by remember { mutableStateOf<List<RouteSolution>>(emptyList()) }

    var expandedSolutionId by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var previousSuggestionsCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(originSuggestions, destinationSuggestions) {
        val currentCount = originSuggestions.size + destinationSuggestions.size
        if (currentCount > 0 && previousSuggestionsCount == 0) {
            scrollState.animateScrollBy(140f)
        } else if (currentCount == 0 && previousSuggestionsCount > 0) {
            scrollState.animateScrollBy(-140f)
        }
        previousSuggestionsCount = currentCount
    }

    favoriteToRemove?.let { trainNum ->
        AlertDialog(
            onDismissRequest = { favoriteToRemove = null },
            title = { Text("Rimuovere dai preferiti?", fontWeight = FontWeight.Bold) },
            text = { Text("Vuoi rimuovere il treno $trainNum dai tuoi preferiti salvati?") },
            confirmButton = {
                Button(
                    onClick = {
                        favoritesManager.toggleFavorite(trainNum)
                        favoriteList = favoritesManager.getFavoriteTrains()
                        favoriteToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8102E))
                ) {
                    Text("Rimuovi", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { favoriteToRemove = null }) {
                    Text("Annulla")
                }
            }
        )
    }

    val searchByTrainNumber = { numberToSearch: String ->
        focusManager.clearFocus()
        val trimmed = numberToSearch.trim()
        if (trimmed.isNotBlank()) {
            errorMessage = null
            isLoading = true

            coroutineScope.launch {
                when (val resolveRes = ViaggiaTrenoService.resolveTrain(trimmed)) {
                    is ViaggiaTrenoResult.Success -> {
                        val (num, stationId, timestamp) = resolveRes.data
                        when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(num, stationId, timestamp)) {
                            is ViaggiaTrenoResult.Success -> {
                                trainStatus = statusRes.data
                            }
                            is ViaggiaTrenoResult.Error -> {
                                errorMessage = statusRes.message
                            }
                        }
                    }
                    is ViaggiaTrenoResult.Error -> {
                        errorMessage = resolveRes.message
                    }
                }
                isLoading = false
            }
        }
    }

    val searchByStations = {
        focusManager.clearFocus()
        val originName = originQuery.text.trim()
        val destName = destinationQuery.text.trim()

        if (originName.isEmpty()) {
            errorMessage = "La stazione di partenza è obbligatoria."
        } else if (destName.isEmpty()) {
            errorMessage = "La stazione di arrivo è obbligatoria per la ricerca per tratta."
        } else {
            errorMessage = null
            isLoading = true
            trainStatus = null
            routeSolutions = emptyList()
            expandedSolutionId = null

            coroutineScope.launch {
                recentSearchesManager.addRecentSearch(originName, destName)
                recentSearches = recentSearchesManager.getRecentSearches()

                var originStation = selectedOriginStation
                if (originStation == null) {
                    when (val autoRes = ViaggiaTrenoService.autocompleteStation(originName)) {
                        is ViaggiaTrenoResult.Success -> {
                            originStation = autoRes.data.firstOrNull()
                        }
                        is ViaggiaTrenoResult.Error -> {}
                    }
                }

                if (originStation == null) {
                    errorMessage = "Impossibile trovare la stazione di partenza '$originName'."
                    isLoading = false
                    return@launch
                }

                val searchDate = Date(selectedDateMs)
                when (val routeRes = ViaggiaTrenoService.fetchRouteSolutionsWithTransfers(
                    originStationId = originStation.id,
                    originNameQuery = originName,
                    destinationQuery = destName,
                    date = searchDate,
                    minSolutions = 5
                )) {
                    is ViaggiaTrenoResult.Success -> {
                        routeSolutions = routeRes.data
                        if (routeSolutions.isEmpty()) {
                            val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(searchDate)
                            errorMessage = "Nessuna soluzione trovata tra ${originStation.name} e $destName a partire dal $timeStr."
                        }
                    }
                    is ViaggiaTrenoResult.Error -> {
                        errorMessage = routeRes.message
                    }
                }
                isLoading = false
            }
        }
    }

    val showDatePicker = {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val newCal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                newCal.set(Calendar.YEAR, year)
                newCal.set(Calendar.MONTH, month)
                newCal.set(Calendar.DAY_OF_MONTH, day)
                selectedDateMs = newCal.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val showTimePicker = {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val newCal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                newCal.set(Calendar.HOUR_OF_DAY, hour)
                newCal.set(Calendar.MINUTE, minute)
                selectedDateMs = newCal.timeInMillis
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "TreniLive",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Cerca per numero treno o per tratta completa (anche con cambi)",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        if (favoriteList.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "I miei preferiti",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Tieni premuto per rimuovere",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                favoriteList.forEach { favNum ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                trainNumberInput = favNum
                                searchByTrainNumber(favNum)
                            },
                            onLongClick = {
                                favoriteToRemove = favNum
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFC8102E),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = favNum,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        if (recentSearches.isNotEmpty()) {
            Text(
                text = "Ricerche recenti",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recentSearches.forEach { search ->
                    SuggestionChip(
                        onClick = {
                            originQuery = TextFieldValue(
                                text = search.originName,
                                selection = TextRange(search.originName.length)
                            )
                            destinationQuery = TextFieldValue(
                                text = search.destinationName,
                                selection = TextRange(search.destinationName.length)
                            )
                            selectedOriginStation = null
                            selectedDestinationStation = null
                            selectedDateMs = System.currentTimeMillis()
                            searchByStations()
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${search.originName} ➔ ${search.destinationName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "1",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "Ricerca diretta per numero treno",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = trainNumberInput,
                    onValueChange = { tfv ->
                        trainNumberInput = tfv
                    },
                    placeholder = { Text("Numero treno (es. 9410)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { searchByTrainNumber(trainNumberInput) }
                    ),
                    trailingIcon = {
                        if (trainNumberInput.isNotBlank()) {
                            IconButton(onClick = { trainNumberInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancella",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                if (trainNumberInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { searchByTrainNumber(trainNumberInput) },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC8102E),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cerca treno $trainNumberInput", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "2",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "Oppure cerca per tratta completa",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = originQuery,
                    onValueChange = { tfv ->
                        originQuery = tfv
                        selectedOriginStation = null
                        trainNumberInput = ""
                        val query = tfv.text
                        if (query.length >= 2) {
                            coroutineScope.launch {
                                when (val res = ViaggiaTrenoService.autocompleteStation(query)) {
                                    is ViaggiaTrenoResult.Success -> {
                                        originSuggestions = res.data
                                    }
                                    is ViaggiaTrenoResult.Error -> {}
                                }
                            }
                        } else {
                            originSuggestions = emptyList()
                        }
                    },
                    placeholder = { Text("Stazione di partenza") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (originQuery.text.isNotBlank()) {
                            IconButton(onClick = {
                                originQuery = TextFieldValue("")
                                selectedOriginStation = null
                                originSuggestions = emptyList()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancella",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                if (originSuggestions.isNotEmpty() && selectedOriginStation == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 4.dp
                    ) {
                        Column {
                            originSuggestions.take(5).forEach { station ->
                                Text(
                                    text = station.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedOriginStation = station
                                            originQuery = TextFieldValue(
                                                text = station.name,
                                                selection = TextRange(station.name.length)
                                            )
                                            originSuggestions = emptyList()
                                        }
                                        .padding(12.dp),
                                    fontSize = 14.sp
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = destinationQuery,
                    onValueChange = { tfv ->
                        destinationQuery = tfv
                        selectedDestinationStation = null
                        trainNumberInput = ""
                        val query = tfv.text
                        if (query.length >= 2) {
                            coroutineScope.launch {
                                when (val res = ViaggiaTrenoService.autocompleteStation(query)) {
                                    is ViaggiaTrenoResult.Success -> {
                                        destinationSuggestions = res.data
                                    }
                                    is ViaggiaTrenoResult.Error -> {}
                                }
                            }
                        } else {
                            destinationSuggestions = emptyList()
                        }
                    },
                    placeholder = { Text("Stazione di arrivo") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (destinationQuery.text.isNotBlank()) {
                            IconButton(onClick = {
                                destinationQuery = TextFieldValue("")
                                selectedDestinationStation = null
                                destinationSuggestions = emptyList()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancella",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                if (destinationSuggestions.isNotEmpty() && selectedDestinationStation == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 4.dp
                    ) {
                        Column {
                            destinationSuggestions.take(5).forEach { station ->
                                Text(
                                    text = station.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDestinationStation = station
                                            destinationQuery = TextFieldValue(
                                                text = station.name,
                                                selection = TextRange(station.name.length)
                                            )
                                            destinationSuggestions = emptyList()
                                        }
                                        .padding(12.dp),
                                    fontSize = 14.sp
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = showDatePicker,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarToday,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(selectedDateMs)),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = showTimePicker,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(selectedDateMs)),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { searchByStations() },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC8102E),
                        contentColor = Color.White
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = "Cerca soluzioni tratta",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = trainStatus != null && !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            trainStatus?.let { status ->
                Spacer(modifier = Modifier.height(20.dp))
                TrainStatusCard(
                    status = status,
                    isFavorite = favoritesManager.isFavorite(status.trainNumber),
                    onToggleFavorite = {
                        favoritesManager.toggleFavorite(status.trainNumber)
                        favoriteList = favoritesManager.getFavoriteTrains()
                    },
                    onCloseDetail = {
                        trainStatus = null
                    },
                    userBoardingStation = originQuery.text,
                    userAlightingStation = destinationQuery.text,
                    onSwitchToLiveTracker = onSwitchToLiveTracker
                )
            }
        }

        // LISTA SOLUZIONI TROVATE (CON LAYOUT TRATTA SELEZIONATA ANTISOVRAAPPOSIZIONE BINARI E SENTENCE CASE)
        if (routeSolutions.isNotEmpty() && !isLoading) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Soluzioni di viaggio disponibili (${routeSolutions.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            routeSolutions.forEach { solution ->
                val isDirect = solution.numberOfTransfers == 0
                val singleLeg = solution.legs.firstOrNull()
                val isExpanded = expandedSolutionId == solution.id

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            expandedSolutionId = if (isExpanded) null else solution.id
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isExpanded) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    ) {
                        // INTESTAZIONE CARD
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${solution.departureTimeFormatted} ➔ ${solution.arrivalTimeFormatted}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    // Badge Cambi / Diretto
                                    Surface(
                                        color = if (isDirect) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color(0xFFE65100).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = if (isDirect) "Diretto" else "${solution.numberOfTransfers} Cambio",
                                            color = if (isDirect) Color(0xFF2E7D32) else Color(0xFFE65100),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = if (isDirect && singleLeg != null) {
                                        "${singleLeg.category} ${singleLeg.trainNumber}"
                                    } else {
                                        "Treni: ${solution.legs.joinToString(" + ") { "${it.category} ${it.trainNumber}" }}"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 2.dp)
                                )

                                Text(
                                    text = "Durata: ${solution.totalDurationFormatted} • ${solution.originStationName} ➔ ${solution.destinationStationName}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        // VISTA ESPANSA DELLA CARD (DETTAGLI IN TEMPO REALE COMPLETI MATERIAL 3)
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                                if (isDirect && singleLeg != null) {
                                    val legStatus = singleLeg.status

                                    if (legStatus != null) {
                                        // 1. INFO RITARDO E STATO REALE
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val (delayColor, delayText) = when {
                                                legStatus.isCancelled -> Color(0xFFD32F2F) to "SOPPRESSO"
                                                legStatus.delayMinutes > 0 -> Color(0xFFE65100) to "+${legStatus.delayMinutes} min"
                                                legStatus.delayMinutes < 0 -> Color(0xFF2E7D32) to "${legStatus.delayMinutes} min"
                                                else -> Color(0xFF2E7D32) to "IN ORARIO"
                                            }

                                            Text(
                                                text = "Stato in tempo reale",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )

                                            Surface(
                                                color = delayColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(20.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, delayColor)
                                            ) {
                                                Text(
                                                    text = delayText,
                                                    color = delayColor,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // 2. ULTIMO RILEVAMENTO
                                        Text(
                                            text = "Ultimo rilevamento",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            text = legStatus.lastDetectedStation,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(top = 1.dp, bottom = 10.dp)
                                        )

                                        // 3. PROSSIMA FERMATA + BINARIO
                                        legStatus.nextStop?.let { next ->
                                            val nextPlat = (next.actualPlatform ?: next.scheduledPlatform)?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }

                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "Prossima fermata",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Text(
                                                            text = next.stationName,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                        nextPlat?.let { platform ->
                                                            Text(
                                                                text = "Binario: $platform",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                            )
                                                        }
                                                    }

                                                    Text(
                                                        text = formatTime(next.actualOrEstimatedTimeMs),
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                        }

                                        // 4. BARRA DI PROGRESSO MATERIAL 3 AVANZAMENTO TRENO (PILL SHAPE 100% M3)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Avanzamento treno totale",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${legStatus.progressPercentage}%",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        LinearProgressIndicator(
                                            progress = { legStatus.progressPercentage / 100f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp)
                                                .height(10.dp)
                                                .clip(CircleShape),
                                            color = Color(0xFFC8102E),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                            strokeCap = StrokeCap.Round
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // 5. TRATTA SELEZIONATA (SALITA E DISCESA CON ENTRAMBI I BINARI - NO WRAP)
                                        val stopsCount = legStatus.stops.size
                                        if (stopsCount > 1) {
                                            val boardingIdx = legStatus.stops.indexOfFirst {
                                                ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, singleLeg.originStationName, singleLeg.originStationId)
                                            }.takeIf { it >= 0 } ?: 0

                                            val alightingIdx = legStatus.stops.indexOfFirst {
                                                ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, singleLeg.destinationStationName, singleLeg.destinationStationId)
                                            }.takeIf { it >= 0 } ?: (stopsCount - 1)

                                            val boardingStop = legStatus.stops.getOrNull(boardingIdx)
                                            val alightingStop = legStatus.stops.getOrNull(alightingIdx)

                                            val rawBoardingPlat = boardingStop?.actualPlatform ?: boardingStop?.scheduledPlatform ?: singleLeg.platform
                                            val rawAlightingPlat = alightingStop?.actualPlatform ?: alightingStop?.scheduledPlatform

                                            val boardingPlatform = rawBoardingPlat?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }
                                            val alightingPlatform = rawAlightingPlat?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }

                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(14.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(
                                                        text = "Tratta selezionata",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Salita: ${boardingStop?.stationName ?: singleLeg.originStationName} (${singleLeg.departureTimeFormatted})",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                        )
                                                        boardingPlatform?.let { p ->
                                                            Text(
                                                                text = "Binario $p",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF2E7D32)
                                                            )
                                                        }
                                                    }

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Discesa: ${alightingStop?.stationName ?: singleLeg.destinationStationName} (${singleLeg.arrivalTimeFormatted})",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                        )
                                                        alightingPlatform?.let { p ->
                                                            Text(
                                                                text = "Binario $p",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF2E7D32)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Partenza: ${singleLeg.originStationName} (${singleLeg.departureTimeFormatted})",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Arrivo: ${singleLeg.destinationStationName} (${singleLeg.arrivalTimeFormatted})",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                } else {
                                    // DETTAGLI PER SOLUZIONE CON CAMBI (1+ CAMBI)
                                    solution.legs.forEachIndexed { idx, leg ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Tratta ${idx + 1}: ${leg.category} ${leg.trainNumber}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    leg.platform?.let { plat ->
                                                        Text(
                                                            text = "Binario $plat",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF2E7D32)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "${leg.originStationName} (${leg.departureTimeFormatted}) ➔ ${leg.destinationStationName} (${leg.arrivalTimeFormatted})",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }

                                        if (idx < solution.legs.size - 1) {
                                            val nextLeg = solution.legs[idx + 1]
                                            val waitMin = ((nextLeg.departureTimestampMs - leg.arrivalTimestampMs) / (1000 * 60)).coerceAtLeast(0)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.TransferWithinAStation,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Cambio a ${leg.destinationStationName} • Attesa coincidenza: $waitMin min",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            programRouteSolutionInLiveTracker(
                                                solution = solution,
                                                context = context,
                                                onComplete = {
                                                    onSwitchToLiveTracker()
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Programma nel Tracker",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

private suspend fun programRouteSolutionInLiveTracker(
    solution: RouteSolution,
    context: Context,
    onComplete: () -> Unit
) {
    val liveManager = LiveTrainManager(context)

    val savedLegs = mutableListOf<LiveTrainLeg>()
    var allDetectedDays = setOf<Int>()

    solution.legs.forEachIndexed { index, leg ->
        val detectedDays = ViaggiaTrenoService.detectRunningDaysForTrain(leg.trainNumber)
        if (allDetectedDays.isEmpty()) {
            allDetectedDays = detectedDays
        } else {
            allDetectedDays = allDetectedDays.intersect(detectedDays).ifEmpty { detectedDays }
        }

        val autoMonitoredStops = mutableListOf<MonitoredStop>()

        when (val statusRes = ViaggiaTrenoService.fetchTrainStatusForDeparture(
            trainNumber = leg.trainNumber,
            departureStationId = leg.originStationId,
            departureTimestampMs = leg.departureTimestampMs
        )) {
            is ViaggiaTrenoResult.Success -> {
                val status = statusRes.data
                val totalStopsCount = status.stops.size
                if (totalStopsCount > 1) {
                    val boardingIdx = status.stops.indexOfFirst {
                        it.stationName.contains(leg.originStationName, ignoreCase = true)
                    }.takeIf { it >= 0 } ?: 0

                    val alightingIdx = status.stops.indexOfFirst {
                        it.stationName.contains(leg.destinationStationName, ignoreCase = true)
                    }.takeIf { it >= 0 } ?: (totalStopsCount - 1)

                    val boardingStop = status.stops.getOrNull(boardingIdx)
                    val alightingStop = status.stops.getOrNull(alightingIdx)

                    if (boardingStop != null) {
                        val pct = ((boardingIdx.toFloat() / (totalStopsCount - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
                        autoMonitoredStops.add(
                            MonitoredStop(
                                stationId = boardingStop.stationId,
                                stationName = boardingStop.stationName,
                                progressPercentage = pct
                            )
                        )
                    }

                    if (alightingStop != null && alightingStop.stationId != boardingStop?.stationId) {
                        val pct = ((alightingIdx.toFloat() / (totalStopsCount - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
                        autoMonitoredStops.add(
                            MonitoredStop(
                                stationId = alightingStop.stationId,
                                stationName = alightingStop.stationName,
                                progressPercentage = pct
                            )
                        )
                    }
                }
            }
            is ViaggiaTrenoResult.Error -> {}
        }

        savedLegs.add(
            LiveTrainLeg(
                legIndex = index,
                trainNumber = leg.trainNumber,
                category = leg.category,
                originStationId = leg.originStationId,
                originStationName = leg.originStationName,
                destinationStationId = leg.destinationStationId,
                destinationStationName = leg.destinationStationName,
                scheduledDepartureTime = leg.departureTimeFormatted,
                scheduledArrivalTime = leg.arrivalTimeFormatted,
                monitoredStops = autoMonitoredStops
            )
        )
    }

    val primaryTrainNum = solution.legs.joinToString(" + ") { it.trainNumber }
    val newConfig = LiveTrainConfig(
        id = UUID.randomUUID().toString(),
        trainNumber = primaryTrainNum,
        daysOfWeek = if (allDetectedDays.isNotEmpty()) allDetectedDays else setOf(1, 2, 3, 4, 5, 6, 7),
        originStationId = solution.originStationId,
        originStationName = solution.originStationName,
        destinationStationName = solution.destinationStationName,
        scheduledDepartureTime = solution.departureTimeFormatted,
        isEnabled = true,
        legs = savedLegs
    )

    liveManager.saveLiveTrain(newConfig)

    LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)

    Toast.makeText(
        context,
        "Soluzione $primaryTrainNum salvata nel Live Tracker!",
        Toast.LENGTH_LONG
    ).show()

    onComplete()
}

@Composable
fun LiveTrackerScreen(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val liveManager = remember { LiveTrainManager(context) }
    val favoritesManager = remember { FavoritesManager(context) }

    var liveTrains by remember { mutableStateOf(liveManager.getLiveTrains()) }
    val favoriteTrains by remember { mutableStateOf(favoritesManager.getFavoriteTrains()) }
    var isMediaSessionBypass by remember { mutableStateOf(liveManager.isMediaSessionBypassEnabled()) }

    var inputTrainNumber by remember { mutableStateOf("") }
    var selectedDays by remember {
        mutableStateOf(
            setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
                Calendar.SATURDAY,
                Calendar.SUNDAY
            )
        )
    }

    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    var selectedConfigForStops by remember { mutableStateOf<LiveTrainConfig?>(null) }
    var selectedConfigForDays by remember { mutableStateOf<LiveTrainConfig?>(null) }
    var isSamsungHintDismissed by remember { mutableStateOf(liveManager.isSamsungHintDismissed()) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isVisible) {
        if (isVisible) {
            liveTrains = liveManager.getLiveTrains()
        }
    }

    selectedConfigForStops?.let { config ->
        MonitoredStopsSelectionDialog(
            config = config,
            onDismiss = { selectedConfigForStops = null },
            onSave = { updatedStops ->
                val updatedConfig = config.copy(monitoredStops = updatedStops)
                liveTrains = liveManager.saveLiveTrain(updatedConfig)
                selectedConfigForStops = null

                val refreshIntent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                    action = TrainTrackerForegroundService.ACTION_REFRESH_NOTIF
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(refreshIntent)
                } else {
                    context.startService(refreshIntent)
                }
            }
        )
    }

    selectedConfigForDays?.let { config ->
        EditDaysSelectionDialog(
            config = config,
            onDismiss = { selectedConfigForDays = null },
            onSave = { updatedDays ->
                val updatedConfig = config.copy(daysOfWeek = updatedDays)
                liveTrains = liveManager.saveLiveTrain(updatedConfig)
                selectedConfigForDays = null

                LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)
            }
        )
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)
    }

    val openSystemPromotedSettings = {
        try {
            val intent = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    val dayOptions = listOf(
        Calendar.MONDAY to "LUN",
        Calendar.TUESDAY to "MAR",
        Calendar.WEDNESDAY to "MER",
        Calendar.THURSDAY to "GIO",
        Calendar.FRIDAY to "VEN",
        Calendar.SATURDAY to "SAB",
        Calendar.SUNDAY to "DOM"
    )

    val addTrainNumberToLiveTracker = { targetNumber: String ->
        val cleanNum = targetNumber.trim()
        if (cleanNum.isBlank()) {
            addError = "Inserisci un numero di treno valido."
        } else {
            addError = null
            isAdding = true

            coroutineScope.launch {
                val detectedDays = ViaggiaTrenoService.detectRunningDaysForTrain(cleanNum)
                selectedDays = detectedDays

                when (val resolveRes = ViaggiaTrenoService.resolveTrain(cleanNum)) {
                    is ViaggiaTrenoResult.Success -> {
                        val (num, stationId, timestamp) = resolveRes.data
                        when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(num, stationId, timestamp)) {
                            is ViaggiaTrenoResult.Success -> {
                                val status = statusRes.data
                                val newConfig = LiveTrainConfig(
                                    id = UUID.randomUUID().toString(),
                                    trainNumber = num,
                                    daysOfWeek = detectedDays,
                                    originStationId = stationId,
                                    originStationName = status.originStationName,
                                    destinationStationName = status.destinationStationName,
                                    scheduledDepartureTime = status.stops.firstOrNull()?.scheduledTimeMs?.let { formatTime(it) } ?: "",
                                    isEnabled = true
                                )
                                liveTrains = liveManager.saveLiveTrain(newConfig)
                                inputTrainNumber = ""
                                addError = null

                                LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)

                                Toast.makeText(
                                    context,
                                    "Treno $num salvato con i suoi giorni di circolazione rilevati!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ViaggiaTrenoResult.Error -> {
                                addError = statusRes.message
                            }
                        }
                    }
                    is ViaggiaTrenoResult.Error -> {
                        addError = resolveRes.message
                    }
                }
                isAdding = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "Live Tracker Pendolari",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "I treni salvati qui attivano automaticamente la notifica live nei giorni programmati.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        AnimatedVisibility(
            visible = !isSamsungHintDismissed,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { openSystemPromotedSettings() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Tocca qui per abilitare 'Notifiche live' in impostazioni",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            isSamsungHintDismissed = true
                            liveManager.setSamsungHintDismissed(true)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Chiudi suggerimento",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Attiva i permessi per la notifica Live Activity / Capsule nella barra di stato.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Abilita")
                    }
                }
            }
        }

        if (favoriteTrains.isNotEmpty()) {
            Text(
                text = "Aggiungi rapido dai miei preferiti",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                favoriteTrains.forEach { favNum ->
                    val isAlreadyAdded = liveTrains.any { it.trainNumber == favNum }
                    SuggestionChip(
                        onClick = {
                            if (!isAlreadyAdded) {
                                inputTrainNumber = favNum
                                addTrainNumberToLiveTracker(favNum)
                            }
                        },
                        enabled = !isAdding && !isAlreadyAdded,
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAlreadyAdded) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Treno $favNum",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (isAlreadyAdded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Programma un nuovo treno",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = inputTrainNumber,
                    onValueChange = { inputTrainNumber = it },
                    placeholder = { Text("Numero treno (es. 9410)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "GIORNI DI TRACCIAMENTO AUTOMATICO:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayOptions.forEach { (calDay, label) ->
                        val isSelected = selectedDays.contains(calDay)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedDays = if (isSelected) {
                                    selectedDays - calDay
                                } else {
                                    selectedDays + calDay
                                }
                            },
                            label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        selectedDays = setOf(
                            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                            Calendar.THURSDAY, Calendar.FRIDAY
                        )
                    }) {
                        Text("Lun-Ven", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        selectedDays = setOf(
                            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
                        )
                    }) {
                        Text("Tutti i giorni", fontSize = 12.sp)
                    }
                }

                addError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { addTrainNumberToLiveTracker(inputTrainNumber) },
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC8102E),
                        contentColor = Color.White
                    )
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Aggiungi a Live Tracker", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Soluzioni programmate (${liveTrains.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (liveTrains.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Nessun treno o soluzione salvata nel Live Tracker. Aggiungi un treno o una soluzione di viaggio con cambi sopra per attivare le notifiche automatiche.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            liveTrains.forEach { config ->
                val effLegs = config.getEffectiveLegs()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = if (effLegs.size > 1) "Soluzione ${config.getDisplayTrainNumbers()}" else "Treno ${config.trainNumber}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (effLegs.size > 1) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = Color(0xFFE65100).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "${effLegs.size - 1} Cambio",
                                            color = Color(0xFFE65100),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = config.originStationName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = config.destinationStationName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clickable {
                                            selectedConfigForDays = config
                                        }
                                ) {
                                    Text(
                                        text = "📅 ${config.getDaysFormatted()}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Modifica giorni",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Switch(
                                checked = config.isEnabled,
                                onCheckedChange = { isChecked ->
                                    liveTrains = liveManager.toggleTrainEnabled(config.id)
                                    LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)
                                    if (!isChecked) {
                                        effLegs.forEach { leg ->
                                            TrainTrackerForegroundService.stopService(context, leg.trainNumber)
                                        }
                                    }
                                }
                            )
                        }

                        if (effLegs.size > 1) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Text(
                                text = "TRATTE COMPONENTI:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            effLegs.forEachIndexed { idx, leg ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Tratta ${idx + 1}: ${leg.category} ${leg.trainNumber}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${leg.originStationName} (${leg.scheduledDepartureTime}) ➔ ${leg.destinationStationName}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                TrainTrackerForegroundService.startService(
                                                    context = context,
                                                    trainNumber = leg.trainNumber,
                                                    stationId = leg.originStationId
                                                )
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Avvia Tratta",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    effLegs.forEach { leg ->
                                        TrainTrackerForegroundService.startService(
                                            context = context,
                                            trainNumber = leg.trainNumber,
                                            stationId = leg.originStationId
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (effLegs.size > 1) "Avvia notifiche ora" else "Avvia notifica ora",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            TextButton(onClick = {
                                liveTrains = liveManager.removeLiveTrain(config.id)
                                effLegs.forEach { leg ->
                                    TrainTrackerForegroundService.stopService(context, leg.trainNumber)
                                }
                            }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = Color(0xFFD32F2F),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text("Rimuovi", color = Color(0xFFD32F2F), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Opzioni sviluppatore (Debug)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "Forza Capsula One UI (MediaSession Bypass)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Bypassa la Whitelist OEM Samsung per forzare l'ancoraggio della capsula nella barra di stato.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = isMediaSessionBypass,
                        onCheckedChange = { checked ->
                            isMediaSessionBypass = checked
                            liveManager.setMediaSessionBypassEnabled(checked)

                            val refreshIntent = Intent(context, TrainTrackerForegroundService::class.java).apply {
                                action = TrainTrackerForegroundService.ACTION_REFRESH_NOTIF
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(refreshIntent)
                            } else {
                                context.startService(refreshIntent)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EditDaysSelectionDialog(
    config: LiveTrainConfig,
    onDismiss: () -> Unit,
    onSave: (Set<Int>) -> Unit
) {
    var tempDays by remember { mutableStateOf(config.daysOfWeek) }

    val dayOptions = listOf(
        Calendar.MONDAY to "LUN",
        Calendar.TUESDAY to "MAR",
        Calendar.WEDNESDAY to "MER",
        Calendar.THURSDAY to "GIO",
        Calendar.FRIDAY to "VEN",
        Calendar.SATURDAY to "SAB",
        Calendar.SUNDAY to "DOM"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📅 Modifica giorni ${config.getDisplayTrainNumbers()}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Seleziona i giorni nei quali attivare il tracciamento automatico della soluzione:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayOptions.forEach { (calDay, label) ->
                        val isSelected = tempDays.contains(calDay)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                tempDays = if (isSelected) {
                                    tempDays - calDay
                                } else {
                                    tempDays + calDay
                                }
                            },
                            label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        tempDays = setOf(
                            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                            Calendar.THURSDAY, Calendar.FRIDAY
                        )
                    }) {
                        Text("Lun-Ven", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        tempDays = setOf(
                            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
                        )
                    }) {
                        Text("Tutti i giorni", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (tempDays.isNotEmpty()) {
                        onSave(tempDays)
                    }
                },
                enabled = tempDays.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8102E))
            ) {
                Text("Salva giorni", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}

@Composable
fun MonitoredStopsSelectionDialog(
    config: LiveTrainConfig,
    onDismiss: () -> Unit,
    onSave: (List<MonitoredStop>) -> Unit
) {
    var isLoadingStops by remember { mutableStateOf(true) }
    var stopsError by remember { mutableStateOf<String?>(null) }
    var availableStops by remember { mutableStateOf<List<TrainStop>>(emptyList()) }
    var tempSelectedStops by remember { mutableStateOf(config.monitoredStops) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(config) {
        isLoadingStops = true
        stopsError = null

        coroutineScope.launch {
            when (val resolveRes = ViaggiaTrenoService.resolveTrain(config.trainNumber)) {
                is ViaggiaTrenoResult.Success -> {
                    val (num, stationId, timestamp) = resolveRes.data
                    when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(num, stationId, timestamp)) {
                        is ViaggiaTrenoResult.Success -> {
                            availableStops = statusRes.data.stops
                        }
                        is ViaggiaTrenoResult.Error -> {
                            stopsError = statusRes.message
                        }
                    }
                }
                is ViaggiaTrenoResult.Error -> {
                    stopsError = resolveRes.message
                }
            }
            isLoadingStops = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "📍 Fermate monitorate ${config.getDisplayTrainNumbers()}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Seleziona fino a 4 fermate della tratta da mostrare come quadratini sulla notifica.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            if (isLoadingStops) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (stopsError != null) {
                Text(
                    text = stopsError ?: "Errore caricamento fermate",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val totalCount = availableStops.size
                    availableStops.forEachIndexed { idx, stop ->
                        val isChecked = tempSelectedStops.any { it.stationId == stop.stationId }
                        val isMaxReached = tempSelectedStops.size >= 4 && !isChecked

                        val progressPct = when {
                            totalCount <= 1 -> 0
                            else -> ((idx.toFloat() / (totalCount - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isMaxReached || isChecked) {
                                    tempSelectedStops = if (isChecked) {
                                        tempSelectedStops.filterNot { it.stationId == stop.stationId }
                                    } else {
                                        tempSelectedStops + MonitoredStop(
                                            stationId = stop.stationId,
                                            stationName = stop.stationName,
                                            progressPercentage = progressPct
                                        )
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                enabled = !isMaxReached || isChecked,
                                onCheckedChange = { checked ->
                                    tempSelectedStops = if (!checked) {
                                        tempSelectedStops.filterNot { it.stationId == stop.stationId }
                                    } else {
                                        tempSelectedStops + MonitoredStop(
                                            stationId = stop.stationId,
                                            stationName = stop.stationName,
                                            progressPercentage = progressPct
                                        )
                                    }
                                }
                            )

                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = stop.stationName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMaxReached) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Posizione nel percorso: $progressPct%",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(tempSelectedStops)
                },
                enabled = !isLoadingStops && stopsError == null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8102E))
            ) {
                Text("Salva fermate (${tempSelectedStops.size}/4)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}

@Composable
fun TrainStatusCard(
    status: TrainStatus,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onCloseDetail: () -> Unit,
    userBoardingStation: String,
    userAlightingStation: String,
    onSwitchToLiveTracker: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (delayColor, delayText) = when {
                    status.isCancelled -> Color(0xFFD32F2F) to "SOPPRESSO"
                    status.delayMinutes > 0 -> Color(0xFFE65100) to "+${status.delayMinutes} min"
                    status.delayMinutes < 0 -> Color(0xFF2E7D32) to "${status.delayMinutes} min"
                    else -> Color(0xFF2E7D32) to "IN ORARIO"
                }

                Surface(
                    color = delayColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, delayColor)
                ) {
                    Text(
                        text = delayText,
                        color = delayColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onCloseDetail,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Chiudi",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${status.category} ${status.trainNumber}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Preferito",
                        tint = if (isFavorite) Color(0xFFC8102E) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = status.originStationName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = status.destinationStationName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Ultimo rilevamento",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp
            )
            Text(
                text = status.lastDetectedStation,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            status.nextStop?.let { next ->
                val nextPlat = (next.actualPlatform ?: next.scheduledPlatform)?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prossima fermata",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = next.stationName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            nextPlat?.let { platform ->
                                Text(
                                    text = "Binario: $platform",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Text(
                            text = formatTime(next.actualOrEstimatedTimeMs),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Avanzamento treno totale",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${status.progressPercentage}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { status.progressPercentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(10.dp)
                    .clip(CircleShape),
                color = Color(0xFFC8102E),
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                strokeCap = StrokeCap.Round
            )

            if (userBoardingStation.isNotBlank() || userAlightingStation.isNotBlank()) {
                val stopsCount = status.stops.size
                if (stopsCount > 1) {
                    val boardingIdx = status.stops.indexOfFirst {
                        ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, userBoardingStation, "")
                    }.takeIf { it >= 0 } ?: 0

                    val alightingIdx = status.stops.indexOfFirst {
                        ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, userAlightingStation, "")
                    }.takeIf { it >= 0 } ?: (stopsCount - 1)

                    val boardingStop = status.stops.getOrNull(boardingIdx)
                    val alightingStop = status.stops.getOrNull(alightingIdx)

                    val rawBoardingPlat = boardingStop?.actualPlatform ?: boardingStop?.scheduledPlatform
                    val rawAlightingPlat = alightingStop?.actualPlatform ?: alightingStop?.scheduledPlatform

                    val boardingPlatform = rawBoardingPlat?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }
                    val alightingPlatform = rawAlightingPlat?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }

                    val startPct = (boardingIdx.toFloat() / (stopsCount - 1)) * 100
                    val endPct = (alightingIdx.toFloat() / (stopsCount - 1)) * 100

                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Tratta selezionata",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Salita: ${boardingStop?.stationName ?: userBoardingStation}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                boardingPlatform?.let { p ->
                                    Text(
                                        text = "Binario $p",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Discesa: ${alightingStop?.stationName ?: userAlightingStation}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                alightingPlatform?.let { p ->
                                    Text(
                                        text = "Binario $p",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }

                            Text(
                                text = "Posizione nel percorso: dal ${startPct.toInt()}% al ${endPct.toInt()}% del percorso totale",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        val liveManager = LiveTrainManager(context)
                        val autoMonitoredStops = mutableListOf<MonitoredStop>()

                        val totalStopsCount = status.stops.size
                        if (totalStopsCount > 1) {
                            val boardingIdx = status.stops.indexOfFirst {
                                ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, userBoardingStation, "")
                            }.takeIf { it >= 0 } ?: 0

                            val alightingIdx = status.stops.indexOfFirst {
                                ViaggiaTrenoService.matchesStation(it.stationName, it.stationId, userAlightingStation, "")
                            }.takeIf { it >= 0 } ?: (totalStopsCount - 1)

                            val boardingStop = status.stops.getOrNull(boardingIdx)
                            val alightingStop = status.stops.getOrNull(alightingIdx)

                            if (boardingStop != null) {
                                val pct = ((boardingIdx.toFloat() / (totalStopsCount - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
                                autoMonitoredStops.add(
                                    MonitoredStop(
                                        stationId = boardingStop.stationId,
                                        stationName = boardingStop.stationName,
                                        progressPercentage = pct
                                    )
                                )
                            }

                            if (alightingStop != null && alightingStop.stationId != boardingStop?.stationId) {
                                val pct = ((alightingIdx.toFloat() / (totalStopsCount - 1).toFloat()) * 100).toInt().coerceIn(0, 100)
                                autoMonitoredStops.add(
                                    MonitoredStop(
                                        stationId = alightingStop.stationId,
                                        stationName = alightingStop.stationName,
                                        progressPercentage = pct
                                    )
                                )
                            }
                        }

                        val departureTimeLocal = status.stops.firstOrNull()?.scheduledTimeMs?.let { formatTime(it) } ?: ""
                        val detectedDays = ViaggiaTrenoService.detectRunningDaysForTrain(status.trainNumber)

                        val newConfig = LiveTrainConfig(
                            id = UUID.randomUUID().toString(),
                            trainNumber = status.trainNumber,
                            daysOfWeek = detectedDays,
                            originStationId = "",
                            originStationName = status.originStationName,
                            destinationStationName = status.destinationStationName,
                            scheduledDepartureTime = departureTimeLocal,
                            isEnabled = true,
                            monitoredStops = autoMonitoredStops
                        )
                        liveManager.saveLiveTrain(newConfig)

                        LiveTrainScheduler.scheduleAlarmsAndCheckActiveTrains(context)

                        Toast.makeText(
                            context,
                            "Treno ${status.trainNumber} salvato nel Live Tracker!",
                            Toast.LENGTH_LONG
                        ).show()

                        onSwitchToLiveTracker()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Programma nel Tracker",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

fun formatTime(timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0) return "--:--"
    val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
    return sdf.format(Date(timestampMs))
}