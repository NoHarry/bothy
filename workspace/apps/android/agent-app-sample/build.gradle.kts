plugins {
    id("com.android.application")
}

/*
 * The smallest app on the app server: no features, the generic persona, one screen that is a protocol client. What a
 * standalone phone agent, or any app taking the server as its core, starts from; it uses no app's plugins.
 */
android {
    namespace = "cc.noharry.agent.appserver.sample"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "cc.noharry.agent.appserver.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        // The app server's workspace and event log use java.nio.file, which Android has from API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    implementation(project(":agent-app-server"))
    implementation(project(":agent-app-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}
