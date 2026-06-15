package atlas.map

import atlas.model.Carrier
import atlas.model.HexAggregate
import atlas.net.ApiClient.Companion.EMPTY_FEATURE_COLLECTION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the map's filter/bbox state and exposes the heatmap data as [StateFlow]s.
 *
 * The [scope] is injected (a `viewModelScope` on Android, a `TestScope` in tests) so
 * this stays free of androidx lifecycle. Loads degrade gracefully: a failed fetch
 * surfaces as an empty list (see [HexRepository]/`ApiClient`), never an exception.
 */
class MapViewModel(
    private val repo: HexRepository,
    private val scope: CoroutineScope,
) {
    private val _hexes = MutableStateFlow<List<HexAggregate>>(emptyList())
    val hexes: StateFlow<List<HexAggregate>> = _hexes.asStateFlow()

    private val _geoJson = MutableStateFlow(EMPTY_FEATURE_COLLECTION)
    /** The heatmap as a raw GeoJSON FeatureCollection string, for the MapLibre fill layer. */
    val geoJson: StateFlow<String> = _geoJson.asStateFlow()

    private val _filter = MutableStateFlow(HexFilter())
    val filter: StateFlow<HexFilter> = _filter.asStateFlow()

    private val _carriers = MutableStateFlow<List<Carrier>>(emptyList())
    val carriers: StateFlow<List<Carrier>> = _carriers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var currentBbox: BoundingBox? = null
    private var loadJob: Job? = null

    /** Stores the visible window and reloads the heatmap for it. */
    fun setBoundingBox(bbox: BoundingBox) {
        currentBbox = bbox
        reload()
    }

    /** Updates the active filter and reloads with the current bbox. */
    fun setFilter(filter: HexFilter) {
        _filter.value = filter
        reload()
    }

    /** Reloads the heatmap with the current bbox + filter. No-op until a bbox is set. */
    fun refresh() = reload()

    /** Fetches the carrier list (for the filter UI). */
    fun loadCarriers() {
        scope.launch {
            _carriers.value = repo.carriers()
        }
    }

    private fun reload() {
        val bbox = currentBbox ?: return
        // Latest call wins: cancel any in-flight load before launching a new one.
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            try {
                // Load both the typed aggregates and the GeoJSON for the map off the same trigger.
                _hexes.value = repo.hexes(bbox, _filter.value)
                _geoJson.value = repo.hexesGeoJson(bbox, _filter.value)
            } finally {
                _loading.value = false
            }
        }
    }
}
