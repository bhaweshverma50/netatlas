package atlas.backend.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class H3Test {
    // Bengaluru
    private val lat = 12.9716
    private val lng = 77.5946

    @Test
    fun lat_lng_to_cell_is_stable() {
        val a = H3.cell(lat, lng, 10)
        val b = H3.cell(lat, lng, 10)
        assertEquals(a, b)
    }

    @Test
    fun center_round_trips_into_same_cell() {
        val cell = H3.cell(lat, lng, 10)
        val (clat, clng) = H3.center(cell)
        assertEquals(cell, H3.cell(clat, clng, 10))
    }

    @Test
    fun boundary_has_5_or_6_vertices() {
        val cell = H3.cell(lat, lng, 10)
        assertTrue(H3.boundary(cell).size in 5..6)
    }

    @Test
    fun address_round_trips() {
        val cell = H3.cell(lat, lng, 10)
        val addr = H3.toAddress(cell)
        assertEquals(cell, H3.fromAddress(addr))
        assertTrue(addr.isNotEmpty())
        assertEquals(addr.lowercase(), addr)
        assertTrue(addr.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun resolution_is_10() {
        assertEquals(10, H3.resolution(H3.cell(lat, lng, 10)))
    }

    @Test
    fun different_resolutions_differ() {
        assertNotEquals(H3.cell(lat, lng, 8), H3.cell(lat, lng, 10))
    }
}
