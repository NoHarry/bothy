@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.exec.js

import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import org.junit.Assert.assertEquals
import org.junit.Test

class ZiplineExecEngineConcurrencyDeclarationTest {
    @Test
    fun declaresSerializedConcurrency() {
        assertEquals(ProviderConcurrencyDeclaration.serialized(), ZiplineExecEngine().providerConcurrency)
    }
}
