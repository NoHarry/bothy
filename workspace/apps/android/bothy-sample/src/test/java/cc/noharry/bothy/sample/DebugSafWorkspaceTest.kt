package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSafWorkspaceTest {
    @Test
    fun safPickerUnavailableMessageNamesMissingDocumentTreePicker() {
        assertEquals(
            "SAF picker unavailable: no document-tree picker installed.",
            debugSafPickerUnavailableMessage(),
        )
    }

    @Test
    fun safPickerLaunchFailedMessageIncludesRuntimeFailureDetail() {
        val message = debugSafPickerLaunchFailedMessage(IllegalStateException("launcher is not ready"))

        assertEquals("SAF picker unavailable: launcher is not ready", message)
    }
}
