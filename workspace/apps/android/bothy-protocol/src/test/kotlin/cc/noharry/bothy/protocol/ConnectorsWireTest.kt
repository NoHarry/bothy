package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.McpSecretsView
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorsWireTest {
    @Test
    fun everyStatusComesBackTheSame() {
        val catalog = ConnectorCatalog(
            connectors = listOf(ConnectorView("grok-bot", "Grok Bot", "Grok", "助手", "智能体", "G", 0xFF111111)),
            statuses = mapOf(
                "a" to ConnectorStatus.Disconnected,
                "b" to ConnectorStatus.Pairing("1234", "https://relay/connect?code=1234", 99L),
                "c" to ConnectorStatus.Connected(verified = true),
                "d" to ConnectorStatus.Paused,
            ),
        )
        val wire = ProtocolJson.encodeToString(ConnectorCatalog.serializer(), catalog)
        assertEquals(catalog, ProtocolJson.decodeFromString(ConnectorCatalog.serializer(), wire))
        assertTrue(wire, "\"type\":\"pairing\"" in wire && "\"type\":\"paused\"" in wire)
    }

    @Test
    fun aStoredSecretIsOnlyAFlag() {
        val wire = ProtocolJson.encodeToString(McpSecretsView.serializer(), McpSecretsView(token = true, headers = 2))
        assertEquals("""{"token":true,"headers":2,"query":false}""", wire)
    }
}
