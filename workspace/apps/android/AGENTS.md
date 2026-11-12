# Android Workspace Agent Map

This directory contains the Bothy Gradle workspace: two demo apps built on
[Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), and the host-side tools they use.

Bothy runs on the runtime's app server: its protocol (`agent-app-protocol`) and the server with its plugin kernel
(`agent-app-server`) are the runtime's two published artifacts for it, experimental, with the host guide
(`agent-app-server/README.md`), the apps on the server (`samples/agent-app-sample*`), the plugin package maker
(`tools/agent-plugin`) and the core's layering and API-level checks in the runtime's repository. The first-party plugins
are source modules here, in `plugins/`, compiled into Bothy and not published (below). The entry points ask for an
opt-in (`ExperimentalAppServerApi`), which `bothy-protocol`, `bothy-server`, `bothy-app` and the plugins give by
compiler option.

## Modules

| Module | Purpose |
|---|---|
| `bothy-protocol` | Bothy's part of the protocol on top of it: voice, checks, the on-device model, companion chat, realtime, onboarding, 连接应用 and the Playground (methods, views, extension sections and their accessors), Bothy's finding codes (`BothyFindingCode`), the overlay card's projection (`view/OverlayHud`, `view/OverlayParts`), wake origins, trip modes, the mascot preview request, the full registry (`ProtocolMethods`) and the schema generator. |
| `bothy-server` | Bothy's server: the app server with Bothy's features (`bothyFeatures`: voice with 小博's wake profile, identity, realtime and companion chat, Jev's tool search; the on-device model; checks; the Playground; onboarding and 连接应用), its persona, skills and subagent types, all `internal`. Its only public face is `BothyServer` (install, connect); the overlay is a protocol client too (`HostConnection`, `presentation/*` and `androidUse/*`). Its code reaches the server through `ServerLink`, the context its voice plugin was given (`turns`, `speech`, `assistant`, `request`), never through the server's own objects. Holds the runtime's assets (skills, agents, model presets) and Bothy's strings. |
| `bothy-app` | The front ends, each a protocol client: the console (`console/`, tablets and head units), the phone screens (`phone/`, after the Stitch design "Bothy 手机端" v2, in colours and type of their own: `PhoneTheme`, which the tablet panes they borrow take on too), the floating assistant card for tablets and head units (`overlay/`), the phone's own assistant over other apps (`phoneassist/`: a capsule at the top, a sheet from the bottom, Android 16 Live Updates; started instead of the overlay on a phone through `OverlayControl`), Bothy as the system's digital assistant (`assist/`, a long press of the power key) and the settings pages, picked by 设置 → 界面布局 (`FrontEnd`; Auto follows the screen, and a tablet held upright gets the phone screens). Pages the agent draws in the conversation (`show_html`) run in a sandboxed WebView (`page/`). On the phone layout 小博 does not rest over other apps (`OverlayPhoneRest`): the card comes only when woken or when a task or plan arrives. Sees neither the runtime SDK nor the server's internals. |
| `bothy-voice` | Voice sessions: on-device wake word and recognition (sherpa-onnx, natives committed), DashScope cloud recognition, turn taking (Jev), echo and near-speech gates. Moved from the runtime (its `agent-voice` up to 0.3.0); uses none of the runtime. |
| `bothy-llm` | The on-device chat model: llama.cpp's llama-server (Snapdragon build, natives committed) run as a child process on 127.0.0.1 behind a per-start key, NPU first then CPU, and the GGUFs it serves (Gemma 4 E4B, Qwen3 4B 2507, MiniCPM5 2B; `LOCAL_MODELS`) downloaded on request with resume and SHA-256 check. Uses none of the runtime; `bothy-server` wires it in as the built-in 本机模型 profile, whose models are picked like any profile's. |
| `bothy-playground` | Second demo app: a fictional car cabin and flat served as two loopback MCP servers (`127.0.0.1:8790/cabin/mcp`, `/home/mcp`) with live state, manual controls and resources for plans. Depends on no module here. |

## The first-party plugins (`plugins/`)

