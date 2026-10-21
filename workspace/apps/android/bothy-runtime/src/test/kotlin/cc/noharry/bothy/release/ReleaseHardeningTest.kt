package cc.noharry.bothy.release

import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.runtime.ANDROID_RELIABILITY_STORAGE_MIN_SDK
import cc.noharry.bothy.transport.OkHttpHttpTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import cc.noharry.bothy.runtime.ReliabilityStorageEncryptionMode
import cc.noharry.bothy.runtime.ReliabilityStorageFileLockMode
import cc.noharry.bothy.runtime.ReliabilityStorageHardeningPolicy
import cc.noharry.bothy.runtime.ReliabilityStorageRootKind

class ReleaseHardeningTest {
    @Test
    fun consumerRulesKeepPublicApiAndMetadata() {
        val rules = File("consumer-proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class cc.noharry.bothy.kernel.api.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.kernel.core.KernelRuntimeBuilder { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.kernel.core.CoreAgentRuntime { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.minimal.MinimalAgent { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.runtime.AgentRuntimeBuilder { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.runtime.RuntimeWorkspaceRef { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.runtime.HostSkill { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.runtime.NetworkToolConfirmationPolicy { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.runtime.eventlog.DurableSessionEventLogPlugin { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.dream.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.model.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.policy.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.transport.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.bothy.vfs.** { *; }"))
        assertTrue(rules.contains("-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature"))
        assertFalse(rules.contains("\$\$serializer"))
    }

    @Test
    fun agentRuntimeBuilderConstructorIsNotPublic() {
        val publicConstructors = Class.forName("cc.noharry.bothy.runtime.AgentRuntimeBuilder")
            .declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue("Public AgentRuntimeBuilder constructors leaked: $publicConstructors", publicConstructors.isEmpty())
    }

    @Test
    fun sampleReleaseBuildEnablesR8WithProjectDependency() {
        val buildFile = File("../bothy-sample/build.gradle.kts").readText()

        assertTrue(buildFile.contains("implementation(project(\":bothy-runtime\"))"))
        assertTrue(buildFile.contains("release"))
        assertTrue(buildFile.contains("isMinifyEnabled = true"))
        assertTrue(buildFile.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
        assertTrue(buildFile.contains("sourceCompatibility = JavaVersion.VERSION_17"))
    }

    @Test
    fun sampleReleaseSmokeActivityReferencesRuntimeApi() {
        val manifest = File("../bothy-sample/src/main/AndroidManifest.xml").readText()
        val activity = File(
            "../bothy-sample/src/main/java/cc/noharry/bothy/sample/AgentRuntimeSmokeActivity.java",
        ).readText()

        assertTrue(manifest.contains("AgentRuntimeSmokeActivity"))
        assertTrue(activity.contains("AgentRuntimeBuilder.create().build()"))
        assertTrue(activity.contains("new AgentRequest("))
    }

    @Test
    fun minSdkAlignsWithCandidateReliabilityStoragePolicy() {
        val buildFile = File("build.gradle.kts").readText()

        assertEquals(23, ANDROID_RELIABILITY_STORAGE_MIN_SDK)
        assertTrue(buildFile.contains("minSdk = $ANDROID_RELIABILITY_STORAGE_MIN_SDK"))
    }

    @Test
    fun candidateReliabilityStoragePolicyIsExplicit() {
        val policy = ReliabilityStorageHardeningPolicy()

        assertEquals(ReliabilityStorageRootKind.APP_PRIVATE_NO_BACKUP, policy.rootKind)
        assertEquals(ReliabilityStorageFileLockMode.PER_OPERATION_ADVISORY, policy.fileLockMode)
        assertEquals(
            ReliabilityStorageEncryptionMode.HOST_APP_PRIVATE_STORAGE_REQUIRED,
            policy.encryptionMode,
        )
    }

    @Test
    fun publicRuntimeApiDoesNotExposeRawDebugTraceAccess() {
        val methodNames = AgentRuntime::class.java.methods
            .map { it.name.lowercase() }
            .filter { it.contains("raw") || it.contains("debug") }

        assertTrue("Public raw/debug trace API leaked: $methodNames", methodNames.isEmpty())
    }

    @Test
    fun publicTransportApiDoesNotExposeOkHttpTypes() {
        val publicConstructors = OkHttpHttpTransport::class.java.constructors
        val publicMethods = OkHttpHttpTransport::class.java.methods.filter(::isPublicDeclaredMethod)
        val exposedTypes = publicConstructors.flatMap { it.parameterTypes.toList() } +
            publicMethods.flatMap { method -> method.parameterTypes.toList() + method.returnType }

        assertFalse(exposedTypes.any { it.name.startsWith("okhttp3.") })
    }

    @Test
    fun candidatePublicApiClassRootsAreLoadable() {
        val missing = CANDIDATE_PUBLIC_API_CLASS_NAMES.filter { name ->
            runCatching { Class.forName(name) }.isFailure
        }
        assertTrue("Missing candidate public API classes: $missing", missing.isEmpty())
    }

    @Test
    fun kernelApiSourcePublicRootsMatchConfiguredInventory() {
        val sourceRoot = File("../bothy-kernel-api/src/main/kotlin")
        assertTrue("kernel-api source root must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val declared = sourcePublicRoots(sourceRoot).filter {
            it.startsWith("cc.noharry.bothy.kernel.api.")
        }.toSet()
        val configured = CANDIDATE_PUBLIC_API_CLASS_NAMES
            .filter { it.startsWith("cc.noharry.bothy.kernel.api.") }
            .toSet()
        val missing = (declared - configured).sorted()
        val stale = (configured - declared).sorted()
        assertTrue("kernel-api roots missing from ReleaseHardening inventory: $missing", missing.isEmpty())
        assertTrue("stale kernel-api roots in ReleaseHardening inventory: $stale", stale.isEmpty())
    }

    @Test
    fun sanctionedHostApiSourcePublicRootsMatchInventory() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue("runtime source root must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val declared = sourcePublicRoots(sourceRoot).filter { name ->
            SANCTIONED_HOST_API_PREFIXES.any(name::startsWith)
        }.toSet()
        val configured = CANDIDATE_PUBLIC_API_CLASS_NAMES.filter { name ->
            SANCTIONED_HOST_API_PREFIXES.any(name::startsWith)
        }.toSet()
        val missing = (declared - configured).sorted()
        val stale = (configured - declared).sorted()
        assertTrue("sanctioned host API roots missing from ReleaseHardening inventory: $missing", missing.isEmpty())
        assertTrue("stale sanctioned host API roots in ReleaseHardening inventory: $stale", stale.isEmpty())
    }

    @Test
    fun candidatePublicApiSurfaceDoesNotExposeForbiddenImplementationTypes() {
        val forbidden = candidatePublicApiRoots()
            .flatMap(::withNestedPublicClasses)
            .flatMap(::publicSignatureTypes)
            .map { it.normalizedTypeName() }
            .filter { typeName -> FORBIDDEN_PUBLIC_API_PREFIXES.any(typeName::startsWith) }
            .distinct()
            .sorted()

        assertTrue("Forbidden public API types leaked: $forbidden", forbidden.isEmpty())
    }

    private fun isPublicDeclaredMethod(method: java.lang.reflect.Method): Boolean =
        method.declaringClass == OkHttpHttpTransport::class.java &&
            Modifier.isPublic(method.modifiers)

    private fun candidatePublicApiRoots(): List<Class<*>> =
        CANDIDATE_PUBLIC_API_CLASS_NAMES.map { Class.forName(it) }

    private fun withNestedPublicClasses(type: Class<*>): List<Class<*>> =
        listOf(type) + type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap(::withNestedPublicClasses)

    private fun publicSignatureTypes(type: Class<*>): List<Class<*>> =
        publicConstructors(type).flatMap { it.parameterTypes.toList() } +
            publicMethods(type).flatMap { it.parameterTypes.toList() + it.returnType } +
            publicFields(type).map { it.type }

    private fun publicConstructors(type: Class<*>): List<Constructor<*>> =
        type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicMethods(type: Class<*>): List<Method> =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicFields(type: Class<*>): List<Field> =
        type.declaredFields.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun Class<*>.normalizedTypeName(): String =
        when {
            isArray -> componentType.normalizedTypeName()
            else -> name
        }
}

