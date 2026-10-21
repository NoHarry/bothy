@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.WorkspaceCallContext

fun MaterializerContext.toWorkspaceCallContext(): WorkspaceCallContext =
    WorkspaceCallContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
    )

fun WorkspaceCallContext.toMaterializerContext(
    timeoutMillis: Long = DEFAULT_MATERIALIZER_TIMEOUT_MILLIS,
): MaterializerContext =
    MaterializerContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
        timeoutMillis = timeoutMillis,
    )
