package atlas.backend.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure [OpenCelliDParser]. No database, no I/O — these pin the
 * tolerant CSV parsing rules: header/blank/malformed lines yield null, valid lines
 * map to a [TowerRow], and empty optional fields become nulls.
 */
class OpenCelliDParserTest {

    @Test
    fun header_line_returns_null() {
        val header = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"
        assertNull(OpenCelliDParser.parseLine(header))
    }

    @Test
    fun valid_lte_line_parses_to_tower_row() {
        val line = "LTE,404,45,678,12345,,77.5946,12.9716,1500,42,1,1700000000,1700000000,-95"
        val row = OpenCelliDParser.parseLine(line)
        assertEquals(
            TowerRow(
                radio = "LTE",
                mcc = 404,
                mnc = 45,
                area = 678,
                cellId = 12345L,
                lng = 77.5946,
                lat = 12.9716,
                rangeM = 1500,
                samples = 42,
            ),
            row,
        )
    }

    @Test
    fun empty_range_and_samples_become_null() {
        val line = "NR,404,10,12,98765,,77.60,12.95,,,1,1700000000,1700000000,"
        val row = OpenCelliDParser.parseLine(line)
        assertEquals("NR", row?.radio)
        assertEquals(98765L, row?.cellId)
        assertNull(row?.rangeM)
        assertNull(row?.samples)
    }

    @Test
    fun too_few_columns_returns_null() {
        assertNull(OpenCelliDParser.parseLine("LTE,404,45,678"))
    }

    @Test
    fun non_numeric_mcc_returns_null() {
        assertNull(
            OpenCelliDParser.parseLine("LTE,ABC,45,678,12345,,77.5946,12.9716,1500,42,1,1,1,-95"),
        )
    }

    @Test
    fun out_of_range_latitude_returns_null() {
        // lat=99 is outside -90..90
        assertNull(
            OpenCelliDParser.parseLine("LTE,404,45,678,12345,,77.5946,99.0,1500,42,1,1,1,-95"),
        )
    }

    @Test
    fun out_of_range_longitude_returns_null() {
        // lng=200 is outside -180..180
        assertNull(
            OpenCelliDParser.parseLine("LTE,404,45,678,12345,,200.0,12.9716,1500,42,1,1,1,-95"),
        )
    }

    @Test
    fun blank_line_returns_null() {
        assertNull(OpenCelliDParser.parseLine(""))
        assertNull(OpenCelliDParser.parseLine("   "))
    }

    @Test
    fun parse_drops_header_and_bad_lines_keeps_good_rows() {
        val lines = sequenceOf(
            "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal",
            "LTE,404,45,678,12345,,77.5946,12.9716,1500,42,1,1,1,-95",
            "",
            "LTE,404,45,678", // too few cols
            "LTE,ABC,45,678,12345,,77.5946,12.9716,1500,42,1,1,1,-95", // bad mcc
            "LTE,404,45,679,12346,,77.6,99.0,1500,42,1,1,1,-95", // bad lat
            "NR,404,10,12,98765,,77.60,12.95,,,1,1,1,",
        )
        val rows = OpenCelliDParser.parse(lines).toList()
        assertEquals(2, rows.size)
        assertEquals(12345L, rows[0].cellId)
        assertEquals(98765L, rows[1].cellId)
    }
}