private val KERNEL_API_TYPE_DECL = Regex(
    """^(internal|private|protected)?\s*(?:(?:expect|actual|data|sealed|enum|value|fun|annotation|inner|open|final|abstract)\s+)*(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
)
private val KERNEL_API_PACKAGE = Regex("""(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")

private val SANCTIONED_HOST_API_PREFIXES = listOf(
    "cc.noharry.bothy.dream.",
    "cc.noharry.bothy.model.",
    "cc.noharry.bothy.policy.",
    "cc.noharry.bothy.transport.",
)

private fun sourcePublicRoots(sourceRoot: File): Set<String> {
    val roots = linkedSetOf<String>()
    val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
    fileLoop@ for (file in files) {
        val text = file.readText()
        val packageName = KERNEL_API_PACKAGE.find(text)?.groupValues?.get(1) ?: continue@fileLoop
        lineLoop@ for (raw in text.lineSequence()) {
            val line = raw.substringBefore("//").trimEnd()
            if (line.isEmpty() || line.first().isWhitespace() || line.startsWith("@")) {
                continue@lineLoop
            }
            val match = KERNEL_API_TYPE_DECL.find(line) ?: continue@lineLoop
            if (match.groupValues[1].isNotEmpty()) {
                continue@lineLoop
            }
            roots += "$packageName.${match.groupValues[2]}"
        }
    }
    return roots
}

private val FORBIDDEN_PUBLIC_API_PREFIXES = listOf(
    "okhttp3.",
    "cc.noharry.bothy.sample.",
)

private val CANDIDATE_PUBLIC_API_CLASS_NAMES = listOf(
    "cc.noharry.bothy.kernel.api.AgentRuntime",
    "cc.noharry.bothy.kernel.api.AgentRequest",
    "cc.noharry.bothy.kernel.api.AgentEvent",
    "cc.noharry.bothy.kernel.api.AgentCapabilities",
    "cc.noharry.bothy.kernel.api.AgentPlugin",
    "cc.noharry.bothy.kernel.api.AgentPluginDescriptor",
    "cc.noharry.bothy.kernel.api.AgentPluginScope",
    "cc.noharry.bothy.kernel.api.AgentProfile",
    "cc.noharry.bothy.kernel.api.CapabilityCardinality",
    "cc.noharry.bothy.kernel.api.CapabilityKey",
    "cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi",
    "cc.noharry.bothy.kernel.api.CompletionImpact",
    "cc.noharry.bothy.kernel.api.ContextStability",
    "cc.noharry.bothy.kernel.api.PluginContractMessages",
    "cc.noharry.bothy.kernel.api.PostUserInputRequest",
    "cc.noharry.bothy.kernel.api.PostUserInputResult",
    "cc.noharry.bothy.kernel.api.ProbeEmitterRegistration",
    "cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration",
    "cc.noharry.bothy.kernel.api.ProviderConcurrencyMode",
    "cc.noharry.bothy.kernel.api.RuntimeActionOutcome",
    "cc.noharry.bothy.kernel.api.RuntimeActionStatus",
    "cc.noharry.bothy.kernel.api.RuntimeCommitStatus",
    "cc.noharry.bothy.kernel.api.RuntimeCompletedTurn",
    "cc.noharry.bothy.kernel.api.RuntimeContextBlock",
    "cc.noharry.bothy.kernel.api.RuntimeContextContribution",
    "cc.noharry.bothy.kernel.api.RuntimeContextDatum",
    "cc.noharry.bothy.kernel.api.RuntimeContextDatumKind",
    "cc.noharry.bothy.kernel.api.RuntimeContextProvider",
    "cc.noharry.bothy.kernel.api.RuntimeContextRequest",
    "cc.noharry.bothy.kernel.api.RuntimeContextScalar",
    "cc.noharry.bothy.kernel.api.RuntimeEffectKind",
    "cc.noharry.bothy.kernel.api.RuntimeEvidenceConfig",
    "cc.noharry.bothy.kernel.api.RuntimeObserverActionPreflight",
    "cc.noharry.bothy.kernel.api.RuntimeProbeEmitterHub",
    "cc.noharry.bothy.kernel.api.RuntimeProbeStageEmitter",
    "cc.noharry.bothy.kernel.api.RuntimeTurnInput",
    "cc.noharry.bothy.kernel.api.RuntimeTurnObserver",
    "cc.noharry.bothy.kernel.api.SessionEvent",
    "cc.noharry.bothy.kernel.api.SessionEventAppend",
    "cc.noharry.bothy.kernel.api.SessionEventLog",
    "cc.noharry.bothy.kernel.api.SessionEventLogDurability",
    "cc.noharry.bothy.kernel.api.SessionEventLogHealth",
    "cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus",
    "cc.noharry.bothy.kernel.api.SessionEventPayload",
    "cc.noharry.bothy.kernel.api.SessionEventQuery",
    "cc.noharry.bothy.kernel.api.SessionEventType",
    "cc.noharry.bothy.kernel.api.SessionJson",
    "cc.noharry.bothy.kernel.api.SessionAttachmentRef",
    "cc.noharry.bothy.kernel.api.SessionRunConflictPolicy",
    "cc.noharry.bothy.kernel.api.TurnInputObservation",
    "cc.noharry.bothy.kernel.api.TurnObservationReceipt",
    "cc.noharry.bothy.kernel.api.ConfirmationDecision",
    "cc.noharry.bothy.kernel.api.ConfirmationChoice",
    "cc.noharry.bothy.kernel.api.PendingConfirmation",
    "cc.noharry.bothy.kernel.api.CancelPendingConfirmationRequest",
    "cc.noharry.bothy.kernel.api.ConfirmationResumeResult",
    "cc.noharry.bothy.kernel.api.CancelPendingResult",
    "cc.noharry.bothy.kernel.api.ExecEngine",
    "cc.noharry.bothy.kernel.api.ExecRequest",
    "cc.noharry.bothy.kernel.api.ExecResult",
    "cc.noharry.bothy.kernel.api.ExecToolBridge",
    "cc.noharry.bothy.kernel.api.HookContext",
    "cc.noharry.bothy.kernel.api.HookDecision",
    "cc.noharry.bothy.kernel.api.HookFailurePolicy",
    "cc.noharry.bothy.kernel.api.KernelApiRange",
    "cc.noharry.bothy.kernel.api.KernelApiVersion",
    "cc.noharry.bothy.kernel.api.KernelApiVersions",
    "cc.noharry.bothy.kernel.api.PluginVersion",
    "cc.noharry.bothy.kernel.api.ProtocolRequestResolution",
    "cc.noharry.bothy.kernel.api.ProtocolRequestRejection",
    "cc.noharry.bothy.kernel.api.ProtocolRequestCancellation",
    "cc.noharry.bothy.kernel.api.ProtocolRequestCloseLoopResult",
    "cc.noharry.bothy.kernel.api.RuntimeHook",
    "cc.noharry.bothy.kernel.api.RuntimeHookRegistration",
    "cc.noharry.bothy.kernel.api.SessionCapabilityContext",
    "cc.noharry.bothy.kernel.api.SessionCapabilityFactory",
    "cc.noharry.bothy.kernel.api.ToolCallContext",
    "cc.noharry.bothy.kernel.api.ToolCallResult",
    "cc.noharry.bothy.kernel.api.ToolCallTrace",
    "cc.noharry.bothy.kernel.api.ToolCatalogCacheIdentity",
    "cc.noharry.bothy.kernel.api.ToolDescriptor",
    "cc.noharry.bothy.kernel.api.ToolPrefetchProvider",
    "cc.noharry.bothy.kernel.api.ToolPrefetchRequest",
    "cc.noharry.bothy.kernel.api.ToolPrefetchTrace",
    "cc.noharry.bothy.kernel.api.ToolInitializeContext",
    "cc.noharry.bothy.kernel.api.ToolInitializeResult",
    "cc.noharry.bothy.kernel.api.ToolMatcher",
    "cc.noharry.bothy.kernel.api.ToolPromptDescriptor",
    "cc.noharry.bothy.kernel.api.ToolProvider",
    "cc.noharry.bothy.kernel.api.ToolProviderStatus",
    "cc.noharry.bothy.kernel.api.ToolResourceDescriptor",
    "cc.noharry.bothy.kernel.api.ToolSource",
    "cc.noharry.bothy.kernel.api.ToolUse",
    "cc.noharry.bothy.kernel.api.ToolUseResult",
    "cc.noharry.bothy.kernel.api.WorkspaceCallContext",
    "cc.noharry.bothy.kernel.api.WorkspaceExecutionResult",
    "cc.noharry.bothy.kernel.api.WorkspaceExecutionTelemetry",
    "cc.noharry.bothy.kernel.api.WorkspacePort",
    "cc.noharry.bothy.dream.DreamWorker",
    "cc.noharry.bothy.dream.DreamRequest",
    "cc.noharry.bothy.dream.DreamMode",
    "cc.noharry.bothy.dream.DreamResult",
    "cc.noharry.bothy.dream.NoOpDreamWorker",
    "cc.noharry.bothy.dream.MemoryIndexRebuilder",
    "cc.noharry.bothy.policy.PolicyRule",
    "cc.noharry.bothy.policy.PolicyAction",
    "cc.noharry.bothy.policy.PolicyContext",
    "cc.noharry.bothy.policy.PolicyDecision",
    "cc.noharry.bothy.kernel.api.mcp.McpClientProvider",
    "cc.noharry.bothy.kernel.api.model.ModelClient",
    "cc.noharry.bothy.kernel.api.model.ModelClientException",
    "cc.noharry.bothy.kernel.api.model.ModelImage",
    "cc.noharry.bothy.kernel.api.model.ModelRef",
    "cc.noharry.bothy.kernel.api.model.ModelRequest",
    "cc.noharry.bothy.kernel.api.model.ModelResponseMetadata",
    "cc.noharry.bothy.kernel.api.model.ModelMessage",
    "cc.noharry.bothy.kernel.api.model.ModelTool",
    "cc.noharry.bothy.kernel.api.model.ModelEvent",
    "cc.noharry.bothy.kernel.api.model.ModelVariant",
    "cc.noharry.bothy.kernel.api.model.ServerSideSearchCapability",
    "cc.noharry.bothy.model.OpenAiModelClient",
    "cc.noharry.bothy.model.AnthropicModelClient",
    "cc.noharry.bothy.model.ModelVariantRegistry",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeRunSnapshot",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeRunEnvelope",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeRunPhase",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeWorkpad",
    "cc.noharry.bothy.kernel.api.reliability.CompletionItem",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeHandoff",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeResult",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeBlocker",
    "cc.noharry.bothy.kernel.api.reliability.OpenConfusion",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeProtocolRequest",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeProtocolRequestStatus",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeErrorCode",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeEvidence",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeEvidenceType",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeTraceEvent",
    "cc.noharry.bothy.kernel.api.reliability.SuggestedNextAction",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeCandidate",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeCandidateType",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome",
    "cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionSummary",
    "cc.noharry.bothy.runtime.AgentRuntimeBuilder",
    "cc.noharry.bothy.runtime.RuntimeWorkspaceRef",
    "cc.noharry.bothy.runtime.HostSkill",
    "cc.noharry.bothy.runtime.NetworkToolConfirmationPolicy",
    "cc.noharry.bothy.runtime.eventlog.DurableSessionEventLogPlugin",
    "cc.noharry.bothy.kernel.core.KernelRuntimeBuilder",
    "cc.noharry.bothy.kernel.core.CoreAgentRuntime",
    "cc.noharry.bothy.vfs.VfsFileSystem",
    "cc.noharry.bothy.transport.HttpTransport",
    "cc.noharry.bothy.transport.HttpRequest",
    "cc.noharry.bothy.transport.HttpResponse",
    "cc.noharry.bothy.transport.HttpEvent",
    "cc.noharry.bothy.transport.OkHttpHttpTransport",
)
