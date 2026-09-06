package com.trenilive.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var selectedTab by remember { mutableIntStateOf(0) }

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
                        Text("Cerca Treni", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                        Text("Live Tracker", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            )
        }

        when (selectedTab) {
            0 -> TrainTrackerScreen()
            1 -> LiveTrackerScreen()
        }
    }
}

@Composable
fun TrainTrackerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val favoritesManager = remember { FavoritesManager(context) }
    var favoriteList by remember { mutableStateOf(favoritesManager.getFavoriteTrains()) }

    var trainNumberInput by remember { mutableStateOf("") }

    // Campi stazioni per la ricerca gestiti con TextFieldValue per controllo del cursore
    var originQuery by remember { mutableStateOf(TextFieldValue("")) }
    var selectedOriginStation by remember { mutableStateOf<StationInfo?>(null) }
    var originSuggestions by remember { mutableStateOf<List<StationInfo>>(emptyList()) }

    var destinationQuery by remember { mutableStateOf(TextFieldValue("")) }
    var selectedDestinationStation by remember { mutableStateOf<StationInfo?>(null) }
    var destinationSuggestions by remember { mutableStateOf<List<StationInfo>>(emptyList()) }

    // Data e Ora selezionate (in millisecondi)
    var selectedDateMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Stati di caricamento e risultati
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var trainStatus by remember { mutableStateOf<TrainStatus?>(null) }
    var stationSolutions by remember { mutableStateOf<List<StationDeparture>>(emptyList()) }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var previousSuggestionsCount by remember { mutableIntStateOf(0) }

    // Quando compaiono i suggerimenti, effettua un piccolo scroll controllato dell'altezza di 1 suggerimento (~140px)
    LaunchedEffect(originSuggestions, destinationSuggestions) {
        val currentCount = originSuggestions.size + destinationSuggestions.size
        if (currentCount > 0 && previousSuggestionsCount == 0) {
            scrollState.animateScrollBy(140f)
        } else if (currentCount == 0 && previousSuggestionsCount > 0) {
            scrollState.animateScrollBy(-140f)
        }
        previousSuggestionsCount = currentCount
    }

    // Funzione ricerca diretta per numero di treno
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

    // Funzione ricerca dettagli per una specifica partenza trovata nella lista
    val searchByDeparture = { dep: StationDeparture ->
        focusManager.clearFocus()
        errorMessage = null
        isLoading = true

        coroutineScope.launch {
            when (val statusRes = ViaggiaTrenoService.fetchTrainStatusForDeparture(
                trainNumber = dep.trainNumber,
                departureStationId = dep.originStationId,
                departureTimestampMs = dep.departureTimestampMs
            )) {
                is ViaggiaTrenoResult.Success -> {
                    trainStatus = statusRes.data
                }
                is ViaggiaTrenoResult.Error -> {
                    errorMessage = statusRes.message
                }
            }
            isLoading = false
        }
    }

    // Funzione ricerca soluzioni per stazione e orario
    val searchByStations = {
        focusManager.clearFocus()
        val originName = originQuery.text.trim()
        val destName = destinationQuery.text.trim()

        if (originName.isEmpty()) {
            errorMessage = "La Stazione di Partenza è obbligatoria."
        } else if (destName.isEmpty()) {
            errorMessage = "La Stazione di Arrivo è obbligatoria per la ricerca per tratta."
        } else {
            errorMessage = null
            isLoading = true
            trainStatus = null
            stationSolutions = emptyList()

            coroutineScope.launch {
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
                when (val routeRes = ViaggiaTrenoService.fetchRouteSolutions(
                    originStationId = originStation.id,
                    originNameQuery = originName,
                    destinationQuery = destName,
                    date = searchDate,
                    minSolutions = 5
                )) {
                    is ViaggiaTrenoResult.Success -> {
                        stationSolutions = routeRes.data
                        if (stationSolutions.isEmpty()) {
                            val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(searchDate)
                            errorMessage = "Nessun treno trovato tra ${originStation.name} e $destName a partire dal $timeStr."
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

    // Picker Data e Ora
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
        // Header
        Text(
            text = "TreniLive",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Cerca per Numero Treno o per Tratta Completa",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // BARRA PREFERITI SALVATI
        if (favoriteList.isNotEmpty()) {
            Text(
                text = "I MIEI PREFERITI",
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
                favoriteList.forEach { favNum ->
                    SuggestionChip(
                        onClick = {
                            trainNumberInput = favNum
                            searchByTrainNumber(favNum)
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = Color(0xFFC8102E),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(favNum, fontWeight = FontWeight.Bold)
                            }
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }

        // CARD DI RICERCA UNIFICATA CON ANGOLI ARROTONDATI M3
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // Opzione 1: Ricerca per Numero Treno
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
                        text = "Ricerca Diretta per Numero Treno",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = trainNumberInput,
                    onValueChange = { trainNumberInput = it },
                    placeholder = { Text("Es. 9410, 16022, 16016") },
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
                        Text("Cerca Treno $trainNumberInput", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))

                // Opzione 2: Ricerca per Stazioni e Tratta
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
                        text = "Oppure Cerca per Tratta Completa",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stazione Partenza (con Autocomplete)
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
                    placeholder = { Text("Stazione di Partenza") },
                    singleLine = true,
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

                // Autocomplete Partenza
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
                                            // Posiziona esplicitamente il cursore alla FINE del testo selezionato
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

                // Stazione Arrivo (con Autocomplete)
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
                    placeholder = { Text("Stazione di Destinazione") },
                    singleLine = true,
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

                // Autocomplete Arrivo
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
                                            // Posiziona esplicitamente il cursore alla FINE del testo selezionato
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

                // Selezione Data & Ora
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
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pulsante di Ricerca Soluzioni Tratta
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
                            text = "Cerca Soluzioni Tratta",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // SCHEDA DETTAGLIO TRENO SELEZIONATO IN TEMPO REALE
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
                    userAlightingStation = destinationQuery.text
                )
            }
        }

        // LISTA SOLUZIONI TROVATE
        if (stationSolutions.isNotEmpty() && !isLoading) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "PROSSIMI TRENI DISPONIBILI DALL'ORARIO SELEZIONATO (${stationSolutions.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            stationSolutions.forEach { departure ->
                val isCurrentlySelected = trainStatus?.trainNumber == departure.trainNumber

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable {
                            searchByDeparture(departure)
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentlySelected) Color(0xFFFFF0F2) else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isCurrentlySelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFC8102E)) else null,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${departure.category} ${departure.trainNumber}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isCurrentlySelected) Color(0xFFC8102E) else MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = departure.destination,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = departure.departureTimeFormatted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (departure.delayMinutes > 0) "+${departure.delayMinutes} min" else "In orario",
                                color = if (departure.delayMinutes > 0) Color(0xFFE65100) else Color(0xFF2E7D32),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun LiveTrackerScreen(modifier: Modifier = Modifier) {
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

    val coroutineScope = rememberCoroutineScope()

    // Gestione Permesso Notifiche Android 13+
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
        // Avvia il tracciamento automatico per i treni programmati per la giornata odierna
        LiveTrainScheduler.checkAndStartScheduledTrains(context)
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
        } else if (selectedDays.isEmpty()) {
            addError = "Seleziona almeno un giorno della settimana."
        } else {
            addError = null
            isAdding = true

            coroutineScope.launch {
                when (val resolveRes = ViaggiaTrenoService.resolveTrain(cleanNum)) {
                    is ViaggiaTrenoResult.Success -> {
                        val (num, stationId, timestamp) = resolveRes.data
                        when (val statusRes = ViaggiaTrenoService.fetchTrainStatus(num, stationId, timestamp)) {
                            is ViaggiaTrenoResult.Success -> {
                                val status = statusRes.data
                                val newConfig = LiveTrainConfig(
                                    id = UUID.randomUUID().toString(),
                                    trainNumber = num,
                                    daysOfWeek = selectedDays,
                                    originStationId = stationId,
                                    originStationName = status.originStationName,
                                    destinationStationName = status.destinationStationName,
                                    scheduledDepartureTime = "",
                                    isEnabled = true
                                )
                                liveTrains = liveManager.saveLiveTrain(newConfig)
                                inputTrainNumber = ""
                                addError = null

                                // Se il treno è programmato per oggi, avvia SUBITO il tracciamento
                                val todayDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                                if (newConfig.isScheduledForDay(todayDayOfWeek)) {
                                    TrainTrackerForegroundService.startService(
                                        context = context,
                                        trainNumber = newConfig.trainNumber,
                                        stationId = newConfig.originStationId
                                    )
                                }
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
            text = "I treni salvati qui attivano automaticamente la notifica Live Ongoing nei giorni programmati.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // Pulsante di accesso rapido alle impostazioni di sistema per le Notifiche Live Samsung One UI
        OutlinedButton(
            onClick = { openSystemPromotedSettings() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Abilita 'Notifiche Live' in Impostazioni Samsung",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
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

        // AGGIUNTA RAPIDA DAI PREFERITI
        if (favoriteTrains.isNotEmpty()) {
            Text(
                text = "AGGIUNGI RAPIDO DAI MIEI PREFERITI",
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

        // CARD PER AGGIUNGERE UN TRENO AL LIVE TRACKER
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "PROGRAMMA UN NUOVO TRENO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = inputTrainNumber,
                    onValueChange = { inputTrainNumber = it },
                    placeholder = { Text("Numero Treno (es. 16758, 9410)") },
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

                // Pulsanti Selettori Giorni Settimana
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8102E))
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Aggiungi a Live Tracker", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "TRENI PROGRAMMATI (${liveTrains.size})",
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
                    text = "Nessun treno salvato nel Live Tracker. Aggiungi il numero del tuo treno pendolare sopra per attivare le notifiche automatiche nei giorni selezionati.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            liveTrains.forEach { config ->
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Treno ${config.trainNumber}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
                                Text(
                                    text = "📅 ${config.getDaysFormatted()}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            Switch(
                                checked = config.isEnabled,
                                onCheckedChange = { isChecked ->
                                    liveTrains = liveManager.toggleTrainEnabled(config.id)
                                    if (isChecked) {
                                        val todayDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                                        if (config.isScheduledForDay(todayDayOfWeek)) {
                                            TrainTrackerForegroundService.startService(
                                                context = context,
                                                trainNumber = config.trainNumber,
                                                stationId = config.originStationId
                                            )
                                        }
                                    } else {
                                        TrainTrackerForegroundService.stopService(context)
                                    }
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    TrainTrackerForegroundService.startService(
                                        context = context,
                                        trainNumber = config.trainNumber,
                                        stationId = config.originStationId
                                    )
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
                                    Text("Avvia Notifica Ora", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            TextButton(onClick = {
                                liveTrains = liveManager.removeLiveTrain(config.id)
                                TrainTrackerForegroundService.stopService(context)
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

        // SEZIONE OPZIONI SVILUPPATORE / DEBUG
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
                        text = "OPZIONI SVILUPPATORE (DEBUG)",
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

                            // Invia il segnale di refresh immediato al ForegroundService attivo
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
fun TrainStatusCard(
    status: TrainStatus,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onCloseDetail: () -> Unit,
    userBoardingStation: String,
    userAlightingStation: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            // 1. RIGA SUPERIORE: Badge Ritardo (sinistra) + Pulsante Chiudi Compatto ✖ (destra)
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

            // 2. RIGA DEDICATA: Nome Categoria e Numero Treno + Cuore Preferiti
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

            // 3. RIGA DEDICATA: Stazione Partenza ➔ Stazione Arrivo
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

            // Ultimo Rilevamento
            Text(
                text = "ULTIMO RILEVAMENTO",
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

            // Prossima Fermata
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
                                text = "PROSSIMA FERMATA",
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

            // Barra di Progresso Avanzamento Viaggio
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Avanzamento Treno Totale",
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
                    .height(8.dp)
                    .padding(top = 6.dp),
                color = Color(0xFFC8102E),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Evidenziazione Tratta Utente con Binari di Salita e Discesa
            if (userBoardingStation.isNotBlank() || userAlightingStation.isNotBlank()) {
                val stopsCount = status.stops.size
                if (stopsCount > 1) {
                    val boardingIdx = status.stops.indexOfFirst {
                        it.stationName.contains(userBoardingStation, ignoreCase = true)
                    }.takeIf { it >= 0 } ?: 0

                    val alightingIdx = status.stops.indexOfFirst {
                        it.stationName.contains(userAlightingStation, ignoreCase = true)
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
                                text = "TRATTA SELEZIONATA DA TE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Salita: ${boardingStop?.stationName ?: userBoardingStation}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Discesa: ${alightingStop?.stationName ?: userAlightingStation}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
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
        }
    }
}

fun formatTime(timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0) return "--:--"
    val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
    return sdf.format(Date(timestampMs))
}