The app server's own plugins, each a Gradle module of its own (project `:<module>`, directory `plugins/<module>`), in
the runtime's packages (`cc.noharry.agent.appserver.*`), with its tests and its public API in `api/<module>.api`
(`apiCheck`, `apiDump`; the root build file sets them up, with the runtime's compile settings). Bothy's server lists
all of them.

| Module | Plugin | What it brings |
|---|---|---|
| `agent-app-cards` | `CardsPlugin` | Cards and options the model shows (`show_card`, the offer tools) |
| `agent-app-pages` | `PagesPlugin` | Pages the model draws in the conversation (`show_html`) |
| `agent-app-memory` | `MemoryPlugin` | What the model remembers about the person (`user_memory`) |
| `agent-app-images` | `ImagesPlugin` | Pictures drawn with an image model (`image_generate`) |
| `agent-app-plans` | `PlansPlugin` | Timed and watched plans, their alarm (`PlanAlarmReceiver`) and watches |
| `agent-app-a2a` | `A2aPlugin` | The person's own agents over A2A, through the runtime's `agent-a2a` (its direct client, and its official one on the official A2A Java SDK, which this module adds) |

They stand on their own, so `plugins/` can become a repository of its own as it is: they depend on the runtime's
artifacts (`cc.noharry:agent-app-server` and the rest at `agentRuntimeVersion`) and on each other only
(`checkPluginsStandAlone`), name nothing of Bothy (`harness.yaml`'s isolation rules, `scripts/lint_deps.py`), and no
plugin names another's package. What they still take from the runtime's `agent-app-protocol` is listed in the
runtime's `docs/design/app-server-split.md` (section 8).

Operating the screen and the device's tools are not among them either: their plugins (`android-use`, `device-tools`)
are in the runtime's `agent-android-use` and `agent-device-capabilities` themselves (packages
`cc.noharry.agent.androiduse.appserver`, `cc.noharry.agent.device.appserver`), which `bothy-server` depends on, and the
server loads them from those modules' manifests, unlisted, under the same ids, so the switches kept on the devices
read as before. Bothy names their public parts only for its checks and skills (`loadAndroidUseEnabled`,
`repairAndroidUse`, `androidUseRoutes`, `ANDROID_USE_TOOL_SERVER`, `DEVICE_*_TOOL_SERVER`).

The model services are not among them: DeepSeek's extras (search and fetch, native history updates, balance, the
relay check, one key for its profiles) and the other common services are the runtime's published `agent-app-models`,
which `bothy-server` depends on and whose plugin (`models`) the server loads from that module's manifest, unlisted.
Their requests are the app server's own (`services/list`, `services/balance`, `services/check`, `services/checked`):
adding a model service starts with choosing one of `services/list` (or Custom) in the settings, tablet and phone, and in
the first launch's setup alike (`bothy-app`'s `model/ServicePicker`), and a profile's balance and relay check show as the
service's `balance` and `relayCheck` say, never by the app matching DeepSeek's address. Bothy's own presets (the profiles file's DeepSeek entries, the
on-device model) stay in `bothy-server`.

## Plugin packages

Plugin packages (`.agentplugin`, no compiled code, downloaded by a person) are the app server's: their format, checks
and running are in the runtime's repository (`docs/design/plugin-packages.md`, `agent-app-server`'s `packages/`), and
its `tools/agent-plugin` makes and signs them. Bothy trusts the publisher keys given as
`-PbothyTrustedPluginPublishers=<hex>` (`AppServerConfig.trustedPublishers`) and takes unsigned packages in debug
builds only. From a PC, over the protocol (`tools/bothy-protocol`, after `adb forward tcp:8791 localabstract:bothy` on a
debug build), with a package built by the runtime's `tools/agent-plugin`:

```bash
node tools/bothy-protocol/bothy.mjs call plugins/install "{\"data\":\"$(base64 < com.example.scripted-1.0.0.agentplugin | tr -d '\n')\"}"
node tools/bothy-protocol/bothy.mjs call plugins/confirm '{"installId":"<from install>","secrets":{"apiKey":"<key>"},"acknowledgeUntrusted":true}'
node tools/bothy-protocol/bothy.mjs call plugins/list '{}'
node tools/bothy-protocol/bothy.mjs say "Call current_weather for latitude 52.52 and longitude 13.41." --approve
```

