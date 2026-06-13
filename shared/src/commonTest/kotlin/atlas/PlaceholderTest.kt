package atlas

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun atlasSharedReturnsExpectedValue() {
        assertEquals("netatlas-shared", atlasShared())
    }
}
