@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.runtime.AgentRuntimeBuilder
import cc.noharry.bothy.vfs.VfsFileSystem
import java.io.File

/** The runtime composition the sample builds every session on. */
internal fun sampleRuntimeAssembly(
    model: ModelClient,
    prompt: String,
    workspace: VfsFileSystem,
    eventLogRoot: File,
): AgentRuntimeBuilder = AgentRuntimeBuilder.create()
    .modelClient(model)
    .systemPrompt(prompt)
    .virtualWorkspace(workspace)
    .sessionEventLogRoot(eventLogRoot)
    .sessionActivationInheritance()
