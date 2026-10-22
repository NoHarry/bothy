@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRawToolTranscriptHookTest {
    @Test
    fun appendsStructuredToolDataWithSecretsAndPiiRedacted() = runBlocking {
        val entries = mutableListOf<Pair<String, String>>()
        val hook = DebugRawToolTranscriptHook { title, body -> entries += title to body }
        val call = ToolUse(
            toolName = "command_ac_TemperatureAdjust",
            argumentsJson = """{"position":"driver","action":"set","value":22,"token":"token-secret","credentials":{"api_key":"api-secret","password":"pass-secret"},"headers":{"Authorization":"Bearer auth-secret"}}""",
            source = ToolSource.BACKEND,
            toolCallId = "call-ac-1",
        )

        hook.onPreToolUse(call, hookContext())
        hook.onPostToolUse(
            call,
            ToolUseResult(
                ok = true,
                resultJson = """{"structuredContent":{"tts":"温度已调到22度","email":"driver@example.com","phone":"+1 415 555 0100","address":"1 Main Street","secret":"home-address"}}""",
            ),
            hookContext(),
        )

        assertEquals("Raw tool arguments", entries[0].first)
        assertTrue(entries[0].second.contains("toolCallId=call-ac-1"))
        assertTrue(entries[0].second.contains("tool=command_ac_TemperatureAdjust"))
        assertTrue(entries[0].second.contains(""""value":22"""))
        assertTrue(entries[0].second.contains(""""token":"[REDACTED]"""))
        assertEquals("Raw tool result", entries[1].first)
        assertTrue(entries[1].second.contains("toolCallId=call-ac-1"))
        assertTrue(entries[1].second.contains("温度已调到22度"))
        val joined = entries.joinToString("\n") { it.second }
        listOf(
            "token-secret",
            "api-secret",
            "pass-secret",
            "auth-secret",
            "driver@example.com",
            "+1 415 555 0100",
            "1 Main Street",
            "home-address",
        ).forEach { sensitive -> assertFalse(joined.contains(sensitive)) }
        assertFalse(joined.contains(""""redacted":true"""))
    }

    @Test
    fun malformedJsonFallsBackToFailSafeCredentialAndPiiRedaction() {
        val redacted = redactDebugToolPayload(
            """{"token":"broken-token","nested":{"address":"1 Main Street","email":"driver@example.com"""",
        )

        assertTrue(redacted.contains("[REDACTED"))
        listOf("broken-token", "1 Main Street", "driver@example.com").forEach { sensitive ->
            assertFalse(redacted.contains(sensitive))
        }
    }
}

private fun hookContext(): HookContext =
    HookContext(
        sessionId = "session",
        runId = "run",
        requestId = "request",
    )
