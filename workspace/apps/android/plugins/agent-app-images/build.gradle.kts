plugins {
    id("com.android.library")
}

description = "The app server's pictures plugin: an image model and its image_generate tool. Experimental."

/*
 * The app server's pictures plugin: the image model's settings (imageModel/read, save, models, try), the image_generate
 * tool while it is set up, its pictures in the conversation's folder of the server's picture store, and which picture
 * each run is drawing for the screens (the offers section's pictures). A host that wants it lists ImagesPlugin among
 * its plugins; the app server itself knows nothing of it. Its debug receiver (ImageModelBroadcastReceiver) is declared
 * by a host that wants it in its own debug manifest.
 */
android {
    namespace = "cc.noharry.agent.appserver.image"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

// Its tests start it on the server's plugin host, which the server keeps public only for tests.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}
