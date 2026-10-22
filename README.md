# Bothy

[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Android Agent Runtime](https://img.shields.io/badge/runtime-0.1.0--SNAPSHOT-blue)](https://github.com/NoHarry/android-agent-runtime)
[![Platform](https://img.shields.io/badge/platform-Android%20tablet-3DDC84?logo=android&logoColor=white)](#requirements)

<p align="center"><img src="docs/images/bothy-hero.jpg" alt="Bothy's mascot 小博 (Xiao Bo) by a mountain bothy at dusk" width="880"></p>

**English** | [中文](README.zh-CN.md)

**Bothy is a tablet-first demo of [Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), an
embeddable agent runtime for Android.**

A bothy is a small mountain shelter, open to any traveller. 小博 (Xiao Bo) lives in one: a floating assistant you talk
to, that drives other apps, public MCP servers, plans that watch the world, and a simulated car and flat. Everything the
agent does, it does through the runtime's public API; nothing here is a library.

> **Status: 0.x, under active development.**

## The apps

| Module | What it does |
|--------|--------------|
| `bothy-sample` | The demo app (tablet first): debug console, settings, plans, voice, floating assistant card |
| `bothy-playground` | A second demo app: a fictional car cabin and home behind a local MCP server, for Bothy to drive ([below](#bothy-playground)) |

The sample depends on the runtime's artifacts (`cc.noharry:agent-*`); the playground depends on nothing: Bothy reaches
it like any other MCP server.

## What the demo shows

Every clip is the demo on a tablet, asked out loud; some are sped up, as each caption says.

### Talk to 小博 in the car

<p align="center"><img src="docs/images/demo-car-voice.gif" alt="Saying 「你好小博，我有点冷」 in the head unit: 小博's card lists the tools it calls and the air conditioning goes from 24 to 26 °C" width="880"></p>

Say 「你好小博」 and ask. While it listens, a slim bar shows the words it hears; once it takes the request, its card
shows what you asked, each tool it calls and the answer. It knows who is speaking: the seat marked 说话中 is yours, so
「我这边好热」 lands on it. Here 「我有点冷」 turns the air conditioning up from 24 to 26 °C. *(Real time.)*

### Plan a trip and drive it

<p align="center"><img src="docs/images/demo-navigation.gif" alt="「先去深圳北站，再去世界之窗」: the route through both stops is shown whole, then a simulated drive starts" width="880"></p>

「先去深圳北站，再去世界之窗」: 小博 finds both places by name, plans a route through them with AMap's navigation SDK,
shows it whole for a few seconds, then starts a simulated drive with turn-by-turn guidance. Stops can be added,
removed, replaced or reordered while driving (up to 16), and a vague name such as 「科技园」 is looked for around the
trip's other stops. In the demo the car starts at 深圳市民中心, not where the device is. *(2× speed.)*

### Cards and panels it draws

<p align="center"><img src="docs/images/demo-a2ui-panel.gif" alt="「空调给我个面板，我自己调」: a climate panel appears in 小博's card; dragging to 22 °C and confirming sets the car to cooling at 22 °C" width="880"></p>

When an answer needs a pick or a setting, 小博 sends UI instead of words (A2UI): information cards, choices and control
panels the head unit draws itself. Drag, tap, confirm, and the choice goes back to 小博, which carries it out; here 22 °C
and cooling. *(1.6× speed.)*

### A home too

<p align="center"><img src="docs/images/demo-home.gif" alt="「我回家了，客厅开灯，空调开到二十五度」: the living room lights up, its air conditioning comes on and the room starts to cool" width="880"></p>

The playground also has a small flat: lights, curtains and air conditioning in four rooms, a motion sensor per room,
the front door lock, a robot vacuum, and scenes (回家, 离家, 睡觉, 观影, 起床). Readings move, so the room above cools
toward 25 °C and a plan like 「客厅超过 28 度就开空调」 fires within minutes. Away from the head unit, 小博 answers in
Bothy's floating card. *(1.5× speed.)*

### It can use other apps

<p align="center"><img src="docs/images/demo-android-use.gif" alt="「帮我用计算器算一下 23 乘以 47」: 小博's avatar moves to each key it taps on the calculator and the answer is 1,081" width="880"></p>

With Android Use on (an accessibility service you switch on), 小博 reads the screen and taps, swipes and types in other
apps. Its avatar shows where it is about to tap, and the screen's edge glows while it is in control; here it works out
23 × 47 on the calculator. *(1.3× speed.)*

### Every step on the record

<p align="center"><img src="docs/images/demo-console.gif" alt="Bothy's console: a request runs, its five tool calls unfold with their durations, then the Trajectory tab replays the run event by event" width="880"></p>

Bothy's own screen is a developer console in the spirit of Codex. The conversation shows each tool call with its
duration and what it said along the way; the board tracks the run, token usage and cache hits, the workspace, the MCP
servers, memory and skills. The Trajectory tab replays a run from its durable event log: a timeline of model and tool
spans, then every event in order, from the request through each model call and token count to every tool call as the
model asks for it, the backend runs it and it completes, with calls that ran side by side. Here 小博 turns the
passenger seat heating off, sets the ambient light warm and checks the doors and the trunk. *(1.15× speed; past sessions
hidden.)*

### And more

- **Bring your own model**: any OpenAI- or Anthropic-compatible endpoint, configured in the app; streaming, thinking,
  server-side web search and prompt caching where the provider offers them.
- **MCP from the public internet**: 连接应用 (Connect apps) lists ready-made remote MCP servers. Open ones connect with one
  tap and no key: Kiwi.com flight search, DeepWiki (questions about GitHub repositories), Hugging Face Hub, Context7
  (library docs). Keyed ones take your own key: AMap, DiDi, McDonald's China, Luckin Coffee, Tencent Docs. Any other
  Streamable HTTP MCP server can be added by address.
- **Skills and subagents**: a multi-day trip planner fans out to transport, stay and day-plan subagents and returns one
  itinerary card; the main agent decides when to delegate.
- **Plans**: reminders and timed tasks, and watches on a server's state, either an MCP resource or a read-only tool
  polled on a schedule, e.g. "remind me when the cheapest Shenzhen–Chengdu flight on 7 Nov drops below 800 yuan" runs on
  Kiwi.com's open search.
- **Voice**: on-device wake word (「你好，小博」) and recognition, turn taking (an optional judge decides when you have
  finished and whether you are talking to 小博), barge-in, TTS; optionally an end-to-end speech model.
- **Your own cloud agent**: pair a personal agent (such as Grok Bot) through the self-hosted relay in
  `workspace/apps/android/tools/agent-relay`, then hand it tasks that need your accounts.
- **Offline**: the demo also runs with a simulated model and the example tools in
  `bothy-sample/src/main/assets/mcp/tools.json`.

## Bothy Playground

<p align="center"><img src="docs/images/bothy-playground.jpg" alt="Bothy Playground's head unit: the car, 小博's card with the tools it called and its answer, and the route it is driving" width="880"></p>

`bothy-playground` is a separate app that simulates a car cabin and a small flat and serves them as two MCP servers on
the device itself (`http://127.0.0.1:8790/cabin/mcp` and `/home/mcp`, Streamable HTTP, loopback only). Nothing in it
reaches a real car or a real device.

- **Cabin**: climate, seat heating and ventilation, windows, doors and the trunk, door locks, screen brightness and
  volume, ambient light, music; who sits where and who is speaking, so 「我这边好热」 lands on the speaker's seat.
- **Navigation**: real routing through AMap's navigation SDK when the app is built with an AMap key: places found by
  name (a vague one such as 「科技园」 is looked for around the trip's other stops), routes through up to 16 stops,
  stops added, removed, replaced or reordered while driving, search along the route, and a simulated drive. Settings can
  park the car at 深圳市民中心 instead of the device's location, for a demo in the demo's city.
- **A head unit**: the cabin is laid out like a car's screen (the car on the left, then three columns, the climate bar
  below). While it is in front it takes the assistant over: Bothy hides its floating card, and 小博 lives in the head
  unit instead. It wakes there (the wake word, or a tap on 小博); while it listens only a slim bar shows the words being
  heard; once it takes the request its card opens with what was asked, what it thinks (in Chinese), each tool it calls
  and what it says as it works, then the answer; a plan coming due shows there too; the X ends voice. The map moves to each place it finds, shows the planned route whole, and only
  then starts driving.
- **Home**: lights, curtains and air conditioning in four rooms, a motion sensor per room, the front door lock, a robot
  vacuum, and scenes (回家, 离家, 睡觉, 观影, 起床).
- **Readings that move**: temperatures drift toward the air conditioning or the weather, a simulated drive drains the
  battery and counts down the route, so plans such as 「车里降到 24 度以下就把车窗都关上」 or 「客厅超过 28 度就开空调」 fire
  within minutes. Both servers publish their state as a resource (`playground://cabin/state`,
  `playground://home/state`) that Bothy's plans watch.
- **Everything by hand too**: tap a seat to seat a passenger, long-press it to make them the speaker, tap a room for its
  light; the side panel has every control and reading. Manual changes show as 手动 in the log and trigger plans like
  real ones.

To demo it: install both apps, open Bothy Playground, then in Bothy open 连接应用 and connect 虚拟座舱 and 虚拟小家 (no
key). Keep the playground in front and talk to 小博 there. Desktop MCP clients can reach the same servers with
`adb forward tcp:8790 tcp:8790`.

Navigation needs your own AMap Android key, bound to the package `cc.noharry.bothy.playground` and the SHA1 of the key
you sign with: put `AMAP_ANDROID_KEY=…` in `workspace/apps/android/local.properties` (never committed). Without it the
navigation tools say what is missing. The head unit takes the assistant over through a signature permission, so it
works only when both apps are signed with the same key (see [Running the demo](#running-the-demo) for signing).

## Running the demo

Clone the runtime beside this repository; Gradle then builds it from source in place of the published artifacts
(`settings.gradle.kts`; `-PagentRuntimeDir=` uses the published `agentRuntimeVersion` instead):

```bash
git clone https://github.com/NoHarry/android-agent-runtime.git
git clone https://github.com/NoHarry/bothy.git
cd bothy/workspace/apps/android
./gradlew :bothy-sample:assembleDebug
./gradlew :bothy-playground:assembleDebug   # optional: the car and home to drive
```

Model provider presets come from `bothy-sample/src/main/assets/model-provider-profiles.json`, which is packaged into
the APK and therefore holds **no API keys**. Put keys in the Git-ignored `workspace/apps/android/local.properties`:

```properties
# local.properties (never committed)
AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY=your-key
```

The key name is `AGENT_RUNTIME_PROVIDER_PROFILE_{PROFILE_ID}_API_KEY`, the profile id upper-cased with `-` as `_`. Keys
from `local.properties` are compiled into the debug build for convenience; build an APK you hand to someone else with
`-PsampleEmbedLocalKeys=none` (or `tts`), or enter keys in the app instead.

Both apps are signed with the machine's own debug key unless `local.properties` names a signing key of your own, which
then signs debug and release builds alike, so a build from any machine installs over the last one:

```properties
BOTHY_SIGNING_STORE_FILE=/path/outside/the/repo/bothy-release.jks
BOTHY_SIGNING_STORE_PASSWORD=…
BOTHY_SIGNING_KEY_ALIAS=bothy
BOTHY_SIGNING_KEY_PASSWORD=…
```

Keep the key file and its passwords backed up: without them a new build no longer installs over the old one.

The on-device voice models are committed (the sherpa-onnx native libraries come with the runtime's `agent-voice`), so
voice works out of the box on arm64 devices; `tools/voice/fetch_voice_assets.py` re-downloads and verifies them.

## Requirements

- Demo app: `minSdk 24`, `compileSdk 37` (core library desugaring with `desugar_jdk_libs_nio`, since the runtime uses
  `java.nio.file`)
- JDK 17, Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.4

## License

[MIT](LICENSE). Third-party components and committed model files keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
