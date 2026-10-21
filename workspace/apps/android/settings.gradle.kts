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

include(":bothy-kernel-api")
include(":bothy-kernel-core")
include(":bothy-minimal")
include(":bothy-runtime")
include(":bothy-vfs")
include(":bothy-vfs-saf")
include(":bothy-exec-js")
include(":bothy-device-capabilities")
include(":bothy-android-use")
include(":bothy-voice")
include(":bothy-a2a-client")
include(":bothy-a2a-client-direct")
include(":bothy-a2a-client-official")
include(":bothy-a2a-server")
include(":bothy-sample")
include(":bothy-playground")
