import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val LOCAL_PROPERTIES_FILE_NAME = "local.properties"

// Signing: the dedicated key named by BOTHY_SIGNING_* in local.properties signs debug and release builds alike, so a
// build from any machine installs over the last one; without it, the machine's own debug key does.
val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? = signingProperties.getProperty("BOTHY_SIGNING_$name")?.trim()?.takeIf { it.isNotEmpty() }

// A build without the chat services' presets leaves their file out of the APK (the server's build leaves them out of
// its BuildConfig); see bothy-server/build.gradle.kts.
val withoutProviderPresets = providers.gradleProperty("bothyProviderPresets").orNull == "none" ||
    providers.gradleProperty("bothyDemoPackage").orNull == "true"

android {
    namespace = "cc.noharry.bothy.app"
    // AndroidX A2UI 1.0.0-alpha01 and Compose 1.13 declare minCompileSdk 37.1 and AGP 9.1+.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "cc.noharry.bothy"
        // A2UI and Compose 1.13 require API 24; the SDK library modules stay at 23.
        minSdk = 24
        targetSdk = 34
        versionCode = rootProject.extra["bothyVersionCode"] as Int
        versionName = rootProject.extra["bothyVersionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        signingValue("STORE_FILE")?.let { path ->
            create("bothy") {
                storeFile = file(path)
                storePassword = signingValue("STORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS") ?: "bothy"
                keyPassword = signingValue("KEY_PASSWORD") ?: signingValue("STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("bothy")?.let { signingConfig = it }
        }
        release {
            // The dedicated key when local.properties names one, else the debug key so a release build still installs.
            signingConfig = signingConfigs.findByName("bothy") ?: signingConfigs.getByName("debug")
            // An open-source demo: nothing to hide, and nothing for shrinking to break (owner 2026-10-25: 「不需要混淆」).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The voice model is read from assets on every wake; storing it uncompressed avoids inflating 57 MB each time.
    androidResources {
        noCompress += "onnx"
        if (withoutProviderPresets) ignoreAssetsPatterns += "!model-provider-profiles.json"
    }

    packaging {
        // bothy-llm runs llama-server as a process from nativeLibraryDir, so the libraries are extracted on install.
        // The NPU-side kernels are Hexagon code: the NDK's strip does not know them.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libggml-htp-*.so"
        resources.excludes += "org/a2aproject/sdk/util/package-info.class"
        resources.pickFirsts += setOf(
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md",
            "META-INF/beans.xml",
        )
    }
}

dependencies {
    // The runtime's workspace and event log use java.nio.file, which Android has from API 26 (minSdk here is 24).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    // The server runs in this process; front ends reach it only through the protocol.
    implementation(project(":bothy-server"))
    implementation(project(":bothy-protocol"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    // Agent-to-UI (A2UI v0.9.1) renderer: catalog-allowlisted native Compose, no executed code. Alpha.
    implementation("androidx.a2ui:a2ui-model:1.0.0-alpha01")
    implementation("androidx.a2ui:a2ui-engine:1.0.0-alpha01")
    implementation("androidx.a2ui.compose:compose-runtime:1.0.0-alpha01")
    implementation("androidx.a2ui.compose:compose-ui:1.0.0-alpha01")
    implementation("androidx.compose.material3:material3-a2ui:1.0.0-alpha01")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pictures on cards (saved on the car or public https), cached and decoded at the slot's size.
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    // QR code for the 连接应用 pairing link (encoder only, pure Java).
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.13.0-alpha03")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.13.0-alpha03")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    // Device lane: the Trajectory cold read depends on real filesystem, lock and permission
    // behaviour that a JVM/Robolectric run cannot reproduce.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
