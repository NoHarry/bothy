@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.api.ExecEngine
import cc.noharry.bothy.kernel.api.ExecRequest
import cc.noharry.bothy.kernel.api.ExecResult
import cc.noharry.bothy.kernel.api.ExecToolBridge
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecEngineConcurrencyDeclarationTest {
    @Test
    fun declaredEngineUsesExplicitSerializedConcurrency() {
        val engine = object : ExecEngine {
            override val providerConcurrency: ProviderConcurrencyDeclaration =
                ProviderConcurrencyDeclaration.serialized()

            override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult =
                ExecResult(ok = true)
        }
        assertEquals(ProviderConcurrencyDeclaration.serialized(), engine.providerConcurrency)
    }
}
