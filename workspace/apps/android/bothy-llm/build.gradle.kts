plugins {
    id("com.android.library")
}

// An on-device chat model: llama.cpp's llama-server (Snapdragon build b12004, natives committed under jniLibs) run as a
// child process on 127.0.0.1, with the GGUF it serves downloaded on demand. Uses none of the runtime.
android {
    namespace = "cc.noharry.bothy.llm"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
