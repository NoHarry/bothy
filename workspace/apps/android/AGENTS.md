# Android Workspace Agent Map

This directory contains the Bothy Gradle workspace: two demo apps built on
[Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), and the host-side tools they use.

## Modules

| Module | Purpose |
|---|---|
| `agent-app-protocol` | The app server's protocol (`cc.noharry.agent.appserver.protocol`), in the style of Codex's app-server: JSON-RPC requests, notifications and server→client approval requests over threads, turns and items; the state sections a front end renders (`ServerState`, with `ExtensionSection`s for an app's own); `ServerClient`; the generic finding codes (`FindingCode`); the method registry (`AppServerMethods`); and the pure logic both sides compute the same way (`view/`). Plain Kotlin/JVM, no Android. |
| `bothy-protocol` | Bothy's part of the protocol on top of it: voice, checks, the on-device model, companion chat, realtime, onboarding, 连接应用 and the Playground (methods, views, extension sections and their accessors), Bothy's finding codes (`BothyFindingCode`), the overlay card's projection (`view/OverlayHud`), the full registry (`ProtocolMethods`) and the schema generator. |
| `agent-app-server` | The generic app server (`cc.noharry.agent.appserver`), meant to move to the runtime's repository once stable: model providers, MCP servers, skills and subagents (the host supplies its own), plans, memory, usage and A2A behind the protocol (`ServerRoutes` plus one `RouteModule` per feature area), keeping the selected session's transcript and every other loaded one's live (`LoadedTranscripts`) so clients can follow any thread. A host app starts it with an `AppServerConfig`: its name, its `Persona`, its `AppServerFeature`s (requests, state sections, start-up work) what it plugs into the runtime (`VoicePort`, `HostModels`, `RunAddOns`, `ProviderPresets`) and the places its screens show (`HostPlaces`). Knows no app: no voice, no Bothy, no screens; a debug build also listens on `localabstract:<AppServerConfig.name>` (Bothy's `bothy`, for `tools/bothy-protocol`). Only what hosts use is public (`agent-app-server/api/agent-app-server.api`); front ends cannot reach it, because only hosts (`bothy-server`, `agent-app-sample`) depend on it. |
| `agent-app-sample` | The smallest app on the app server: no features, the generic persona, one screen that is a protocol client (`ServerClient`), the simulated model by default. What a standalone phone agent or an app taking the server as its core starts from; it uses nothing of Bothy. A debug build listens on `localabstract:agent-app-sample`. |
| `bothy-server` | Bothy's server: the app server with Bothy's features (`bothyFeatures`: voice with 小博's wake profile, identity, realtime and companion chat, Jev's tool search; the on-device model; checks; the Playground; onboarding and 连接应用), its persona, skills and subagent types, all `internal`. Its only public face is `BothyServer` (install, connect); the overlay is a protocol client too (`HostConnection`, `presentation/*` and `androidUse/*`). Holds the runtime's assets (skills, agents, model presets) and Bothy's strings. |
| `bothy-app` | The front ends, each a protocol client: the console (`console/`, tablets and head units), the phone screens (`phone/`), the floating assistant card (`overlay/`) and the settings pages, picked by 设置 → 界面布局 (`FrontEnd`; Auto follows the screen). On the phone layout 小博 does not rest over other apps (`OverlayPhoneRest`): the card comes only when woken or when a task or plan arrives. Sees neither the runtime SDK nor the server's internals. |
| `bothy-voice` | Voice sessions: on-device wake word and recognition (sherpa-onnx, natives committed), DashScope cloud recognition, turn taking (Jev), echo and near-speech gates. Moved from the runtime (its `agent-voice` up to 0.3.0); uses none of the runtime. |
| `bothy-llm` | The on-device chat model: llama.cpp's llama-server (Snapdragon build, natives committed) run as a child process on 127.0.0.1 behind a per-start key, NPU first then CPU, and the GGUFs it serves (Gemma 4 E4B, Qwen3 4B 2507, MiniCPM5 2B; `LOCAL_MODELS`) downloaded on request with resume and SHA-256 check. Uses none of the runtime; `bothy-server` wires it in as the built-in 本机模型 profile, whose models are picked like any profile's. |
| `bothy-playground` | Second demo app: a fictional car cabin and flat served as two loopback MCP servers (`127.0.0.1:8790/cabin/mcp`, `/home/mcp`) with live state, manual controls and resources for plans. Depends on no module here. |

