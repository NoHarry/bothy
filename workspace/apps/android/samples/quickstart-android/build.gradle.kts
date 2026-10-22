plugins {
    id("com.android.application")
}

android {
    namespace = "cc.noharry.agent.samples.quickstart.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.noharry.agent.samples.quickstart"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":agent-runtime-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
}
