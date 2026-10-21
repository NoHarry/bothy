plugins {
    id("com.android.library")
}

android {
    namespace = "cc.noharry.bothy"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-proguard-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(project(":bothy-kernel-api"))
    api(project(":bothy-kernel-core"))
    api(project(":bothy-vfs"))
    // Public API exposes Flow/suspend signatures; api scope keeps external consumers compiling.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    // Public policy APIs expose JsonObject; api scope keeps external consumers compiling.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
