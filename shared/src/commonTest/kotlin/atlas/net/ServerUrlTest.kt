package atlas.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlTest {

    @Test
    fun blank_is_null() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
    }

    @Test
    fun bare_host_port_gets_http_scheme() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("192.168.1.5:8080"))
    }

    @Test
    fun trims_whitespace_around_input() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("  192.168.1.5:8080  "))
    }

    @Test
    fun strips_trailing_slash() {
        assertEquals("http://x:8080", ServerUrl.normalize("http://x:8080/"))
    }

    @Test
    fun strips_multiple_trailing_slashes() {
        assertEquals("http://x:8080", ServerUrl.normalize("http://x:8080///"))
    }

    @Test
    fun https_is_preserved() {
        assertEquals("https://api.example.com", ServerUrl.normalize("https://api.example.com"))
    }

    @Test
    fun host_without_port_is_allowed() {
        assertEquals("http://example.com", ServerUrl.normalize("example.com"))
    }

    @Test
    fun garbage_is_null() {
        assertNull(ServerUrl.normalize("not a url"))
    }

    @Test
    fun non_http_scheme_is_null() {
        assertNull(ServerUrl.normalize("ftp://x"))
    }

    @Test
    fun default_is_deployed_cloud_run_and_valid() {
        assertEquals("https://netatlas-backend-872879151769.asia-south1.run.app", ServerUrl.DEFAULT)
        // the default must itself survive normalization (no trailing slash, https scheme)
        assertEquals(ServerUrl.DEFAULT, ServerUrl.normalize(ServerUrl.DEFAULT))
    }
}
