package cc.noharry.bothy.app.runtime
import org.junit.Assert.assertEquals
import org.junit.Test

class SafWorkspaceTest {
    @Test
    fun safPickerUnavailableMessageNamesMissingDocumentTreePicker() {
        assertEquals(
            "SAF picker unavailable: no document-tree picker installed.",
            safPickerUnavailableMessage(),
        )
    }

    @Test
    fun safPickerLaunchFailedMessageIncludesRuntimeFailureDetail() {
        val message = safPickerLaunchFailedMessage(IllegalStateException("launcher is not ready"))

        assertEquals("SAF picker unavailable: launcher is not ready", message)
    }
}
