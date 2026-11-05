// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugin on this classpath sets the compiler
// version for Android and JVM modules alike, and the Compose compiler plugin must match it.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// One version for the app and the server it carries: a release takes its tag's (-PbothyVersion=0.1.0), a development
// build says which commit it is.
fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val bothyVersionCode = providers.gradleProperty("bothyVersionCode").orNull?.toIntOrNull()
    ?: gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val bothyCommit = gitOutput("rev-parse", "--short", "HEAD").orEmpty()
extra["bothyVersionCode"] = bothyVersionCode
extra["bothyVersionName"] = providers.gradleProperty("bothyVersion").orNull?.takeIf { it.isNotBlank() }
    ?: ("0.1.0-dev.$bothyVersionCode" + if (bothyCommit.isNotEmpty()) "+$bothyCommit" else "")

// Unit tests create temp dirs (createTempDirectory, TemporaryFolder, Robolectric) and rarely delete them,
// which left 100k+ folders in the system Temp. Give every Test task its own java.io.tmpdir under the
// module's build directory, emptied before each run, so leftovers stay bounded and `clean` removes them.
subprojects {
    // Kotlin 2.2+ compiles interface bodies to JVM default methods; keep the Kotlin 1.9 DefaultImpls-only ABI so the
    // reviewed release API baseline stays byte-identical until an API freeze review decides otherwise.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
    tasks.withType<Test>().configureEach {
        val testTmp = project.layout.buildDirectory.dir("tmp/test-tmp/$name").get().asFile
        systemProperty("java.io.tmpdir", testTmp.absolutePath)
        doFirst {
            testTmp.deleteRecursively()
            testTmp.mkdirs()
        }
    }
}
