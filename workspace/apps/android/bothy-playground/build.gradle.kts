import java.util.Properties

// The playground: a fictional car cabin and a fictional home behind a local MCP server, for Bothy to drive in demos.
// It depends on nothing else in this workspace; Bothy reaches it like any other MCP server.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: the dedicated key named by BOTHY_SIGNING_* in local.properties signs debug and release builds alike, so a
// build from any machine installs over the last one; without it, the machine's own debug key does.
val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? = signingProperties.getProperty("BOTHY_SIGNING_$name")?.trim()?.takeIf { it.isNotEmpty() }

// AMap's Android key (bound to this package and the signing key's SHA1), from local.properties; without it navigation says so.
val amapKey: String = signingProperties.getProperty("AMAP_ANDROID_KEY")?.trim().orEmpty()

// The version as Bothy's: the commits counted, so a newer build installs over an older one; a release's name from its tag.
val playgroundVersionCode: Int = providers.gradleProperty("bothyVersionCode").orNull?.toIntOrNull()
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toIntOrNull()
    }.getOrNull() ?: 1

android {
    namespace = "cc.noharry.bothy.playground"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cc.noharry.bothy.playground"
        minSdk = 24
        targetSdk = 34
        versionCode = playgroundVersionCode
        versionName = providers.gradleProperty("bothyVersion").orNull?.takeIf { it.isNotBlank() } ?: "0.1.0-dev.$playgroundVersionCode"
        manifestPlaceholders["AMAP_KEY"] = amapKey
        buildConfigField("boolean", "AMAP_CONFIGURED", amapKey.isNotEmpty().toString())
        // AMap ships 32- and 64-bit ARM libraries (some 100 MB together); tablets and head units are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        resources.excludes += "**/.DS_Store"
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
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The same Compose the sample resolves to (its A2UI renderer lifts it past the BOM), so one cache serves both.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.13.0-alpha03")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.ui:ui:1.13.0-alpha03")
    // The head unit's icons (turn arrows, media, climate); frozen at 1.7.8 upstream.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // AMap navigation, map, location and search (one bundle; separate artifacts clash). Closed source, from Maven Central;
    // it needs AMAP_ANDROID_KEY in local.properties and the user's consent to AMap's privacy terms before first use.
    implementation("com.amap.api:navi-3dmap-location-search:11.2.100_3dmap11.2.100_loc11.2.100_sea9.8.1")

    testImplementation("junit:junit:4.13.2")
}
