plugins {
    id("com.android.library")
}

/*
 * The app server: the agent runtime, sessions, models, MCP, plans, A2A, memory and usage behind the protocol
 * (:agent-app-protocol), with no screens and nothing of any one app. A host app gives it its features, its assistant's
 * persona and what it plugs into the runtime (AppServerConfig); front ends reach it only through the protocol.
 */
android {
    namespace = "cc.noharry.agent.appserver"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The app's targetSdk; Robolectric runs up to API 34.
        targetSdk = 34
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "org/a2aproject/sdk/util/package-info.class"
        resources.pickFirsts += setOf(
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md",
            "META-INF/beans.xml",
        )
    }
}

// The server's own code may use what is public only for other modules' tests (InternalAppServerApi).
kotlin {
    compilerOptions {
        optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
    }
}

// AGP's local-test resource APK must also refresh when the workspace assets change.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}UnitTestForUnitTest" }.configureEach {
        dependsOn("merge${variant}Assets")
        inputs.dir("src/main/assets")
    }
}

dependencies {
    // The runtime's workspace and event log use java.nio.file, which Android has from API 26 (minSdk here is 24).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    // `api` for what the host-facing API names (the runtime's ModelClient, ToolProvider and AgentRuntime, OkHttp's
    // client, A2A's auth): a host implementing HostModels or RunAddOns compiles against them.
    api(platform("cc.noharry:agent-bom:${providers.gradleProperty("agentRuntimeVersion").get()}"))
    api("cc.noharry:agent-runtime-android")
    api(project(":agent-app-protocol"))
    listOf("debug", "release").forEach { variant ->
        add("${variant}Implementation", "cc.noharry:agent-exec-js")
        add("${variant}Implementation", "cc.noharry:agent-device-capabilities")
        add("${variant}Implementation", "cc.noharry:agent-android-use")
        add("${variant}Api", "cc.noharry:agent-a2a")
        add("${variant}Implementation", "cc.noharry:agent-a2a-client-official")
    }
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
