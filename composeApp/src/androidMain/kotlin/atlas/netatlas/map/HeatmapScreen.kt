package atlas.netatlas.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import atlas.map.FilterSelection
import atlas.map.HexDetail
import atlas.map.MapViewModel
import atlas.net.ServerUrl
import atlas.model.Carrier
import atlas.model.CoverageClass
import atlas.model.NetworkType
import atlas.model.Source
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/** Coverage colors shared with the MapLibre fill layer, for the legend chips. */
private val COVERAGE_COLORS: List<Pair<String, Color>> = listOf(
    "Excellent" to Color(0xFF1A9850),
    "Good" to Color(0xFF91CF60),
    "Fair" to Color(0xFFFEE08B),
    "Poor" to Color(0xFFFC8D59),
    "No signal" to Color(0xFFD73027),
)

private data class NetworkOption(val label: String, val type: NetworkType?)

private val NETWORK_OPTIONS = listOf(
    NetworkOption("All", null),
    NetworkOption("LTE", NetworkType.LTE),
    NetworkOption("5G", NetworkType.NR_SA),
)

/**
 * The full coverage-heatmap screen: a MapLibre map under a translucent filter bar + legend,
 * collector controls anchored at the bottom, and a hex-detail bottom sheet on tap.
 *
 * Android-specific concerns (starting/stopping the service, requesting permissions) are
 * injected as callbacks so this composable stays focused on layout + the [MapViewModel].
 * [contributionsFlow] is the live Room reading count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(
    viewModel: MapViewModel,
    contributionsFlow: Flow<Int>,
    onStartCollection: () -> Unit,
    onStopCollection: () -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geoJson by viewModel.geoJson.collectAsState()
    val carriers by viewModel.carriers.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val contributions by contributionsFlow.collectAsState(initial = 0)

    var selection by remember { mutableStateOf(FilterSelection()) }
    var collecting by remember { mutableStateOf(false) }
    var tappedHex by remember { mutableStateOf<HexDetail?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Load the carrier list for the filter dropdown once.
    LaunchedEffect(Unit) { viewModel.loadCarriers() }

    fun applySelection(next: FilterSelection) {
        selection = next
        viewModel.setFilter(next.toHexFilter())
    }

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(
            geoJson = geoJson,
            onBoundsChanged = viewModel::setBoundingBox,
            onHexTapped = { tappedHex = it },
            modifier = Modifier.fillMaxSize(),
        )

        // Top overlay: filters + legend.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterBar(
                carriers = carriers,
                selection = selection,
                onSelectionChange = ::applySelection,
                onOpenSettings = { showSettings = true },
            )
            Legend()
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // Bottom controls: collector toggle + contributions count.
        CollectorControls(
            collecting = collecting,
            contributions = contributions,
            onToggle = {
                if (collecting) {
                    onStopCollection()
                    collecting = false
                } else {
                    onStartCollection()
                    collecting = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        )
    }

    // Hex-detail sheet.
    val detail = tappedHex
    if (detail != null) {
        ModalBottomSheet(
            onDismissRequest = { tappedHex = null },
            sheetState = sheetState,
        ) {
            HexDetailSheet(detail = detail, carriers = carriers)
        }
    }

    // Server-URL settings dialog.
    if (showSettings) {
        ServerUrlDialog(
            currentUrl = serverUrl,
            onDismiss = { showSettings = false },
            onSave = { url ->
                onServerUrlChange(url)
                showSettings = false
            },
        )
    }
}

/**
 * Lets the user point the app at their own backend (the emulator alias only works on the
 * emulator). Validates input through [ServerUrl.normalize]: on save it persists the
 * normalized URL via [onSave]; an unparseable entry shows an inline error and keeps the
 * dialog open. The current server is shown as the subtitle so users know where it points.
 */
