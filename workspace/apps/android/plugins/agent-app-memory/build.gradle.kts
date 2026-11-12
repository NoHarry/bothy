plugins {
    id("com.android.library")
}

description = "The app server's memory plugin: what the model remembers about the person. Experimental."

/*
 * The app server's memory plugin: what the model remembers about the person across conversations (the user_memory tool,
 * what is remembered given to every round, the memory page's requests and section, the book in files/user-memory/),
 * behind the person's switch. A host that wants it lists MemoryPlugin among its plugins; the app server itself knows
 * nothing of it, and a host that does not reads and writes no memory file.
 */
android {
    namespace = "cc.noharry.agent.appserver.memory"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        targetSdk = 34
        unitTests.isIncludeAndroidResources = true
    }
}

// The app server is experimental (ExperimentalAppServerApi); a plugin builds on what the server keeps for plugins
// (AppServerPluginApi).
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi")
        optIn.add("cc.noharry.agent.appserver.AppServerPluginApi")
    }
}

dependencies {
    // The runtime's artifacts at the version the build names (agentRuntimeVersion).
    val agentRuntimeVersion = providers.gradleProperty("agentRuntimeVersion").get()
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    api("cc.noharry:agent-app-server:$agentRuntimeVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

// Its tests start it on the server's plugin host, which the server keeps public only for tests.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}
