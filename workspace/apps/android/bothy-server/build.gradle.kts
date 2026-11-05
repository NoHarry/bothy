plugins {
    id("com.android.library")
}

/*
 * Bothy's server: the app server (:agent-app-server) with Bothy's features (voice, the on-device model, checks, the
 * Playground, its own settings), reached by front ends only through the protocol (:bothy-protocol). Its build carries
 * what the runtime needs from local.properties: the chat services' presets and the keys embedded in a development build.
 */
val PROVIDER_PROFILE_CONFIG_PATH = "src/main/assets/model-provider-profiles.json"
val LOCAL_PROPERTIES_FILE_NAME = "local.properties"

val PROVIDER_LOCAL_OVERRIDE_PREFIX = "AGENT_RUNTIME_PROVIDER_PROFILE_"
val PROVIDER_LOCAL_OVERRIDE_SUFFIX = "_API_KEY"
// A whole service from local.properties (not only its key), and which listed service is the default.
val PROVIDER_PROFILE_LOCAL_FIELDS = setOf("LABEL", "PROVIDER", "ENDPOINT", "BASE_URL", "MODEL", "API_KEY", "STREAM", "SEARCH_CAPABILITY")
val DEFAULT_PROFILE_LOCAL_KEY = "AGENT_RUNTIME_DEFAULT_PROFILE"
val LEGACY_PROVIDER_LOCAL_KEYS = setOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY")
val MIMO_PROVIDER_PROFILE_LOCAL_KEY = "AGENT_RUNTIME_PROVIDER_PROFILE_MIMO_API_KEY"
// all = local developer builds; tts/none = APKs handed to other people (everything in BuildConfig is extractable).
val EMBED_LOCAL_KEYS_PROPERTY = "bothyEmbedLocalKeys"
val EMBED_LOCAL_KEY_SCOPES = setOf("all", "tts", "none")
val MIMO_TTS_LOCAL_KEYS = setOf(
    "MIMO_TTS_ENABLED",
    "MIMO_TTS_API_KEY",
    "MIMO_TTS_BASE_URL",
    "MIMO_TTS_MODEL",
    "MIMO_TTS_VOICE",
    "MIMO_TTS_FORMAT",
    "MIMO_TTS_STYLE",
    "MIMO_TTS_OPTIMIZE_TEXT_PREVIEW",
    "MIMO_TTS_STREAM",
)
// 豆包语音合成 (DebugDoubaoTts): TTS_PROVIDER=doubao picks it for 播报; travels with the MiMo TTS keys (scope `tts`).
val DOUBAO_TTS_LOCAL_KEYS = setOf(
    "TTS_PROVIDER",
    "DOUBAO_TTS_APP_ID",
    "DOUBAO_TTS_API_KEY",
    "DOUBAO_TTS_RESOURCE_ID",
    "DOUBAO_TTS_VOICE",
    "DOUBAO_TTS_SPEECH_RATE",
)
// 生图模型: seeds Settings → 生图模型 once on first launch; embedded only with scope `all`.
val IMAGE_MODEL_LOCAL_KEYS = setOf(
    "IMAGE_MODEL_ENABLED",
    "IMAGE_MODEL_BASE_URL",
    "IMAGE_MODEL_NAME",
    "IMAGE_MODEL_API_KEY",
    "IMAGE_MODEL_SIZE",
)
// 设置 → 语音: seeds recognition (engine, 百炼 key/endpoint/model, hotwords) and the Jev connection once on first launch;
// embedded only with scope `all`. MiMo TTS reads MIMO_TTS_* above (or the MiMo model service's key).
val VOICE_SERVICE_LOCAL_KEYS = setOf(
    "VOICE_ASR_ENGINE",
    "DASHSCOPE_API_KEY",
    "DASHSCOPE_ASR_ENDPOINT",
    "DASHSCOPE_ASR_MODEL",
    "VOICE_HOTWORDS",
    "VOICE_OVERLAY_WAKE",
    "JEV_API_KEY",
    "JEV_ENDPOINT",
    "JEV_MODEL",
)
// 连接应用: overrides the relay preset inside Grok Bot (DEFAULT_AGENT_RELAY_URL). An address, not a key, so every APK
// carries it.
val CONNECTOR_LOCAL_KEYS = setOf("AGENT_RELAY_URL")
// 陪聊 (DebugCompanionConfig): COMPANION_SERVICE picks the service; each has its key, address and model. Seeds 设置 → 陪聊
// once on first launch; embedded only with scope `all`.
val COMPANION_SERVICES = listOf("QWEN", "DOUBAO", "HUNYUAN", "CUSTOM")
val COMPANION_LOCAL_KEYS = setOf("COMPANION_SERVICE") +
    COMPANION_SERVICES.flatMap { service -> listOf("API_KEY", "BASE_URL", "MODEL").map { "COMPANION_${service}_$it" } }