@Composable
private fun ServerUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Currently: $currentUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("Backend address") },
                    placeholder = { Text("e.g. 192.168.1.5:8080") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = ServerUrl.normalize(text)
                if (normalized == null) {
                    error = "Enter a valid http(s) address, e.g. 192.168.1.5:8080"
                } else {
                    onSave(normalized)
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    carriers: List<Carrier>,
    selection: FilterSelection,
    onSelectionChange: (FilterSelection) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Carrier dropdown.
            FilterDropdown(
                label = "Carrier",
                selectedLabel = selection.carrier?.carrierName ?: "All carriers",
                options = buildList {
                    add(null to "All carriers")
                    carriers.forEach { add(it to it.carrierName) }
                },
                onSelect = { onSelectionChange(selection.copy(carrier = it)) },
                modifier = Modifier.weight(1f),
            )
            // Network dropdown.
            FilterDropdown(
                label = "Network",
                selectedLabel = NETWORK_OPTIONS.first { it.type == selection.networkType }.label,
                options = NETWORK_OPTIONS.map { it.type to it.label },
                onSelect = { onSelectionChange(selection.copy(networkType = it)) },
                modifier = Modifier.weight(1f),
            )
            // Settings (server URL). A gear glyph keeps this dependency-free (no
            // material-icons-extended), while reading as a settings affordance.
            IconButton(onClick = onOpenSettings) {
                Text("⚙", fontSize = 20.sp)
            }
        }
    }
}

/** A generic single-select [ExposedDropdownMenuBox] backed by a list of `value to label`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FilterDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                COVERAGE_COLORS.forEach { (name, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(name, fontSize = 11.sp)
                    }
                }
            }
            Text(
                text = "Dashed = modeled (OpenCelliD) · solid = measured",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CollectorControls(
    collecting: Boolean,
    contributions: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        ) {
            Text(
                text = "Your contributions: $contributions",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onToggle) {
            Text(if (collecting) "Stop collecting" else "Start collecting")
        }
    }
}

@Composable
private fun HexDetailSheet(detail: HexDetail, carriers: List<Carrier>) {
    val carrierName = carriers
        .firstOrNull { it.mcc == detail.mcc && it.mnc == detail.mnc }
        ?.carrierName
    val modeled = detail.source == Source.OPENCELLID
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Hex coverage", style = MaterialTheme.typography.titleLarge)
        // Provenance line: measured vs. modeled.
        Text(
            text = if (modeled) {
                "Modeled estimate · OpenCelliD"
            } else {
                "Crowd-sourced · ${detail.sampleCount} samples"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        DetailRow("Mean signal", "${detail.meanDbm.roundToInt()} dBm")
        DetailRow("Median signal", "${detail.medianDbm.roundToInt()} dBm")
        DetailRow("Coverage", detail.coverageClass.toLabel())
        if (modeled) {
            // Modeled hexes carry no real measurements (sampleCount == 0).
            DetailRow("Samples", "Modeled (no measurements yet)")
        } else {
            DetailRow("Samples", detail.sampleCount.toString())
        }
        DetailRow("Confidence", "${(detail.confidence * 100).roundToInt()}%")
        DetailRow(
            "Carrier",
            carrierName?.let { "$it (${detail.mcc}/${detail.mnc})" } ?: "${detail.mcc}/${detail.mnc}",
        )
        DetailRow("Network", detail.networkType.toLabel())
        Spacer(Modifier.height(8.dp))
        Text(
            text = "H3: ${detail.h3}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun CoverageClass.toLabel(): String = when (this) {
    CoverageClass.EXCELLENT -> "Excellent"
    CoverageClass.GOOD -> "Good"
    CoverageClass.FAIR -> "Fair"
    CoverageClass.POOR -> "Poor"
    CoverageClass.NO_SIGNAL -> "No signal"
}

private fun NetworkType.toLabel(): String = when (this) {
    NetworkType.NR_SA, NetworkType.NR_NSA -> "5G"
    NetworkType.LTE -> "LTE"
    NetworkType.UMTS -> "3G"
    NetworkType.GSM -> "2G"
    NetworkType.UNKNOWN -> "Unknown"
}
