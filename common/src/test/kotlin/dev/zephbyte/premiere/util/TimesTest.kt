package dev.zephbyte.premiere.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimesTest {

    @Test
    fun `parses absolute and relative staff time formats`() {
        assertEquals(5_025_000L, Times.parseMs("1:23:45", 0L))
        assertEquals(330_000L, Times.parseMs("5:30", 0L))
        assertEquals(90_000L, Times.parseMs("90", 0L))
        assertEquals(120_000L, Times.parseMs("+30", 90_000L))
        assertEquals(0L, Times.parseMs("-1:30", 90_000L))
    }

    @Test
    fun `rejects malformed time`() {
        assertNull(Times.parseMs("1::30", 0L))
        assertNull(Times.parseMs("movie", 0L))
        assertNull(Times.parseMs("1:2:3:4", 0L))
    }
}
