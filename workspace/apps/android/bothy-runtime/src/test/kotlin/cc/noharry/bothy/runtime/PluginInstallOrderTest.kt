package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import org.junit.Assert.assertEquals
import org.junit.Test
import cc.noharry.bothy.kernel.core.ProfileCompiler

@OptIn(ExperimentalAgentRuntimeApi::class)
class PluginInstallOrderTest {
    @Test
    fun topologicalInstall_isDeterministicRegardlessOfInputOrder() {
        val root = testKey("root")
        val mid = testKey("mid")
        val leaf = testKey("leaf")
        val observed = mutableListOf<String>()

        fun plugin(
            id: String,
            provides: Set<cc.noharry.bothy.kernel.api.CapabilityKey<*>>,
            requires: Set<cc.noharry.bothy.kernel.api.CapabilityKey<*>>,
            value: String,
        ) = testPlugin(id, testDescriptor(id, provides = provides, requires = requires)) {
            observed += id
            provide(
                provides.single() as cc.noharry.bothy.kernel.api.CapabilityKey<String>,
                value,
                testSerializedConcurrency,
            )
        }

        val plugins = listOf(
            plugin("leaf", setOf(leaf), setOf(mid), "c"),
            plugin("root", setOf(root), emptySet(), "a"),
            plugin("mid", setOf(mid), setOf(root), "b"),
        )

        val first = ProfileCompiler().compile(testProfile(), plugins)
        assertEquals(listOf("root", "mid", "leaf"), observed.toList())
        first.close()
        observed.clear()

        val reversed = ProfileCompiler().compile(testProfile(), plugins.reversed())
        assertEquals(listOf("root", "mid", "leaf"), observed.toList())
        reversed.close()
    }
}