`tools/` holds host-side helpers: `agent-relay` (the Node relay that pairs the demo with a user's own cloud agent),
`bothy-protocol` (a minimal Node client of the server's protocol, over `adb forward`) and `voice` (voice asset fetch and
wake-answer synthesis). The runtime's own tools (event-log reports, probe analyzer, A2A
interop runners) live in the runtime's repository.

## The runtime

- `agent-app-server` and `bothy-server` depend on `cc.noharry:agent-*` at `agentRuntimeVersion` (`gradle.properties`), aligned by `agent-bom`.
- With the runtime's repository checked out beside this one (`../../../../android-agent-runtime`), `settings.gradle.kts`
  includes it as a composite build, so the same coordinates build from source; `-PagentRuntimeDir=<path>` points
  elsewhere and `-PagentRuntimeDir=` uses the published version.
- Changes to the runtime are made, tested and committed in its repository, never here.

## Rules

- Keep business code under this directory; do not place Android source directly in the repository root.
- Front ends talk to the server only through the protocol: a new settings page adds its view type and `XxxRequests` to
  `bothy-protocol`, a `RouteModule` (in `coreModules` when every app server answers it, else in a feature's `routes`),
  and reads and writes through `LocalServer`. `agent-app-server` never imports `bothy-server`: what it needs from the
  host comes through `AppServerConfig`.
  Text the server says itself uses the server's strings; labels the screens map from an enum use the app's.
- `agent-app-server`'s public API is what hosts use: new declarations are `internal`; one only another module's tests
  need is `@InternalAppServerApi` (opt-in; only `bothy-server`'s unit tests opt in). A change to what hosts see fails
  validate until `python tools/app-server-api/api_dump.py --write` rewrites `agent-app-server/api/agent-app-server.api`.
- Every protocol method has one line in `AppServerMethods` (generic) or `ProtocolMethods` (Bothy's); after changing the protocol's types or methods, run
  `./gradlew :bothy-protocol:generateSchema` and commit `bothy-protocol/schema/` (`SchemaFilesTest` fails otherwise).
- Nothing from the sample flows back into the runtime: concrete MCP servers, connection pools, keys, 小博's persona and
  wake phrases, and Chinese wording stay here. `bothy-playground` stays standalone: Bothy talks to it only over MCP, so
  it shows what any app can do.
- The playground's overlay spots (`SceneSpots.kt`) and the cabin's cut-out doors and tailgate (`CabinParts.kt`, cut from
  the cabin picture over a base with the openings painted in) are measured on its pictures; new pictures need them
  measured and cut again.
- A release (R8) build of the sample must keep passing; the runtime ships its keep rules with its artifacts.
- Provider assets packaged into the sample APK never contain real API keys. Real keys live in the ignored
  `local.properties` of this Gradle root; `-PbothyEmbedLocalKeys=none` builds an APK without any of them.
- Demo MCP servers are public services (连接应用): open ones need no key, keyed ones take the user's own key, stored in
  no-backup storage and never in the server address.
- The sample's plan watches read MCP resources, or call a server's read-only tool (MCP `readOnlyHint`) no more often
  than every five minutes; automation actions always pass `automation-policy.json`.

## Verify

```bash
./gradlew :agent-app-protocol:test :bothy-protocol:test :agent-app-server:testDebugUnitTest :bothy-server:testDebugUnitTest :bothy-app:testDebugUnitTest :bothy-voice:testDebugUnitTest :bothy-llm:testDebugUnitTest :bothy-playground:testDebugUnitTest
./gradlew :bothy-app:assembleDebug :bothy-app:assembleRelease :bothy-playground:assembleDebug
node --test tools/agent-relay/relay.test.cjs
node --test tools/bothy-protocol/bothy.test.mjs
```
