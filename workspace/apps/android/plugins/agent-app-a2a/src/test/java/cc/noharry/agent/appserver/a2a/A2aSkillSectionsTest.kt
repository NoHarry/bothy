package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.appserver.skills.withSections
import org.junit.Assert.assertEquals
import org.junit.Test

/** A skill's `a2a` sections hold while the person's own agent is connected, its `no-a2a` ones while not. */
class A2aSkillSectionsTest {
    @Test
    fun theA2aSectionsAreTheSkillsSectionsUnderTheirKey() {
        val text = "a\n<!-- a2a -->\nsend it\n<!-- /a2a -->\n<!-- no-a2a -->\nkeep it\n<!-- /no-a2a -->\nb"
        assertEquals(text.withSections(setOf(A2A_SKILL_SECTION)), text.withA2aSections(connected = true))
        assertEquals("a\nsend it\nb", text.withA2aSections(connected = true))
        assertEquals("a\nkeep it\nb", text.withA2aSections(connected = false))
    }
}
