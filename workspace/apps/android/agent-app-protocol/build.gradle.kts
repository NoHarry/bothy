plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The app server's protocol (in the style of Codex's app-server): JSON-RPC messages over threads, turns and items, the
// state sections a front end renders, a client that folds them into one state, and the pure logic both sides compute
// alike. An app adds its own methods and sections on top, in its own protocol module. Plain Kotlin, no Android.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
