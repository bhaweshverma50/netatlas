package atlas.map

import atlas.model.Carrier
import atlas.model.CoverageClass
import atlas.model.HexAggregate
import atlas.model.NetworkType
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VM-level tests use a hand-written [FakeHexRepository] so the whole flow runs on the
 * test scheduler with deterministic virtual time. The real ApiClient -> MockEngine HTTP
 * chain is covered separately in [atlas.net.ApiClientHexTest], so nothing is left untested.
 */
class MapViewModelTest {

    private fun hex(h3: String, network: NetworkType = NetworkType.LTE) = HexAggregate(
        h3 = h3, resolution = 10, mcc = 405, mnc = 861, networkType = network,
        sampleCount = 42, meanDbm = -95.0, medianDbm = -94.0, stddev = 3.5,
        confidence = 0.8, coverageClass = CoverageClass.FAIR, centerLat = 28.61, centerLng = 77.20,
    )

    /** Records the calls it received and replays a fixed result. */
    private class FakeHexRepository(
        private val hexResult: List<HexAggregate>,
        private val carrierResult: List<Carrier> = emptyList(),
    ) : HexRepository(api = throwingApi()) {
        var hexCalls = 0
        var lastBbox: BoundingBox? = null
        var lastFilter: HexFilter? = null
        var carrierCalls = 0

        override suspend fun hexes(bbox: BoundingBox, filter: HexFilter): List<HexAggregate> {
            hexCalls++
            lastBbox = bbox
            lastFilter = filter
            return hexResult
        }

        override suspend fun carriers(): List<Carrier> {
            carrierCalls++
            return carrierResult
        }

        companion object {
            // The fake overrides every method, so the ApiClient is never touched.
            private fun throwingApi(): atlas.net.ApiClient =
                atlas.net.ApiClient("http://unused.test", io.ktor.client.HttpClient(
                    io.ktor.client.engine.mock.MockEngine { error("ApiClient must not be called by the fake repo") },
                ))
        }
    }

    private val bbox = BoundingBox(minLng = 77.0, minLat = 28.0, maxLng = 78.0, maxLat = 29.0)

    @Test
    fun setBoundingBox_loads_hexes() = runTest {
        val repo = FakeHexRepository(listOf(hex("8a2a1072b59ffff")))
        val vm = MapViewModel(repo, this)

        vm.setBoundingBox(bbox)
        advanceUntilIdle()

        assertEquals(1, vm.hexes.value.size)
        assertEquals("8a2a1072b59ffff", vm.hexes.value[0].h3)
        assertEquals(bbox, repo.lastBbox)
    }

    @Test
    fun setFilter_reloads_with_filter() = runTest {
        val repo = FakeHexRepository(listOf(hex("8a2a1072b59ffff")))
        val vm = MapViewModel(repo, this)

        vm.setBoundingBox(bbox)
        advanceUntilIdle()
        vm.setFilter(HexFilter(mnc = 45))
        advanceUntilIdle()

        assertEquals(HexFilter(mnc = 45), repo.lastFilter, "filter must reach the repo call")
        assertEquals(HexFilter(mnc = 45), vm.filter.value)
        assertEquals(1, vm.hexes.value.size)
        assertEquals(2, repo.hexCalls, "setFilter triggers a reload")
    }

    @Test
    fun no_bbox_no_load() = runTest {
        val repo = FakeHexRepository(listOf(hex("8a2a1072b59ffff")))
        val vm = MapViewModel(repo, this)

        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.hexes.value.isEmpty(), "no bbox means no hexes")
        assertEquals(0, repo.hexCalls, "no repo call should be made without a bbox")
    }

    @Test
    fun loadCarriers_populates() = runTest {
        val repo = FakeHexRepository(
            hexResult = emptyList(),
            carrierResult = listOf(Carrier(405, 861, "Jio"), Carrier(404, 45, "Airtel")),
        )
        val vm = MapViewModel(repo, this)

        vm.loadCarriers()
        advanceUntilIdle()

        assertEquals(2, vm.carriers.value.size)
        assertEquals("Jio", vm.carriers.value[0].carrierName)
    }

    @Test
    fun loading_toggles() = runTest {
        val repo = FakeHexRepository(listOf(hex("8a2a1072b59ffff")))
        val vm = MapViewModel(repo, this)

        assertFalse(vm.loading.value, "starts not loading")
        vm.setBoundingBox(bbox)
        advanceUntilIdle()
        assertFalse(vm.loading.value, "loading must be false after idle")

        assertEquals(NetworkType.LTE, vm.hexes.value[0].networkType)
    }
}
