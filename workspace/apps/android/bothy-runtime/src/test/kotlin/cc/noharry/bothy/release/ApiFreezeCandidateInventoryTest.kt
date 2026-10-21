@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.release

import cc.noharry.bothy.kernel.api.AgentCapabilities
import cc.noharry.bothy.kernel.api.AgentEvent
import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentPluginDescriptor
import cc.noharry.bothy.kernel.api.AgentPluginScope
import cc.noharry.bothy.kernel.api.AgentProfile
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.kernel.api.SessionRunConflictPolicy
import cc.noharry.bothy.kernel.api.CapabilityCardinality
import cc.noharry.bothy.kernel.api.ExecEngine
import cc.noharry.bothy.kernel.api.ExecRequest
import cc.noharry.bothy.kernel.api.ExecResult
import cc.noharry.bothy.kernel.api.ExecToolBridge
import cc.noharry.bothy.kernel.api.HookContext
import cc.noharry.bothy.kernel.api.HookDecision
import cc.noharry.bothy.kernel.api.HookFailurePolicy
import cc.noharry.bothy.kernel.api.KernelApiRange
import cc.noharry.bothy.kernel.api.KernelApiVersion
import cc.noharry.bothy.kernel.api.PluginVersion
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.ProviderConcurrencyMode
import cc.noharry.bothy.kernel.api.CapabilityKey
import cc.noharry.bothy.kernel.api.RuntimeHook
import cc.noharry.bothy.kernel.api.RuntimeHookRegistration
import cc.noharry.bothy.kernel.api.SessionCapabilityContext
import cc.noharry.bothy.kernel.api.SessionCapabilityFactory
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolCallResult
import cc.noharry.bothy.kernel.api.ToolCallTrace
import cc.noharry.bothy.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.bothy.kernel.api.ToolDescriptor
import cc.noharry.bothy.kernel.api.ToolInitializeContext
import cc.noharry.bothy.kernel.api.ToolInitializeResult
import cc.noharry.bothy.kernel.api.ToolMatcher
import cc.noharry.bothy.kernel.api.ToolPromptDescriptor
import cc.noharry.bothy.kernel.api.ToolProvider
import cc.noharry.bothy.kernel.api.ToolProviderStatus
import cc.noharry.bothy.kernel.api.ToolResourceDescriptor
import cc.noharry.bothy.kernel.api.ToolSource
import cc.noharry.bothy.kernel.api.ToolUse
import cc.noharry.bothy.kernel.api.ToolUseResult
import cc.noharry.bothy.kernel.api.WorkspaceCallContext
import cc.noharry.bothy.kernel.api.WorkspaceExecutionResult
import cc.noharry.bothy.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.bothy.kernel.api.WorkspacePort
import cc.noharry.bothy.kernel.api.CancelPendingResult
import cc.noharry.bothy.kernel.api.ConfirmationResumeResult
import cc.noharry.bothy.kernel.api.PostUserInputResult
import cc.noharry.bothy.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.bothy.kernel.api.SessionEvent
import cc.noharry.bothy.kernel.api.SessionEventAppend
import cc.noharry.bothy.kernel.api.SessionEventLog
import cc.noharry.bothy.kernel.api.SessionEventLogDurability
import cc.noharry.bothy.kernel.api.SessionEventLogHealth
import cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus
import cc.noharry.bothy.kernel.api.SessionAttachmentRef
import cc.noharry.bothy.kernel.api.SessionEventPayload
import cc.noharry.bothy.kernel.api.SessionEventQuery
import cc.noharry.bothy.kernel.api.SessionEventType
import cc.noharry.bothy.kernel.api.SessionJson
import cc.noharry.bothy.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.kernel.api.model.ModelEvent
import cc.noharry.bothy.kernel.api.model.ModelImage
import cc.noharry.bothy.kernel.api.model.ModelMessage
import cc.noharry.bothy.kernel.api.model.ModelRequest
import cc.noharry.bothy.policy.PolicyAction
import cc.noharry.bothy.policy.PolicyContext
import cc.noharry.bothy.policy.PolicyDecision
import cc.noharry.bothy.policy.PolicyRule
import cc.noharry.bothy.protocol.AgentProtocolCatalog
import cc.noharry.bothy.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.bothy.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.bothy.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.bothy.kernel.api.reliability.RuntimeResult
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.kernel.api.reliability.SuggestedNextAction
import cc.noharry.bothy.runtime.AgentRuntimeBuilder
import cc.noharry.bothy.runtime.RuntimeWorkspaceRef
import cc.noharry.bothy.transport.HttpEvent
import cc.noharry.bothy.vfs.VfsEntry
import cc.noharry.bothy.vfs.VfsReadResult
import cc.noharry.bothy.vfs.VfsSource
import cc.noharry.bothy.vfs.VfsStat
import cc.noharry.bothy.vfs.VfsStatResult
import cc.noharry.bothy.vfs.VfsWriteMode
import cc.noharry.bothy.vfs.VfsWriteResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ApiFreezeCandidateInventoryTest {
    @Test
    fun candidateApiFreezeTokenInventoryIsExplicit() {
        assertEquals(expected("AgentRuntime methods"), publicMethodNames(AgentRuntime::class.java))
        assertEquals(expected("AgentRuntimeBuilder methods"), publicMethodNames(AgentRuntimeBuilder::class.java))
        assertEquals(expected("AgentRequest fields"), publicDataFields(AgentRequest::class.java))
        assertEquals(expected("SessionRunConflictPolicy tokens"), SessionRunConflictPolicy.entries.map { it.name })
        assertEquals(expected("RuntimeWorkspaceRef fields"), publicDataFields(RuntimeWorkspaceRef::class.java))
        assertKernelPluginAndEventLogSurface()
        assertExecSurface()
        assertHookSurface()
        assertPolicySurface()
        assertModelTransportSurface()
        assertRuntimeRecordSurface()
        assertVfsSurface()
    }

    private fun assertKernelPluginAndEventLogSurface() {
        assertEquals(expected("AgentPlugin methods"), publicMethodNames(AgentPlugin::class.java))
        assertEquals(expected("AgentPluginScope methods"), publicMethodNames(AgentPluginScope::class.java))
        assertEquals(expected("CapabilityCardinality tokens"), CapabilityCardinality.entries.map { it.name })
        assertEquals(expected("CapabilityKey fields"), publicDataFields(CapabilityKey::class.java))
        assertEquals(expected("AgentCapabilities fields"), publicObjectFields(AgentCapabilities::class.java))
        assertEquals(expected("WorkspacePort methods"), publicMethodNames(WorkspacePort::class.java))
        assertEquals(expected("WorkspaceCallContext fields"), publicDataFields(WorkspaceCallContext::class.java))
        assertEquals(expected("WorkspaceExecutionResult fields"), publicDataFields(WorkspaceExecutionResult::class.java))
        assertEquals(
            expected("WorkspaceExecutionTelemetry fields"),
            publicDataFields(WorkspaceExecutionTelemetry::class.java),
        )
        assertEquals(
            expected("ModelClientException fields"),
            publicDataFields(cc.noharry.bothy.kernel.api.model.ModelClientException::class.java),
        )
        assertEquals(expected("AgentProfile fields"), publicDataFields(AgentProfile::class.java))
        assertEquals(expected("AgentPluginDescriptor fields"), publicDataFields(AgentPluginDescriptor::class.java))
        assertEquals(expected("PluginVersion fields"), publicDataFields(PluginVersion::class.java))
        assertEquals(expected("KernelApiVersion fields"), publicDataFields(KernelApiVersion::class.java))
        assertEquals(expected("KernelApiRange fields"), publicDataFields(KernelApiRange::class.java))
        assertEquals(
            expected("KernelApiRange methods"),
            publicMethodNames(KernelApiRange::class.java).filter { it == "contains" },
        )
        assertEquals(expected("SessionCapabilityContext fields"), publicDataFields(SessionCapabilityContext::class.java))
        assertEquals(
            expected("SessionCapabilityFactory methods"),
            publicMethodNames(SessionCapabilityFactory::class.java),
        )
        assertEquals(expected("SessionEventLog methods"), publicMethodNames(SessionEventLog::class.java))
        assertEquals(expected("SessionEventLogHealth fields"), publicDataFields(SessionEventLogHealth::class.java))
        assertEquals(expected("SessionEventLogHealthStatus tokens"), SessionEventLogHealthStatus.entries.map { it.name })
        assertEquals(expected("SessionEventLogDurability tokens"), SessionEventLogDurability.entries.map { it.name })
        assertEquals(expected("SessionEvent fields"), publicDataFields(SessionEvent::class.java))
        assertEquals(expected("SessionEventAppend fields"), publicDataFields(SessionEventAppend::class.java))
        assertEquals(expected("SessionEventQuery fields"), publicDataFields(SessionEventQuery::class.java))
        assertEquals(expected("SessionEventPayload kinds"), publicNestedNames(SessionEventPayload::class.java))
        assertEquals(expected("SessionEventPayload fields"), publicNestedFieldSignatures(SessionEventPayload::class.java))
        assertEquals(expected("SessionJson kinds"), publicNestedNames(SessionJson::class.java))
        assertEquals(expected("SessionJson fields"), publicNestedFieldSignatures(SessionJson::class.java))
        assertEquals(expected("SessionAttachmentRef fields"), publicDataFields(SessionAttachmentRef::class.java))
        assertEquals(expected("SessionEventType tokens"), SessionEventType.entries.map { it.name })
    }

    private fun assertPolicySurface() {
        assertEquals(expected("PolicyRule methods"), publicMethodNames(PolicyRule::class.java))
        assertEquals(expected("PolicyAction variants"), publicNestedNames(PolicyAction::class.java))
        assertEquals(expected("PolicyAction fields"), publicNestedFieldSignatures(PolicyAction::class.java))
        assertEquals(expected("PolicyContext fields"), publicDataFields(PolicyContext::class.java))
        assertEquals(expected("PolicyDecision variants"), publicNestedNames(PolicyDecision::class.java))
        assertEquals(expected("PolicyDecision fields"), publicNestedFieldSignatures(PolicyDecision::class.java))
    }

    private fun assertModelTransportSurface() {
        assertEquals(expected("ModelRequest fields"), publicDataFields(ModelRequest::class.java))
        assertEquals(expected("AgentEvent variants"), publicNestedNames(AgentEvent::class.java))
        assertEquals(expected("AgentEvent fields"), publicNestedFieldSignatures(AgentEvent::class.java))
        assertEquals(expected("ModelMessage fields"), publicDataFields(ModelMessage::class.java))
        assertEquals(expected("ModelImage fields"), publicDataFields(ModelImage::class.java))
        assertEquals(expected("ModelEvent variants"), publicNestedNames(ModelEvent::class.java))
        assertEquals(expected("ModelEvent fields"), publicNestedFieldSignatures(ModelEvent::class.java))
        assertEquals(expected("HttpEvent variants"), publicNestedNames(HttpEvent::class.java))
        assertEquals(expected("HttpEvent fields"), publicNestedFieldSignatures(HttpEvent::class.java))
        assertEquals(
            expected("ConfirmationResumeResult tokens"),
            publicNestedNames(ConfirmationResumeResult::class.java),
        )
        assertEquals(
            expected("CancelPendingResult tokens"),
            publicNestedNames(CancelPendingResult::class.java),
        )
        assertEquals(
            expected("PostUserInputResult tokens"),
            publicNestedNames(PostUserInputResult::class.java),
        )
        assertEquals(
            expected("ProtocolRequestCloseLoopResult tokens"),
            publicNestedNames(ProtocolRequestCloseLoopResult::class.java),
        )
    }

    private fun assertRuntimeRecordSurface() {
        assertEquals(expected("RuntimeRunStatus tokens"), RuntimeRunStatus.entries.map { it.name })
        assertEquals(expected("RuntimeResult fields"), publicDataFields(RuntimeResult::class.java))
        assertEquals(expected("RuntimeRunSnapshot fields"), publicDataFields(RuntimeRunSnapshot::class.java))
        assertEquals(expected("RuntimeRunPhase tokens"), RuntimeRunPhase.entries.map { it.name })
        assertEquals(
            expected("RuntimeProtocolRequestStatus tokens"),
            RuntimeProtocolRequestStatus.entries.map { it.name },
        )
        assertEquals(expected("RuntimeEvidenceType tokens"), RuntimeEvidenceType.entries.map { it.name })
        assertEquals(expected("SuggestedNextAction tokens"), SuggestedNextAction.entries.map { it.name })
        assertEquals(expected("RuntimeErrorCode tokens"), RuntimeErrorCode.entries.map { it.name })
        assertEquals(expected("RuntimeErrorCode tokens"), RuntimeErrorCode.entries.map { it.wireCode })
    }

    private fun assertHookSurface() {
        assertEquals(expected("RuntimeHook methods"), publicMethodNames(RuntimeHook::class.java))
        assertEquals(expected("RuntimeHookRegistration fields"), publicDataFields(RuntimeHookRegistration::class.java))
        assertEquals(expected("HookFailurePolicy tokens"), HookFailurePolicy.entries.map { it.name })
        assertEquals(expected("ToolMatcher methods"), publicMethodNames(ToolMatcher::class.java))
        assertEquals(expected("ToolUse fields"), publicDataFields(ToolUse::class.java))
        assertEquals(expected("ToolSource tokens"), ToolSource.entries.map { it.name })
        assertEquals(expected("ToolUseResult fields"), publicDataFields(ToolUseResult::class.java))
        assertEquals(expected("HookContext fields"), publicDataFields(HookContext::class.java))
        assertEquals(expected("HookDecision variants"), publicNestedNames(HookDecision::class.java))
        assertEquals(expected("HookDecision fields"), publicNestedFieldSignatures(HookDecision::class.java))
    }

    private fun assertExecSurface() {
        assertEquals(expected("ExecEngine methods"), publicMethodNames(ExecEngine::class.java))
        assertEquals(expected("ModelClient methods"), publicMethodNames(ModelClient::class.java))
        assertEquals(expected("McpClientProvider methods"), publicMethodNames(McpClientProvider::class.java))
        assertEquals(expected("ToolProvider methods"), publicMethodNames(ToolProvider::class.java))
        assertEquals(
            expected("ToolCatalogCacheIdentity methods"),
            publicMethodNames(ToolCatalogCacheIdentity::class.java),
        )
        assertEquals(expected("ToolProviderStatus tokens"), ToolProviderStatus.entries.map { it.name })
        assertEquals(expected("ToolInitializeContext fields"), publicDataFields(ToolInitializeContext::class.java))
        assertEquals(expected("ToolInitializeResult fields"), publicDataFields(ToolInitializeResult::class.java))
        assertEquals(expected("ToolDescriptor fields"), publicDataFields(ToolDescriptor::class.java))
        assertEquals(expected("ToolCallContext fields"), publicDataFields(ToolCallContext::class.java))
        assertEquals(expected("ToolCallResult fields"), publicDataFields(ToolCallResult::class.java))
        assertEquals(expected("ToolCallTrace fields"), publicDataFields(ToolCallTrace::class.java))
        assertEquals(expected("ToolResourceDescriptor fields"), publicDataFields(ToolResourceDescriptor::class.java))
        assertEquals(expected("ToolPromptDescriptor fields"), publicDataFields(ToolPromptDescriptor::class.java))
        assertEquals(expected("ProviderConcurrencyMode tokens"), ProviderConcurrencyMode.entries.map { it.name })
        assertEquals(
            expected("ProviderConcurrencyDeclaration fields"),
            publicDataFields(ProviderConcurrencyDeclaration::class.java),
        )
        assertEquals(expected("ExecRequest fields"), publicDataFields(ExecRequest::class.java))
        assertEquals(expected("ExecToolBridge methods"), publicMethodNames(ExecToolBridge::class.java))
        assertEquals(expected("ExecResult fields"), publicDataFields(ExecResult::class.java))
    }

    private fun assertVfsSurface() {
        assertEquals(expected("VfsEntry fields"), publicDataFields(VfsEntry::class.java))
        assertEquals(expected("VfsReadResult variants"), publicNestedNames(VfsReadResult::class.java))
        assertEquals(expected("VfsReadResult fields"), publicNestedFieldSignatures(VfsReadResult::class.java))
        assertEquals(expected("VfsStat fields"), publicDataFields(VfsStat::class.java))
        assertEquals(expected("VfsStatResult variants"), publicNestedNames(VfsStatResult::class.java))
        assertEquals(expected("VfsStatResult fields"), publicNestedFieldSignatures(VfsStatResult::class.java))
        assertEquals(expected("VfsWriteMode tokens"), VfsWriteMode.entries.map { it.name })
        assertEquals(expected("VfsWriteResult variants"), publicNestedNames(VfsWriteResult::class.java))
        assertEquals(expected("VfsWriteResult fields"), publicNestedFieldSignatures(VfsWriteResult::class.java))
        assertEquals(expected("VfsSource methods"), publicMethodNames(VfsSource::class.java))
    }

    @Test
    fun protocolCatalogMatchesGoldenFixture() {
        assertEquals(
            resourceText("fixtures/protocol/agent_protocol_catalog.json"),
            AgentProtocolCatalog.canonicalJson(),
        )
    }

    private fun expected(group: String): List<String> =
        AgentProtocolCatalog.requireGroup(group)

    private fun publicMethodNames(type: Class<*>): List<String> =
        type.methods
            .filter { it.declaringClass == type }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filterNot { "$" in it }
            .distinct()
            .sorted()

    private fun publicNestedNames(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.simpleName }
            .sorted()

    private fun publicDataFields(type: Class<*>): List<String> =
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .sorted()

    private fun publicObjectFields(type: Class<*>): List<String> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filterNot { it == "INSTANCE" || "$" in it }
            .sorted()

    private fun publicNestedFieldSignatures(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .mapNotNull { nested ->
                val fields = publicDataFields(nested)
                if (fields.isEmpty()) null else "${nested.simpleName}:${fields.joinToString(",")}"
            }
            .sorted()

    private fun resourceText(path: String): String {
        val classLoader = requireNotNull(Thread.currentThread().contextClassLoader) {
            "Missing context class loader."
        }
        val stream = requireNotNull(classLoader.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }
}
