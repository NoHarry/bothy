package cc.noharry.bothy.playground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated car's music, stepped through by the play bar. */
class MediaPlayedTest {
    @Test
    fun previousWithNothingOnStartsFromTheEndAsNextStartsFromTheBeginning() {
        val first = Media().played("next", null)
        val last = first.played("previous", null)
        val fromNothing = Media().played("previous", null)
        assertTrue(fromNothing.playing)
        assertEquals(last.title, fromNothing.title)
        assertNotEquals(first.title, fromNothing.title)
    }

    @Test
    fun previousUndoesNext() {
        val start = Media().played("next", null)
        assertEquals(start.title, start.played("next", null).played("previous", null).title)
    }
}
