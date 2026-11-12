pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // -PagentRuntimeRepo=<dir>: the runtime's artifacts (cc.noharry) from that local Maven repository only, such as
        // what the runtime's publishToMavenLocal -Dmaven.repo.local=<dir> wrote; for a version not on Maven Central, with
        // -PagentRuntimeDir= and -PagentRuntimeVersion=.
        providers.gradleProperty("agentRuntimeRepo").orNull?.takeIf { it.isNotEmpty() }?.let { repo ->
            exclusiveContent {
                forRepository { maven { url = file(repo).toURI() } }
                filter { includeGroup("cc.noharry") }
            }
        }
    }
}

rootProject.name = "bothy"

// The demo apps build on Android Agent Runtime (cc.noharry:agent-*), its app server (cc.noharry:agent-app-protocol and
// agent-app-server) among it. With its repository checked out beside this one, Gradle builds it from source in place of
// the published artifacts; -PagentRuntimeDir=… points elsewhere, and -PagentRuntimeDir= (empty) uses the published
// version (agentRuntimeVersion in gradle.properties, on Maven Central).
val agentRuntimeDir = providers.gradleProperty("agentRuntimeDir").getOrElse("../../../../android-agent-runtime")
if (agentRuntimeDir.isNotEmpty() && file(agentRuntimeDir).resolve("settings.gradle.kts").isFile) {
    includeBuild(agentRuntimeDir)
}

include(":bothy-protocol")
include(":bothy-server")
include(":bothy-app")
include(":bothy-voice")
include(":bothy-llm")
include(":bothy-playground")

// The app server's first-party plugins: source modules compiled into Bothy and not published (operating the screen and
// the device's tools come with the runtime's agent-android-use and agent-device-capabilities). They depend on the
// runtime's artifacts and on each other only, and name nothing of Bothy (harness.yaml's isolation rules,
// checkPluginsStandAlone), so plugins/ can be a repository of its own as it is.
listOf(
    "agent-app-cards", "agent-app-pages", "agent-app-memory", "agent-app-images", "agent-app-plans", "agent-app-a2a",
).forEach { plugin ->
    include(":$plugin")
    project(":$plugin").projectDir = file("plugins/$plugin")
}
