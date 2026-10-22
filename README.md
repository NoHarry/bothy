# Bothy

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

- **A car and a home to drive**: [Bothy Playground](#bothy-playground) puts a fictional cabin and a fictional flat on
  screen; say 「你好小博，我有点冷」 and watch the seat warm up.
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
- **Generative UI**: cards, sliders, switches and choices rendered with A2UI; answers that need a pick become buttons.
- **Voice**: on-device wake word (「你好，小博」) and recognition, turn taking and barge-in, TTS; optionally an
  end-to-end speech model.
- **Android Use**: the agent reads the screen and taps, swipes and types in other apps (you enable the accessibility
  service).
- **Your own cloud agent**: pair a personal agent (such as Grok Bot) through the self-hosted relay in
  `workspace/apps/android/tools/agent-relay`, then hand it tasks that need your accounts.

The demo can also run fully offline with a simulated model and the example tools in
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

- Demo app: `minSdk 24`, `compileSdk 37`
- JDK 17, Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.4

## License

[MIT](LICENSE). Third-party components and committed model files keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
