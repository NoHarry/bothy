# Android Workspace Agent Map

This directory contains the Bothy Gradle workspace: two demo apps built on
[Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), and the host-side tools they use.

## Modules

| Module | Purpose |
|---|---|
| `bothy-app` | Demo host app and debug console (tablet first): model providers, MCP servers and 连接应用, skills, subagents, plans, voice (小博's wake profile and identity live here), A2A and the floating assistant card. |
| `bothy-voice` | Voice sessions: on-device wake word and recognition (sherpa-onnx, natives committed), DashScope cloud recognition, turn taking (Jev), echo and near-speech gates. Moved from the runtime (its `agent-voice` up to 0.3.0); uses none of the runtime. |
| `bothy-playground` | Second demo app: a fictional car cabin and flat served as two loopback MCP servers (`127.0.0.1:8790/cabin/mcp`, `/home/mcp`) with live state, manual controls and resources for plans. Depends on no module here. |

`tools/` holds host-side helpers: `agent-relay` (the Node relay that pairs the demo with a user's own cloud agent) and
`voice` (voice asset fetch and wake-answer synthesis). The runtime's own tools (event-log reports, probe analyzer, A2A
interop runners) live in the runtime's repository.

## The runtime

- The sample depends on `cc.noharry:agent-*` at `agentRuntimeVersion` (`gradle.properties`), aligned by `agent-bom`.
- With the runtime's repository checked out beside this one (`../../../../android-agent-runtime`), `settings.gradle.kts`
  includes it as a composite build, so the same coordinates build from source; `-PagentRuntimeDir=<path>` points
  elsewhere and `-PagentRuntimeDir=` uses the published version.
- Changes to the runtime are made, tested and committed in its repository, never here.

## Rules

- Keep business code under this directory; do not place Android source directly in the repository root.
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
./gradlew :bothy-app:testDebugUnitTest :bothy-voice:testDebugUnitTest :bothy-playground:testDebugUnitTest
./gradlew :bothy-app:assembleDebug :bothy-app:assembleRelease :bothy-playground:assembleDebug
node --test tools/agent-relay/relay.test.cjs
```