The runtime's scripted example (`tools/agent-plugin/example-scripts`) has three tools: `current_weather` (Open-Meteo,
no key), `echo_request` (an echo service, with the package's secret placed by the host) and `notes_digest`, which calls
`mcp__notes__list_notes`, a read-only tool of an MCP server configured as `notes`; without one it reports that the run
has no such tool.

`tools/` holds host-side helpers: `agent-relay` (the Node relay that pairs the demo with a user's own cloud agent),
`bothy-protocol` (a minimal Node client of the server's protocol, over `adb forward`) and `voice` (voice asset fetch and
wake-answer synthesis). The runtime's own tools (event-log reports, probe analyzer, A2A
interop runners) live in the runtime's repository.

## The runtime

- `bothy-server`, `bothy-protocol` and the plugins depend on `cc.noharry:agent-*` at `agentRuntimeVersion`
  (`gradle.properties`), aligned by `agent-bom`, which has the app server's two artifacts too.
- With the runtime's repository checked out beside this one (`../../../../android-agent-runtime`), `settings.gradle.kts`
  includes it as a composite build, so the same coordinates build from source; `-PagentRuntimeDir=<path>` points
  elsewhere and `-PagentRuntimeDir=` uses the published version (0.5.0, on Maven Central: what a release
  build and CI use). `-PagentRuntimeRepo=<dir>` takes `cc.noharry` from that local Maven repository only (what the runtime's
  `publishToMavenLocal -Dmaven.repo.local=<dir>` wrote), with `-PagentRuntimeDir= -PagentRuntimeVersion=<version>` for a
  version not on Maven Central.
- Changes to the runtime are made, tested and committed in its repository, never here.

## Rules

- Keep business code under this directory; do not place Android source directly in the repository root.
- Front ends talk to the server only through the protocol: a new settings page adds its view type and `XxxRequests` to
  `bothy-protocol`, a `RouteModule` (in `coreModules` when every app server answers it, else in a feature's `routes`),
  and reads and writes through `LocalServer`. `agent-app-server` never imports `bothy-server`: what it needs from the
  host comes through `AppServerConfig`.
  Text the server says itself uses the server's strings; labels the screens map from an enum use the app's.
- The app server's public API has three levels (a host's; `@AppServerPluginApi` for plugin modules, which
  `bothy-server` opts in to by compiler option; `@InternalAppServerApi` for other modules' tests, which `bothy-server`
  opts in to for its test compiles only, never in main code). Bothy's tests put the server into a state through its one
  test entry point, `AppServerTesting`, and run Bothy's plugins on the kernel's `PluginHost`; they check only what is
  Bothy's. A test of the server's or a first-party plugin's own logic lives in the runtime's repository.
- Every protocol method has one line in `AppServerMethods` (the app server's, in the runtime's repository) or `ProtocolMethods` (Bothy's); after changing the protocol's types or methods, run
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
./gradlew :bothy-protocol:test :bothy-server:testDebugUnitTest :bothy-app:testDebugUnitTest :bothy-voice:testDebugUnitTest :bothy-llm:testDebugUnitTest :bothy-playground:testDebugUnitTest
./gradlew :bothy-app:assembleDebug :bothy-app:assembleRelease :bothy-playground:assembleDebug
node --test tools/agent-relay/relay.test.cjs
node --test tools/bothy-protocol/bothy.test.mjs
```

## Pitfalls

- A Robolectric test that runs the whole server through the protocol runs on the main thread and must turn the main
  looper while it waits, with `idleFor`; `AppServer` and `RuntimeSession` are process singletons, so such a test is the
  only one starting the server in its sandbox. The runtime's repository says more (its `AGENTS.md`).
- A regex runs on Android's own engine on a device and on the JVM's in unit tests (Robolectric too). Android refuses
  a bare closing brace (`}`) the JVM accepts, and the failure is a crash when the pattern is first built: escape it.
