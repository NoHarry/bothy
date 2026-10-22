# Android Workspace Agent Map

This directory contains the Bothy Gradle workspace: an embeddable on-device agent SDK, its optional capability
modules, and a demo host app.

## Modules

| Module | Purpose |
|---|---|
| `agent-kernel-api` | Android-neutral Kernel SPI/DTO: plugin, capability, `WorkspacePort`, model, tool, completion, EventLog and lifecycle contracts. Depends on no project module. |
| `agent-kernel-core` | Scheduler, `NativeToolLoop`, `ProfileCompiler`, `KernelRuntimeBuilder` and `CoreAgentRuntime`. Depends only on `:agent-kernel-api`; never on VFS, runtime, sample or plugins. |
| `agent-minimal` | `MinimalAgent` profile composing core + api directly through `KernelRuntimeBuilder`; no runtime facade or VFS. |
| `agent-vfs` | JVM, semantic-free virtual filesystem primitives: manifest parsing, mount resolution, path normalization, snapshots. |
| `agent-runtime` | JVM composition artifact and host facade: `AgentRuntimeBuilder.create()`, model clients, VFS adapters, EventLog wiring and platform policy. Depends on core + api + `agent-vfs`; no Android dependency. |
| `agent-runtime-android` | Thin Android layer over `agent-runtime`: SQLite session store (`androidSqliteSessions()`) and the app-private durable EventLog (`DurableSessionEventLogPlugin.appPrivate(context)`). |
| `agent-vfs-saf` | Optional Storage Access Framework `VfsSource` backed by DocumentFile tree permissions. |
| `agent-exec-js` | Optional Zipline-backed `ExecEngine` for `exec_js` and nested tool bridge calls. |
| `agent-device-capabilities` | Optional calendar and notification MCP providers with permission error envelopes. |
| `agent-android-use` | Optional Android Use: accessibility service plus `android_observe` / `android_action` tools (screenshot first, elements to aim), a process-wide screen lease and a UI-free presenter SPI. |
| `agent-voice` | Optional voice session: microphone capture, streaming ASR SPI with on-device sherpa-onnx and DashScope engines, wake word, turn and addressee judgment. Depends on no project module. |
| `agent-a2a-client` | Optional, transport-neutral outbound A2A client facade and packaged-module SPI. |
| `agent-a2a-client-direct` | Optional direct JSON-RPC/SSE A2A client adapter. |
| `agent-a2a-client-official` | Optional adapter over the official A2A Java SDK, packaged in the sample for comparison. |
| `agent-a2a-server` | Optional inbound A2A 0.3 JSON-RPC/SSE loopback transport with bounded tasks. |
| `bothy-sample` | Demo host app and debug console (tablet first): model providers, MCP servers and 连接应用, skills, subagents, plans, voice, A2A and the floating assistant card. |
| `bothy-playground` | Second demo app: a fictional car cabin and flat served as two loopback MCP servers (`127.0.0.1:8790/cabin/mcp`, `/home/mcp`) with live state, manual controls and resources for plans. Depends on no module here. |

`tools/` holds host-side helpers: `agent-relay` (the Node relay that pairs the demo with a user's own cloud agent),
`a2a` (A2A interop smoke tests), `voice` (voice asset fetch and wake-answer synthesis) and the bench analyzers.

## Rules

- Keep business code under this directory; do not place Android source directly in the repository root.
- Dependency direction: sample → runtime-android → runtime → kernel-core → kernel-api, runtime → vfs. kernel-api, kernel-core,
  vfs and runtime are plain JVM modules; Android APIs belong in `agent-runtime-android` or an optional module. The runtime never depends on the
  sample, A2A, voice, Android Use or any optional module; optional modules depend at most on `:agent-kernel-api`
  (or `:agent-vfs` / `:agent-a2a-client` for their own adapters).
- Keep `agent-vfs` free of AGENTS, skills, MCP, session, memory or model semantics; those belong in the runtime.
- The generic Streamable HTTP MCP client (`cc.noharry.agent.mcp`: `McpHttpClient`, `McpToolProvider`) lives in
  `agent-runtime` and speaks English by default (`McpClientMessages`). Concrete MCP servers, connection pools, keys,
  wording and demo integrations live in `bothy-sample`; nothing from the sample flows back into the libraries. `bothy-playground` stays standalone: Bothy talks to it only over MCP, so it shows what any app can do.
- The playground's overlay spots (`SceneSpots.kt`) and the cabin's cut-out doors and tailgate (`CabinParts.kt`, cut from
  the cabin picture over a base with the openings painted in) are measured on its pictures; new pictures need them
  measured and cut again.
- Public API is 0.x and not frozen; breaking changes must be called out in release notes.
- Keep `consumer-proguard-rules.pro` in the libraries; a release (R8) build of the sample must keep passing.
- Provider assets packaged into the sample APK never contain real API keys. Real keys live in the ignored
  `local.properties` of this Gradle root; `-PsampleEmbedLocalKeys=none` builds an APK without any of them.
- Demo MCP servers are public services (连接应用): open ones need no key, keyed ones take the user's own key, stored in
  no-backup storage and never in the server address.
- The sample's plan watches read MCP resources, or call a server's read-only tool (MCP `readOnlyHint`) no more often
  than every five minutes; automation actions always pass `automation-policy.json`.

## Verify

```bash
./gradlew testDebugUnitTest :agent-kernel-api:test :agent-kernel-core:test :agent-vfs:test :agent-runtime:test :agent-minimal:test
./gradlew :bothy-sample:assembleDebug :bothy-sample:assembleRelease :bothy-playground:assembleDebug
node --test tools/agent-relay/relay.test.cjs
```