// -PbothyProviderPresets=none builds without the presets of 设置 → 模型与服务: no model-provider-profiles.json (in
// BuildConfig or in assets) and none of local.properties' chat services and their keys. The app then offers only the
// blank OpenAI and Anthropic templates. Voice, TTS and image model values still follow bothyEmbedLocalKeys.
val PROVIDER_PRESETS_PROPERTY = "bothyProviderPresets"
val providerPresets = providers.gradleProperty(PROVIDER_PRESETS_PROPERTY).orNull ?: "all"
require(providerPresets in setOf("all", "none")) { "$PROVIDER_PRESETS_PROPERTY must be all or none" }
// -PbothyDemoPackage=true builds the 演示包 (owner 2026-10-14: 「打演示包就是打包里面不带模型与服务的预置和豆包的预置，其他都带上」):
// no presets of 模型与服务 (as bothyProviderPresets=none) and no 豆包 values (豆包语音合成, 陪聊's 豆包 service, a 豆包
// choice of 陪聊), while 陪聊's other services, voice, MiMo and image model values stay.
val DEMO_PACKAGE_PROPERTY = "bothyDemoPackage"
val demoPackage = providers.gradleProperty(DEMO_PACKAGE_PROPERTY).orNull == "true"
val withoutProviderPresets = providerPresets == "none" || demoPackage
val providerConfigText = if (withoutProviderPresets) "" else loadProviderConfigText(projectDir)
val embedLocalKeyScope = providers.gradleProperty(EMBED_LOCAL_KEYS_PROPERTY).orNull ?: "all"
require(embedLocalKeyScope in EMBED_LOCAL_KEY_SCOPES) {
    "$EMBED_LOCAL_KEYS_PROPERTY must be one of $EMBED_LOCAL_KEY_SCOPES"
}
// Leaves some local.properties keys out of one build without editing the file, e.g.
// -PbothyEmbedExcludeLocalKeys=DASHSCOPE_*,IMAGE_MODEL_* (a trailing * matches every key starting with the rest).
val embedExcludedLocalKeys = providers.gradleProperty("bothyEmbedExcludeLocalKeys").orNull.orEmpty()
    .split(',').map(String::trim).filter(String::isNotEmpty)
val providerLocalOverridesText = loadProviderLocalOverrides(rootProject.projectDir, embedLocalKeyScope)
// The version code grows with history: the commit count of HEAD (-PbothyVersionCode overrides it; without git it is
// 1). The name adds the commit.

android {
    namespace = "cc.noharry.bothy.server"
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
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_NAME", (rootProject.extra["bothyVersionName"] as String).toBuildConfigString())
        val providerConfigSource = providerConfigSource(providerConfigText, providerLocalOverridesText)
        buildConfigField("String", "PROVIDER_ENV_SOURCE", providerConfigSource.toBuildConfigString())
        buildConfigField("boolean", "DEMO_PACKAGE", demoPackage.toString())
        buildConfigField("String", "ENV_MODEL_PROVIDER_CONFIG", providerConfigText.toBuildConfigString())
        buildConfigField("String", "ENV_MODEL_PROVIDER_LOCAL_OVERRIDES", providerLocalOverridesText.toBuildConfigString())
        buildConfigField("String", "ENV_AGENT_RUNTIME_MODEL_PROVIDER", "".toBuildConfigString())
        buildConfigField("String", "ENV_AGENT_RUNTIME_LIVE_PROVIDER", "".toBuildConfigString())
        buildConfigField("String", "ENV_AGENT_RUNTIME_MODEL_PROVIDER_STREAM", "".toBuildConfigString())
        buildConfigField("String", "ENV_AGENT_RUNTIME_LIVE_PROVIDER_STREAM", "".toBuildConfigString())
        buildConfigField("String", "ENV_OPENAI_API_KEY", "".toBuildConfigString())
        buildConfigField("String", "ENV_OPENAI_MODEL", "".toBuildConfigString())
        buildConfigField("String", "ENV_OPENAI_BASE_URL", "".toBuildConfigString())
        buildConfigField("String", "ENV_ANTHROPIC_API_KEY", "".toBuildConfigString())
        buildConfigField("String", "ENV_ANTHROPIC_MODEL", "".toBuildConfigString())
        buildConfigField("String", "ENV_ANTHROPIC_BASE_URL", "".toBuildConfigString())
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Production-builder unit tests need assets (runtime-workspace/workspace.json, Case Pack).
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

// Tests of the app server's own logic that run on Bothy's assets and plugins reach names the server keeps public only for
// them (InternalAppServerApi); Bothy's main code never does.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.add("cc.noharry.agent.appserver.InternalAppServerApi")
}

// AGP's local-test resource APK must also refresh when runtime skill assets change.
listOf("Debug", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}UnitTestForUnitTest" }.configureEach {
        dependsOn("merge${variant}Assets")
        inputs.dir("src/main/assets")
    }
}

dependencies {
    // The runtime's workspace and event log use java.nio.file, which Android has from API 26 (minSdk here is 24).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    implementation(platform("cc.noharry:agent-bom:${providers.gradleProperty("agentRuntimeVersion").get()}"))
    implementation("cc.noharry:agent-runtime-android")
    api(project(":bothy-protocol"))
    implementation(project(":agent-app-server"))
    implementation(project(":bothy-voice"))
    implementation(project(":bothy-llm"))
    listOf("debugImplementation", "releaseImplementation").forEach { scope ->
        add(scope, "cc.noharry:agent-exec-js")
        add(scope, "cc.noharry:agent-device-capabilities")
        add(scope, "cc.noharry:agent-android-use")
        add(scope, "cc.noharry:agent-a2a")
        add(scope, "cc.noharry:agent-a2a-client-official")
    }
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}

