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

include(":agent-kernel-api")
include(":agent-kernel-core")
include(":agent-minimal")
include(":agent-runtime")
include(":agent-vfs")
include(":agent-vfs-saf")
include(":agent-exec-js")
include(":agent-device-capabilities")
include(":agent-android-use")
include(":agent-voice")
include(":agent-a2a-client")
include(":agent-a2a-client-direct")
include(":agent-a2a-client-official")
include(":agent-a2a-server")
include(":bothy-sample")
include(":bothy-playground")
