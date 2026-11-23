package com.schmitzkr.grimreader.reader

import com.schmitzkr.grimreader.ui.reader.stripCurrentPage
import org.junit.Assert.assertEquals
import org.junit.Test

class StripCurrentPageTest {
    @Test fun `the topmost visible page is current while the strip can scroll on`() {
        assertEquals(4, stripCurrentPage(firstVisible = 4, canScrollForward = true, count = 10))
    }

    @Test fun `the end of the strip makes the last page current even when it never reaches the top`() {
        assertEquals(9, stripCurrentPage(firstVisible = 8, canScrollForward = false, count = 10))
    }

    @Test fun `an out-of-range index is clamped and an empty book is page zero`() {
        assertEquals(2, stripCurrentPage(firstVisible = 7, canScrollForward = true, count = 3))
        assertEquals(0, stripCurrentPage(firstVisible = 0, canScrollForward = false, count = 0))
    }
}
