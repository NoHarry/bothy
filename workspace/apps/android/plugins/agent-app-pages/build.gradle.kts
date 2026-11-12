plugins {
    id("com.android.library")
}

description = "The app server's pages plugin: pages the model draws in the conversation. Experimental."

/*
 * The app server's pages plugin: show_html, pages the model draws in the conversation (HTML with its own styles and
 * script) while the person looks at it, kept under the conversation's folder and read back with pages/read. A host
 * that wants it lists PagesPlugin among its plugins; the app server itself knows nothing of it.
 */
android {
    namespace = "cc.noharry.agent.appserver.pages"
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
