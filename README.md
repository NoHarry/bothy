# Bothy

[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Android Agent Runtime](https://img.shields.io/maven-central/v/cc.noharry/agent-runtime?label=runtime)](https://github.com/NoHarry/android-agent-runtime)
[![Platform](https://img.shields.io/badge/platform-Android%20tablet%20%26%20phone-3DDC84?logo=android&logoColor=white)](#requirements)

<p align="center"><img src="docs/images/bothy-hero.jpg" alt="Bothy's mascot Xiao Bo by a mountain bothy at dusk" width="880"></p>

**English** | [中文](README.zh-CN.md)

**Bothy turns an Android tablet into a personal assistant you talk to, and, with Bothy Playground, into a car's head
unit.** It is the demo of [Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), an embeddable agent
runtime for Android: everything Xiao Bo (小博), the assistant, does goes through the runtime's public API.

▶ **[Watch the 12-minute demo on Bilibili](https://www.bilibili.com/video/BV1g1nf6uEE8)** (in Chinese, with chapters)

| Install | The tablet becomes |
|---------|--------------------|
| **Bothy** | A personal assistant. Say 「你好小博」 or "OK Bothy" over any app: it looks things up on the web, plans and reminds, plays and pauses music and video, works other apps for you (Android Use) and passes any request you like to your own cloud agent, such as Grok Bot. |
| **+ Bothy Playground** | A head unit that cannot control a car: a car's screen with AMap navigation on the device's own location (real mode), or a simulated car and home to control (simulated mode). In a car mount it plans the pickup, the stops and the reminder on arrival. |
| **+ your car's controls as MCP** | A real head unit. Playground's virtual car is just an MCP server (`set_climate`, `set_seat`, `set_window`, `set_trunk`, …); serve your car's own control interface the same way, add it in 连接应用 (Connect apps) by address, and Xiao Bo controls the real car as it controls the virtual one. |

**Head unit not smart enough?** Mount a tablet with Bothy (and Playground in real mode) and the cabin gets an assistant
that plans the trip, reminds you on arrival, plays your music and runs your errands, whatever the car came with.
**Want more?** Bothy is MIT-licensed and made to be extended: connect any MCP server, add your own skills, or fork it and
build the features you want on the same runtime.

**Why it is worth a look**

- **It plans like a person.** "A friend flies in on CZ3956, pick her up, then a late-night diner": it looks the flight up,
  finds a place still open when she lands, routes up to 16 stops and reworks them as plans change.
- **It acts on its own.** Plans watch the car, the home or any server, and speak up on arrival, at a time, or when a
  reading crosses a line.
- **It uses your apps.** Android Use reads the screen and taps, swipes and types in any app; music and video obey through
  their media sessions.
- **It brings your own agent.** Name Grok Bot (or say 云端助手, cloud assistant) and Xiao Bo passes your request, whatever it
  is, to your own agent over A2A; the result comes back by voice.
- **It draws when words are not enough**: cards, choices and control panels on screen (A2UI).
- **It listens properly.** On-device wake word in Chinese or English, it knows which seat is speaking, you can talk over
  it, and an end-to-end speech model is one switch away.
- **Every step is on the record.** A Codex-style console replays each run, tool call by tool call.

A bothy is a small mountain shelter, open to any traveller; Xiao Bo lives in one.

> **Status: 0.x, under active development.**

## Try it in 5 minutes

No development setup, one model key. Both apps speak and show Mandarin Chinese: on-screen labels below are quoted as
they appear, with a translation.

1. **Install**: download the latest `bothy-*.apk` (Xiao Bo) and `bothy-playground-*.apk` (the virtual head unit,
   optional) from [Releases](https://github.com/NoHarry/bothy/releases) onto an Android tablet or phone (Android 7.0 or
   later, arm64).
2. **Add a DeepSeek key**: open Bothy; its first screen walks you through setup. Create an API key on the
   [DeepSeek platform](https://platform.deepseek.com/api_keys), paste it and tap 保存并检查 (Save and check).
3. **(Recommended) Add a Doubao speech key**: in the [Volcano Engine Doubao speech
   console](https://console.volcengine.com/speech/new/setting/activate?projectName=default), under 开通管理 (Activation)
   enable 语音合成2.0 (Speech Synthesis 2.0) and 端到端实时语音 (End-to-end Realtime Speech), then create a key under API Key in
   the left menu and paste it. Xiao Bo then speaks in a natural voice and can hold an end-to-end voice conversation.
   Without it, Xiao Bo speaks with the device's own voice.
4. **Grant permissions** from the same screen: microphone and overlay are needed; notification access (to control
   music) and accessibility (to let Xiao Bo operate other apps) are optional.
5. **Say 「你好小博」** (*nǐ hǎo xiǎo bó*, "hello, Xiao Bo") **or "OK Bothy"** and try 「明天深圳天气怎么样」 (tomorrow's weather in
   Shenzhen), 「明早八点提醒我带伞」 (remind me at eight tomorrow to take an umbrella) or 「下一首」 (next song). With Bothy Playground
   in front, ask it to turn on the air conditioning, open a window or plan a route in the virtual car.

### Which key does what

| Feature | Needs |
|---------|-------|
| Conversation and tasks, web search, reminders and plans, memory, operating other apps, media control, calendar, cards and panels | DeepSeek key |
| Wake word, speech recognition | Nothing (on the device) |
| Xiao Bo's voice | Nothing (the system voice); a Doubao speech key for a natural voice |
| End-to-end voice conversation | Doubao speech key |
| Playground's virtual car and home, the weather | Nothing (without an AMap key the weather comes from Open-Meteo) |
| Playground's map, navigation and place search | Your own AMap key (below) |

### AMap key (optional, for the map and navigation)

The Playground's map and navigation run on AMap's SDK, with a free key of your own:

1. Sign in to the [AMap console](https://console.amap.com/dev/key/app) (a personal developer account is enough), create
   an app under 我的应用 (My Apps) and add a key for the **Android** platform.
2. Enter the package `cc.noharry.bothy.playground` and, as both the release and the debug SHA1,
   `02:09:D5:0D:3B:C7:52:02:A2:1A:2E:E7:EB:01:5B:21:0D:74:13:5B` (the signature of the APKs in Releases). A build of
   your own is signed differently: the Playground's settings → 高德 key (AMap key) show its package and SHA1, ready to
   copy.
3. Paste the key into the Playground's settings → 高德 key; it restarts and the map appears.

A build of your own can also carry the key as `AMAP_ANDROID_KEY` in `local.properties` (see [Running the demo](#running-the-demo)).

## The apps

| Module | What it does |
|--------|--------------|
| `bothy-app` | The demo app (tablet first): debug console, settings, plans, voice, floating assistant card |
| `bothy-playground` | A second demo app: a fictional car cabin and home behind a local MCP server, for Bothy to drive ([below](#bothy-playground)) |

The sample depends on the runtime's artifacts (`cc.noharry:agent-*`); the playground depends on nothing: Bothy reaches
it like any other MCP server.

## What the demo shows

<p align="center"><a href="https://www.bilibili.com/video/BV1g1nf6uEE8"><img src="docs/images/demo-video-cover.jpg" alt="The full demo on Bilibili: Bothy's head unit on a tablet in a car at night, a route planned on screen" width="880"></a></p>

The whole demo, 12 minutes with sound and chapters, is [on Bilibili](https://www.bilibili.com/video/BV1g1nf6uEE8). Every clip below is the demo on a tablet,
asked out loud; some are sped up, as each caption says.

### Talk to Xiao Bo in the car

<p align="center"><img src="docs/images/demo-car-voice.gif" alt="Saying 「你好小博，我有点冷」 (hello Xiao Bo, I'm a bit cold) in the head unit: Xiao Bo's card shows the tools it calls and the air conditioning goes from 24 to 28 °C" width="880"></p>

Say 「你好小博」 and ask. While it listens, a slim bar shows the words it hears; once it takes the request, its card shows
what you asked, each tool it calls and the answer. It knows who is speaking: the seat marked 说话中 (speaking) is yours, so
「我这边好热」 (it's hot on my side) lands on it. Here 「我有点冷」 (I'm a bit cold) turns the air conditioning up from 24 to 28 °C.
*(Real time.)*

### Plan a trip and drive it

<p align="center"><img src="docs/images/demo-navigation.gif" alt="「先去深圳北站，再去世界之窗」 (Shenzhen North Station first, then Window of the World): the route through both stops is shown whole, then a simulated drive starts" width="880"></p>

「先去深圳北站，再去世界之窗」 (Shenzhen North Station first, then Window of the World): Xiao Bo finds both places by name, plans a
route through them with AMap's navigation SDK, shows it whole for a few seconds, then starts a simulated drive with
turn-by-turn guidance. Stops can be added, removed, replaced or reordered while driving (up to 16), and a vague name
such as 「科技园」 (the science park) is looked for around the trip's other stops. In the demo the car starts at Shenzhen
Civic Center, not where the device is. *(2× speed.)*

### Real navigation, where the device is

<p align="center"><img src="docs/images/demo-real-navigation.gif" alt="On the real head unit, 「我有个朋友坐南方航空 CZ3956 来深圳…」 (a friend flies in on China Southern CZ3956): Xiao Bo looks the flight up, plans the airport and a late-night diner, then adds the hotel on the way" width="880"></p>

The 模拟｜真实 (simulated | real) switch turns the head unit into a real one: AMap's navigation screen on the device's
location, with only real navigation and Xiao Bo. 「我有个朋友坐南方航空 CZ3956 来深圳，你先去机场接她，然后带她去市区找个深夜食堂吃点东西」
(a friend flies in on China Southern CZ3956; pick her up, then find a late-night diner in town): Xiao Bo looks the flight
up on the web (it lands at 01:10), plans the airport and a diner, and points out that the first one closes at 23:30,
offering ones open until 2; 「接到她先去福田香格里拉酒店放行李，再去吃宵夜」 (drop her bags at the hotel first) adds the hotel on
the way and takes the late diner. 「用高德导航去…」 (navigate with AMap to…) hands a route to the 高德地图 App instead. For
the recording the tablet's location was set to Shenzhen Civic Center. *(2× speed, cut; web search on.)*

### Pass anything to your own agent

<p align="center"><img src="docs/images/demo-grok-bot.gif" alt="「让 Grok Bot 帮我查一下最近 AI 圈有什么新闻」 (have Grok Bot look up the latest AI news): Xiao Bo hands it to the paired Grok Bot, then reads the report out when it comes back and lists the five items on a card" width="880"></p>

Pair your own cloud agent, such as Grok Bot, in 连接应用 (Connect apps) through the self-hosted relay in
`workspace/apps/android/tools/agent-relay`. Name it (「让 Grok Bot…」, or 云端助手 for your cloud assistant) and Xiao Bo passes
the request to it, whatever you ask: research, mail, reminders, shopping, anything your agent can do with your
accounts. The task goes over A2A, the conversation carries on,
and when the agent reports back Xiao Bo reads it out; 「Grok 那边怎么样了」 (how is Grok doing) asks for progress. Unpaired,
Xiao Bo can still open the agent's own app on the screen and ask it there. *(1.5× speed; the wait for the report cut.)*

### Cards and panels it draws

<p align="center"><img src="docs/images/demo-a2ui-panel.gif" alt="「空调给我个面板，我自己调」 (give me a climate panel, I'll set it myself): a climate panel appears in Xiao Bo's card; dragging to 22 °C and confirming sets the car to cooling at 22 °C" width="880"></p>

When an answer needs a pick or a setting, Xiao Bo sends UI instead of words (A2UI): information cards, choices and
control panels the head unit draws itself. Drag, tap, confirm, and the choice goes back to Xiao Bo, which carries it
out; here 22 °C and cooling. *(1.6× speed.)*

### A home too

<p align="center"><img src="docs/images/demo-home.gif" alt="「我回家了，客厅开灯，空调开到二十五度」 (I'm home: living room lights on, air conditioning to 25 degrees): the living room lights up, its air conditioning comes on and the room starts to cool" width="880"></p>

The playground also has a small flat: lights, curtains and air conditioning in four rooms, a motion sensor per room, the
front door lock, a robot vacuum, and scenes (home, away, sleep, movie, morning). Readings move, so the room above cools
toward 25 °C and a plan like 「客厅超过 28 度就开空调」 (turn the air conditioning on when the living room passes 28 °C) fires
within minutes. Away from the head unit, Xiao Bo answers in Bothy's floating card. *(1.5× speed.)*

### It can use other apps

<p align="center"><img src="docs/images/demo-android-use.gif" alt="「帮我用计算器算一下 23 乘以 47」 (work out 23 times 47 on the calculator): Xiao Bo's avatar moves to each key it taps on the calculator and the answer is 1,081" width="880"></p>

With Android Use on (an accessibility service you switch on), Xiao Bo reads the screen and taps, swipes and types in
other apps. Its avatar shows where it is about to tap, and the screen's edge glows while it is in control; here it works
out 23 × 47 on the calculator. *(1.3× speed.)*

### Every step on the record

<p align="center"><img src="docs/images/demo-console.gif" alt="Bothy's console: a request runs, its tool calls unfold with their durations, then the Trajectory tab replays the run event by event" width="880"></p>

Bothy's own screen is a developer console in the spirit of Codex. The conversation shows each tool call with its
duration and what it said along the way; the board tracks the run, token usage and cache hits, the workspace, the MCP
servers, memory and skills. The Trajectory tab replays a run from its durable event log: a timeline of model and tool
spans, then every event in order, from the request through each model call and token count to every tool call as the
model asks for it, the backend runs it and it completes, with calls that ran side by side. Here Xiao Bo finds the
passenger seat heating already off, sets the ambient light warm and checks the doors and the trunk. *(1.15× speed.)*

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
- **Voice**: on-device wake word (「你好小博」, "hello, Xiao Bo", or "OK Bothy") and recognition, turn taking (an optional judge decides when
  you have finished and whether you are talking to Xiao Bo), barge-in, TTS; optionally an end-to-end speech model.
- **Your own cloud agent**: pair a personal agent (such as Grok Bot) through the self-hosted relay in
  `workspace/apps/android/tools/agent-relay`, then hand it tasks that need your accounts.
- **Offline**: the demo also runs with a simulated model and the example tools in
  `bothy-app/src/main/assets/mcp/tools.json`.

## Bothy Playground

<p align="center"><img src="docs/images/bothy-playground.jpg" alt="Bothy Playground's head unit: the car, Xiao Bo's card with the tools it called and its answer, and the route it is driving" width="880"></p>

`bothy-playground` is a separate app that simulates a car cabin and a small flat and serves them as two MCP servers on
the device itself (`http://127.0.0.1:8790/cabin/mcp` and `/home/mcp`, Streamable HTTP, loopback only). Nothing in it
reaches a real car or a real device.

- **Cabin**: climate, seat heating and ventilation, windows, doors and the trunk, door locks, screen brightness and
  volume, ambient light, music; who sits where and who is speaking, so 「我这边好热」 (it's hot on my side) lands on the
  speaker's seat.
- **Navigation**: real routing through AMap's navigation SDK with your AMap key
  ([above](#amap-key-optional-for-the-map-and-navigation)): places found by name (a vague one such as 「科技园」, the science
  park, is looked for around the trip's other stops), routes through up to 16 stops, stops added, removed, replaced or
  reordered while driving, search along the route, and a simulated drive. Settings can park the car at Shenzhen Civic
  Center instead of the device's location, for a demo in the demo's city.
- **A head unit**: the cabin is laid out like a car's screen (the car on the left, then three columns, the climate bar
  below). While it is in front it takes the assistant over: Bothy hides its floating card, and Xiao Bo lives in the head
  unit instead. It wakes there (the wake word, or a tap on Xiao Bo); while it listens only a slim bar shows the words
  being heard; once it takes the request its card opens with what was asked, what it thinks (in Chinese), each tool it
  calls and what it says as it works, then the answer; a plan coming due shows there too; the X ends voice. The map
  moves to each place it finds, shows the planned route whole, and only then starts driving.
- **Home**: lights, curtains and air conditioning in four rooms, a motion sensor per room, the front door lock, a robot
  vacuum, and scenes (home, away, sleep, movie, morning).
- **Readings that move**: temperatures drift toward the air conditioning or the weather, a simulated drive drains the
  battery and counts down the route, so plans such as 「车里降到 24 度以下就把车窗都关上」 (close every window once the car is below 24
  °C) fire within minutes. Both servers publish their state as a resource (`playground://cabin/state`,
  `playground://home/state`) that Bothy's plans watch.
- **Everything by hand too**: tap a seat to seat a passenger, long-press it to make them the speaker, tap a room for its
  light; the side panel has every control and reading. Manual changes show as 手动 (manual) in the log and trigger plans
  like real ones.

To demo it: install both apps, open Bothy Playground, then in Bothy open 连接应用 (Connect apps) and connect 虚拟座舱 (Virtual
cabin) and 虚拟小家 (Virtual home), no key needed. Keep the playground in front and talk to Xiao Bo there. Desktop MCP
clients can reach the same servers with `adb forward tcp:8790 tcp:8790`.

The 模拟｜真实 (simulated | real) switch at the top right of the head unit changes mode at any time: simulated is the made-up
car and home above; real keeps only real navigation and Xiao Bo, on the device's own location, so a tablet in a car
works. Each switch starts a new conversation, so nothing said in the other mode carries over.

Navigation needs your own AMap Android key, typed into the Playground's settings → 高德 key (how to get one is
[above](#amap-key-optional-for-the-map-and-navigation)). Without it the map's place shows the way to one, the navigation
tools say what is missing, and everything else works. The head unit takes the assistant over through a signature permission, so it
works only when both apps are signed with the same key (see [Running the demo](#running-the-demo) for signing).

## Running the demo

The apps use the runtime published on Maven Central (`agentRuntimeVersion` in `gradle.properties`). To build against
its source instead, clone it beside this repository: Gradle then builds it in place of the published artifacts
(`settings.gradle.kts`; `-PagentRuntimeDir=` goes back to the published version).

```bash
git clone https://github.com/NoHarry/bothy.git
cd bothy/workspace/apps/android
./gradlew :bothy-app:assembleDebug
./gradlew :bothy-playground:assembleDebug   # optional: the car and home to drive
```

Model provider presets come from `bothy-app/src/main/assets/model-provider-profiles.json`, which is packaged into
the APK and therefore holds **no API keys**. Put keys in the Git-ignored `workspace/apps/android/local.properties`:

```properties
# local.properties (never committed)
AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY=your-key
```

The key name is `AGENT_RUNTIME_PROVIDER_PROFILE_{PROFILE_ID}_API_KEY`, the profile id upper-cased with `-` as `_`. Keys
from `local.properties` are compiled into the debug build for convenience; build an APK you hand to someone else with
`-PbothyEmbedLocalKeys=none` (or `tts`), or enter keys in the app instead.

Both apps are signed with the machine's own debug key unless `local.properties` names a signing key of your own, which
then signs debug and release builds alike, so a build from any machine installs over the last one:

```properties
BOTHY_SIGNING_STORE_FILE=/path/outside/the/repo/bothy-release.jks
BOTHY_SIGNING_STORE_PASSWORD=…
BOTHY_SIGNING_KEY_ALIAS=bothy
BOTHY_SIGNING_KEY_PASSWORD=…
```

Keep the key file and its passwords backed up: without them a new build no longer installs over the old one.

The on-device voice models are committed (with the sherpa-onnx native libraries in `bothy-voice`), so
voice works out of the box on arm64 devices; `tools/voice/fetch_voice_assets.py` re-downloads and verifies them.

## Requirements

- Demo app: `minSdk 24`, `compileSdk 37` (core library desugaring with `desugar_jdk_libs_nio`, since the runtime uses
  `java.nio.file`)
- JDK 17, Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.4
- Tablets first; phones work held sideways. On a phone (smallest width under 600 dp) both apps turn landscape: Bothy's
  console takes its compact layout with the destinations in a rail, the head unit draws at three quarters of its size,
  and Xiao Bo's floating card fits the upright apps it shows over. A phone emulator (arm64 image on Apple silicon) runs
  both apps; AMap's map needs software rendering there (start it with `-gpu swiftshader_indirect`), or it crashes when
  the map opens.

## Friends

- [LINUX DO](https://linux.do): a Chinese tech community

## License

[MIT](LICENSE). Third-party components and committed model files keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
