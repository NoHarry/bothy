package cc.noharry.bothy.protocol.schema

import cc.noharry.bothy.protocol.BothyExtensions
import cc.noharry.bothy.protocol.ProtocolMethods
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaFilesTest {
    @Test
    fun committedSchemaFilesMatchTheProtocol() {
        ProtocolSchema().files().forEach { (name, text) ->
            val committed = File(SCHEMA_DIR, name).takeIf { it.isFile }?.readText()
            assertTrue("schema/$name is out of date: run ./gradlew :bothy-protocol:generateSchema", committed == text)
        }
    }

    @Test
    fun registryListsEveryMethodTheProtocolDefines() {
        // The app server's methods and Bothy's own.
        val defined = listOf(File("../agent-app-protocol/src/main/kotlin"), File("src/main/kotlin"))
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .flatMap { file -> METHOD.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()
        val registered = ProtocolMethods.all.map { it.name }

        assertEquals("each method once in ProtocolMethods", registered.size, registered.toSet().size)
        assertEquals("ProtocolMethods lists every RequestMethod and NotificationMethod", defined.toList(), registered.sorted())
    }

    @Test
    fun sealedMembersCarryTheirTypeAndShareNoName() {
        val schema = ProtocolSchema()
        val typeScript = schema.files().getValue(ProtocolSchema.TYPESCRIPT_FILE)

        assertTrue(typeScript.contains("""| ({ type: "run" } & StateSectionRun)"""))
        assertTrue(typeScript.contains("""| ({ type: "speech" } & A2aClientItemSpeech)"""))
        assertTrue(typeScript.contains("""| { type: "disconnected" }"""))
        assertEquals(schema.names.size, schema.names.values.toSet().size)
    }

    @Test
    fun everyExtensionSectionIsTypedByItsName() {
        val schema = ProtocolSchema()
        val typeScript = schema.files().getValue(ProtocolSchema.TYPESCRIPT_FILE)

        assertTrue(typeScript.contains("export interface ExtensionSections {"))
        assertTrue(typeScript.contains("  voice: VoiceSection;"))
        assertEquals(BothyExtensions.sections.keys.toList(), schema.extensionTypes.map { it.first })
    }

    private companion object {
        val SCHEMA_DIR = File("schema")
        val METHOD = Regex("""(?:Request|Notification)Method\(\s*"([^"]+)"""")
    }
}
