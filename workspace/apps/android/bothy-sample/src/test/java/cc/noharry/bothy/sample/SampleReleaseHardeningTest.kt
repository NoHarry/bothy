package cc.noharry.bothy.sample

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo's own release and hygiene checks, which used to sit in the SDK's tests and read this module from there. */
class SampleReleaseHardeningTest {
    @Test
    fun releaseBuildEnablesR8OverTheAndroidRuntime() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(buildFile.contains("agent-runtime-android"))
        assertTrue(buildFile.contains("release"))
        assertTrue(buildFile.contains("isMinifyEnabled = true"))
        assertTrue(buildFile.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
        assertTrue(buildFile.contains("sourceCompatibility = JavaVersion.VERSION_17"))
    }

    @Test
    fun releaseSmokeActivityReferencesRuntimeApi() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = File("src/main/java/cc/noharry/bothy/sample/AgentRuntimeSmokeActivity.java").readText()

        assertTrue(manifest.contains("AgentRuntimeSmokeActivity"))
        assertTrue(activity.contains("AgentRuntimeBuilder.create().build()"))
        assertTrue(activity.contains("new AgentRequest("))
    }

    @Test
    fun noExecutePlanCompatibilityPathRemains() {
        val main = File("src/main")
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "json", "md", "txt", "xml") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    PLAN_COMPAT_TOKENS.firstOrNull(line::contains)?.let { "${file.path}:${index + 1}:$it" }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun providersDeclareExplicitConcurrency() {
        val missing = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> implementsProvider(file.readText()) }
            .filterNot { file -> file.readText().contains("override val providerConcurrency") }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), missing)
    }

    private fun implementsProvider(text: String): Boolean {
        val code = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//.*"""), " ").replace(Regex("""\s+"""), " ")
        return PROVIDER_INTERFACES.any { name ->
            Regex("""(?:class|object)(?:\s+\w[\w.]*)?\s*(?:\([^;{]*\))?\s*:\s*[^;{]*\b${Regex.escape(name)}\b""").containsMatchIn(code)
        }
    }
}

private val PLAN_COMPAT_TOKENS = listOf("execute_plan", "COMPAT_PLAN", "PlanExecutor", "PendingPlan")
private val PROVIDER_INTERFACES = listOf("ModelClient", "ToolProvider", "ExecEngine", "McpClientProvider")
