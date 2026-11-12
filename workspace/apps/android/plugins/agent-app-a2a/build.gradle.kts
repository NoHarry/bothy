plugins {
    id("com.android.library")
}

description = "The app server's A2A plugin: the person's own agents as A2A profiles, the hand-off tool, " +
    "delegations, the local endpoint and client mode. Experimental."

/*
 * The app server's A2A plugin: the person's own agents as A2A profiles (connected, followed, reported back), the bridge
 * tool that hands them a task, the local endpoint other A2A clients on the device hand tasks to (with its token), and
 * client mode, which talks to that endpoint. A host that wants it lists A2aPlugin(host) among its plugins; the app server
 * itself knows nothing of it and carries none of the runtime's A2A module (agent-a2a), which comes with this one, with
 * the official A2A Java SDK its official client runs on. Its debug receiver (A2aProfileBroadcastReceiver) is declared by
 * a host that wants it in its own debug manifest.
 */
android {
    namespace = "cc.noharry.agent.appserver.a2a"
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
    api("cc.noharry:agent-a2a:$agentRuntimeVersion")
    // The official A2A Java SDK, for agent-a2a's official client (OfficialA2aClientModule, the official-java-sdk
    // profiles), which agent-a2a compiles against without carrying it; without its own a2a-java-sdk-common, as agent-a2a
    // has a copy of that which runs on Android.
    listOf("a2a-java-sdk-client", "a2a-java-sdk-http-client-android").forEach { sdk ->
        implementation("org.a2aproject.sdk:$sdk:1.1.0.Final") { exclude(group = "org.a2aproject.sdk", module = "a2a-java-sdk-common") }
    }
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

// AGP's local-test resource APK must also refresh when the tests' assets (the presets a host may ship) change.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}UnitTestForUnitTest" }.configureEach {
        inputs.dir("src/test/assets")
    }
}

// Its tests start it on the server's plugin host, which the server keeps public only for tests.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}
