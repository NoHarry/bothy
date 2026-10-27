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
    }
}

rootProject.name = "bothy"

// The demo apps build on Android Agent Runtime (cc.noharry:agent-*). With its repository checked out beside this one,
// Gradle builds it from source in place of the published artifacts; -PagentRuntimeDir=… points elsewhere, and
// -PagentRuntimeDir= (empty) uses the published version.
val agentRuntimeDir = providers.gradleProperty("agentRuntimeDir").getOrElse("../../../../android-agent-runtime")
if (agentRuntimeDir.isNotEmpty() && file(agentRuntimeDir).resolve("settings.gradle.kts").isFile) {
    includeBuild(agentRuntimeDir)
}

include(":bothy-app")
include(":bothy-voice")
include(":bothy-playground")
