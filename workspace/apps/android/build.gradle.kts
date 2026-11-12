// AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin plugin on this classpath sets the compiler
// version for Android and JVM modules alike, and the Compose compiler plugin must match it.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // Its task types dump and check the plugins' public API (registerApiChecks, below).
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2" apply false
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

// The app server's first-party plugins (plugins/): what each module builds with, as in the runtime's own build. Interface
// bodies compile to JVM default methods with DefaultImpls kept beside them, as the app server's own; tests reach the
// runtime's internal seams as the runtime's modules' tests do. Their public API is recorded in api/<module>.api, as
// binary-compatibility-validator dumps it from the classes the module builds (an Android library's release classes,
// Kotlin and Java): apiCheck fails when they differ, apiDump writes it.
subprojects {
    if (projectDir.parentFile != rootProject.file("plugins")) return@subprojects
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
        if (name.endsWith("TestKotlin")) {
            compilerOptions.optIn.add("cc.noharry.agent.spi.InternalAgentRuntimeApi")
            compilerOptions.optIn.add("cc.noharry.agent.spi.ExperimentalAgentRuntimeApi")
        }
    }
    plugins.withId("com.android.library") { registerApiChecks("compileReleaseKotlin", "compileReleaseJavaWithJavac") }
    plugins.withId("org.jetbrains.kotlin.jvm") { registerApiChecks("compileKotlin", "compileJava") }
}

/** apiCheck and apiDump of the module's api/<module>.api, from the classes the two compile tasks write. */
fun Project.registerApiChecks(kotlinCompile: String, javaCompile: String) {
    val apiReader = configurations.create("apiReader") {
        isCanBeConsumed = false
    }
    dependencies {
        // What binary-compatibility-validator 0.18.2 reads classes with; follow it when the validator is updated.
        apiReader("org.ow2.asm:asm:9.6")
        apiReader("org.ow2.asm:asm-tree:9.6")
        apiReader("org.jetbrains.kotlin:kotlin-metadata-jvm:${org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)}")
    }
    val dumpName = "$name.api"
    val built = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
        // Looked up when the task is configured, after AGP has made the release tasks.
        inputClassesDirs.from(
            tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>(kotlinCompile).flatMap { it.destinationDirectory },
            tasks.named<JavaCompile>(javaCompile).flatMap { it.destinationDirectory },
        )
        runtimeClasspath.from(apiReader)
        outputApiFile.set(layout.buildDirectory.file("api/$dumpName"))
    }
    val check = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
        group = "verification"
        projectApiFile.set(layout.projectDirectory.file("api/$dumpName"))
        generatedApiFile.set(built.flatMap { it.outputApiFile })
    }
    tasks.register<Copy>("apiDump") {
        group = "other"
        from(built.flatMap { it.outputApiFile })
        into(layout.projectDirectory.dir("api"))
    }
    tasks.named("check") { dependsOn(check) }
}

// The plugins stand on their own: their classpaths reach the runtime (its artifacts, or its projects from source) and
// other plugins, never one of Bothy's modules, so plugins/ can be a repository of its own as it is.
val pluginProjects = subprojects.filter { it.projectDir.parentFile == rootProject.file("plugins") }
val checkPluginsStandAlone by tasks.registering {
    group = "verification"
    description = "The plugins' classpaths reach no Bothy module."
    val allowed = pluginProjects.map { it.path }.toSet()
    val reached = pluginProjects.map { plugin ->
        plugin.provider {
            val classpaths = plugin.configurations.filter {
                it.isCanBeResolved && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
            }
            classpaths.flatMap { classpath ->
                // This build's projects, not the runtime's when it is built from source.
                classpath.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ProjectComponentIdentifier }
                    .filter { it.buildTreePath == it.projectPath }.map { it.projectPath }
            }.map { "${plugin.path} -> $it" }
        }
    }
    inputs.property("reached", reached.map { it.get() }.flatten())
    doLast {
        val bothy = reached.flatMap { it.get() }.filter { it.substringAfter(" -> ") !in allowed }.distinct()
        check(bothy.isEmpty()) { "A plugin reaches a module of Bothy's: $bothy" }
    }
}
