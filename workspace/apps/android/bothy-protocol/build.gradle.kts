plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Bothy's part of the wire between its server and its front ends (the console, the phone screens, the overlay, a PC
// over adb): the app server's protocol (:agent-app-protocol) plus Bothy's own methods, sections and finding codes
// (voice, checks, the on-device model, companion chat, connectors, onboarding). Plain Kotlin, no Android.
kotlin {
    jvmToolchain(17)
}

// The schema generator (src/schema) writes schema/ from the protocol's types; it is not part of what the app ships.
val schema: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

sourceSets.test {
    compileClasspath += schema.output
    runtimeClasspath += schema.output
}

dependencies {
    api(project(":agent-app-protocol"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}

// Like Codex's `app-server generate-json-schema` / `generate-ts`: the JSON Schema and the TypeScript types of every
// method, committed so clients in any language follow the protocol; SchemaFilesTest fails when they are stale.
tasks.register<JavaExec>("generateSchema") {
    group = "build"
    description = "Writes the protocol's JSON Schema and TypeScript types to schema/."
    classpath = schema.runtimeClasspath
    mainClass.set("cc.noharry.bothy.protocol.schema.ProtocolSchemaKt")
    args(layout.projectDirectory.dir("schema").asFile.absolutePath)
}