fun loadProviderConfigText(serverProjectDir: java.io.File): String {
    val configFile = serverProjectDir.resolve(PROVIDER_PROFILE_CONFIG_PATH)
    return configFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
}

fun loadProviderLocalOverrides(gradleRootDir: java.io.File, scope: String): String {
    val localFile = gradleRootDir.resolve(LOCAL_PROPERTIES_FILE_NAME)
    return localFile.takeIf { it.isFile }
        ?.readLines(Charsets.UTF_8)
        .orEmpty()
        .mapNotNull(::providerLocalOverrideLine)
        .filter { line -> isEmbeddedLocalKey(line.substringBefore("="), scope) }
        .filterNot { line -> isExcludedLocalKey(line.substringBefore("=")) }
        // A 演示包 has no 豆包 key for 陪聊, so it must not start on that service.
        .filterNot { line -> demoPackage && line.equals("COMPANION_SERVICE=doubao", ignoreCase = true) }
        .joinToString("\n")
}

fun isExcludedLocalKey(key: String): Boolean = demoExcludes(key) ||
    (providerPresets == "none" && isProviderPresetKey(key)) ||
    embedExcludedLocalKeys.any { pattern -> if (pattern.endsWith("*")) key.startsWith(pattern.dropLast(1)) else key == pattern }

/**
 * A chat service of 模型与服务 from local.properties: its fields and key, the default choice, the legacy keys. The MiMo
 * profile key stays: 语音播报 falls back to it when MIMO_TTS_API_KEY is not set.
 */
fun isProviderPresetKey(key: String): Boolean = key != MIMO_PROVIDER_PROFILE_LOCAL_KEY &&
    (key.startsWith(PROVIDER_LOCAL_OVERRIDE_PREFIX) || key == DEFAULT_PROFILE_LOCAL_KEY || key in LEGACY_PROVIDER_LOCAL_KEYS ||
        // 陪聊's services are chat models too (owner 2026-10-13: a build without presets carries no chat keys).
        key in COMPANION_LOCAL_KEYS)

/** The 演示包's cut: 模型与服务's presets and every 豆包 value; 陪聊's other services stay. */
fun demoExcludes(key: String): Boolean = demoPackage &&
    (key in DOUBAO_TTS_LOCAL_KEYS || key.startsWith("COMPANION_DOUBAO_") || isProviderPresetKey(key) && key !in COMPANION_LOCAL_KEYS)

fun isEmbeddedLocalKey(key: String, scope: String): Boolean = when {
    key in CONNECTOR_LOCAL_KEYS -> true
    else -> isEmbeddedSecretKey(key, scope)
}

fun isEmbeddedSecretKey(key: String, scope: String): Boolean = when (scope) {
    "all" -> true
    "tts" -> key in MIMO_TTS_LOCAL_KEYS || key in DOUBAO_TTS_LOCAL_KEYS || key == MIMO_PROVIDER_PROFILE_LOCAL_KEY
    else -> false
}

fun providerLocalOverrideLine(rawLine: String): String? {
    val trimmed = rawLine.trim()
    if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
    val split = trimmed.indexOf("=")
    if (split <= 0) return null
    val key = trimmed.substring(0, split).trim()
    val value = trimmed.substring(split + 1).trim()
    if (!isAllowedProviderLocalKey(key) || value.isBlank()) return null
    return "$key=$value"
}

fun isAllowedProviderLocalKey(key: String): Boolean =
    (key.startsWith(PROVIDER_LOCAL_OVERRIDE_PREFIX) && PROVIDER_PROFILE_LOCAL_FIELDS.any { key.endsWith("_$it") }) ||
        key == DEFAULT_PROFILE_LOCAL_KEY ||
        key in LEGACY_PROVIDER_LOCAL_KEYS ||
        key in MIMO_TTS_LOCAL_KEYS ||
        key in DOUBAO_TTS_LOCAL_KEYS ||
        key in IMAGE_MODEL_LOCAL_KEYS ||
        key in VOICE_SERVICE_LOCAL_KEYS ||
        key in CONNECTOR_LOCAL_KEYS ||
        key in COMPANION_LOCAL_KEYS

fun providerConfigSource(configText: String, localOverridesText: String): String =
    listOfNotNull(
        "BuildConfig:bothy-server/$PROVIDER_PROFILE_CONFIG_PATH".takeIf { configText.isNotBlank() },
        "BuildConfig:$LOCAL_PROPERTIES_FILE_NAME provider key overrides ($embedLocalKeyScope)".takeIf { localOverridesText.isNotBlank() },
        "演示包".takeIf { demoPackage },
    ).joinToString(" + ")

fun String.toBuildConfigString(): String =
    "\"" + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n") + "\""
