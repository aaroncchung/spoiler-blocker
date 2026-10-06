package io.github.aaroncchung.spoilerblocker.suggestions

import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.aaroncchung.spoilerblocker.expansion.Breadth as ExpansionBreadth

class TermSuggesterTest {

    // The request that ClaudeTermSuggester ends up sending is tested in the
    // expansion module, against a fake server. What the app adds is this
    // conversion. A mistake in it would ask for the wrong kind of list.
    @Test
    fun `each breadth becomes the expansion module's breadth of the same meaning`() {
        assertEquals(ExpansionBreadth.NARROW, Breadth.NARROW.toExpansionBreadth())
        assertEquals(ExpansionBreadth.BROAD, Breadth.BROAD.toExpansionBreadth())
    }
}
