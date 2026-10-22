# Bothy

<p align="center"><img src="docs/images/bothy-hero.jpg" alt="小博在黄昏的山间小屋旁" width="880"></p>

[English](README.md) | **中文**

**Bothy 是一个可嵌入 Android App 的设备端 AI agent SDK，附带一个展示能力的演示 App。**

Bothy（苏格兰山间小屋）向所有路人开放。Bothy 给你的 Android App 一间自给自足的小屋，让 AI agent 住在里面干活：完整的
agent 循环跑在你的 App 进程里，带沙箱化的虚拟工作区、MCP 工具、skill 和子 agent，对外只暴露一套小巧的 Kotlin API。语音、
屏幕操作（Android Use）、A2A 和设备能力都是按需引入的可选模块。

> **状态：0.x，持续开发中。** 公开 API 尚未冻结，版本之间可能变化。

## 模块

| 模块 | 作用 |
|------|------|
| `agent-kernel-api` | 与 Android 无关的契约：模型、工具、插件、能力、工作区、完成判定、事件日志、生命周期 |
| `agent-kernel-core` | agent 循环本身：调度器、原生工具循环、profile 编译、`KernelRuntimeBuilder` |
| `agent-minimal` | 直接搭在 kernel 上的最小 agent，不含 Android 门面 |
| `agent-runtime` | 运行时和宿主门面（纯 JVM）：`AgentRuntimeBuilder`、模型客户端（OpenAI / Anthropic 兼容）、MCP、skill、子 agent、会话与记忆、联网搜索与读网页、hook 与策略 |
| `agent-runtime-android` | Android 层：SQLite 会话存储、App 私有的持久化事件日志 |
| `agent-vfs` | agent 沙箱工作区的虚拟文件系统；宿主可挂载目录、assets 或内存数据源 |
| `agent-vfs-saf` | 可选：挂载用户通过系统文件选择器授权的文件夹 |
| `agent-exec-js` | 可选：`exec_js`，沙箱里的 JavaScript（Zipline 上的 QuickJS），可以回调 agent 的工具 |
| `agent-device-capabilities` | 可选：日历和通知工具 |
| `agent-android-use` | 可选：agent 通过无障碍服务观察并操作屏幕 |
| `agent-voice` | 可选：语音会话，支持设备端 sherpa-onnx 识别和唤醒词，也可用云端识别 |
| `agent-a2a-client`、`-direct`、`-official` | 可选：把任务委托给远端 A2A agent（两种可互换的传输实现） |
| `agent-a2a-server` | 可选：把 agent 自己暴露成本地 A2A 端点 |
| `bothy-sample` | 演示 App（平板优先）：调试控制台、设置、计划、语音、悬浮助手卡片 |
| `bothy-playground` | 第二个演示 App：用本机 MCP 服务提供一辆虚构的车和一个虚构的家，给 Bothy 操作（见[下文](#bothy-playground)） |

runtime 依赖 kernel 和 `agent-vfs`；可选模块最多依赖 `agent-kernel-api`；没有任何模块依赖 sample；playground 不依赖这里的任何模块，Bothy 像连其他 MCP 服务一样连它。

## 演示 App 能看到什么

- **一辆车和一个家给你操作**：[Bothy Playground](#bothy-playground) 把虚构的座舱和小家画在屏幕上，说一句「你好小博，我有点冷」，
  就能看到座椅热起来。
- **自带模型**：任何 OpenAI 或 Anthropic 兼容的服务，在 App 里配置；服务支持时可用流式、思考、服务端联网搜索和提示缓存。
- **接入公网上的 MCP**：「连接应用」里预置了现成的远程 MCP。公开服务点一下就能连，不需要 key：Kiwi.com 机票搜索、
  DeepWiki（GitHub 仓库问答）、Hugging Face Hub、Context7（开发文档）。需要 key 的填你自己的：高德地图、滴滴出行、
  麦当劳、瑞幸咖啡、腾讯文档。其他 Streamable HTTP 的 MCP 服务也可以按地址添加。
- **skill 和子 agent**：多日旅行规划会分派交通、住宿、每日行程三个子 agent，汇总成一张行程卡片；何时委派由主 agent 决定。
- **计划**：提醒、定时任务，以及监听服务状态（MCP 资源，或按间隔调用的只读工具）。比如「深圳飞成都 11 月 7 日的最低价
  低于 800 元时提醒我」就跑在 Kiwi.com 的公开搜索上。
- **生成式界面**：用 A2UI 渲染卡片、滑块、开关和选项；需要用户选择的回答直接变成按钮。
- **语音**：设备端唤醒词（「你好，小博」）和识别、轮次判断与插话打断、语音播报；也可以切换到端到端语音模型。
- **Android Use**：agent 读屏幕，在别的 App 里点击、滑动和输入（需要你手动开启无障碍服务）。
- **你自己的云端 agent**：通过 `workspace/apps/android/tools/agent-relay` 里自部署的中转，配对你的个人 agent（例如
  Grok Bot），把需要个人账号的事交给它。

演示 App 也可以完全离线运行：使用模拟模型和 `bothy-sample/src/main/assets/mcp/tools.json` 里的示例工具。

## Bothy Playground

<p align="center"><img src="docs/images/bothy-playground.jpg" alt="Bothy Playground 的车机：左边是车，中间是小博的卡片（调用了哪些工具、回答），右边是正在导航的路线" width="880"></p>

`bothy-playground` 是另一个 App，它模拟一辆车的座舱和一个小家，在本机提供两个 MCP 服务
（`http://127.0.0.1:8790/cabin/mcp` 和 `/home/mcp`，Streamable HTTP，只监听本机回环地址）。它不连接任何真车或真实设备。

- **座舱**：空调、座椅加热和通风、车窗、车门和后备箱、车门锁、中控屏亮度和音量、氛围灯、音乐；还有谁坐在哪、谁在说话，所以「我这边好热」会落到说话人的座位上。
- **导航**：打包时配了高德 key，就用高德导航 SDK 真实算路：按名字找地点（「科技园」这种笼统的名字会在这趟行程其他地点附近找），
  最多 16 个途经点，开车途中加、删、换、调整途经点，沿途搜索，以及模拟行驶。设置里可以让车停在深圳市民中心，不用设备的真实位置，方便在演示城市里演示。
- **车机界面**：座舱按车机屏幕布局（左边是车，右边三栏，下面是空调条）。它在前台时接管助手：Bothy 收起悬浮卡片，小博就在车机里。
  在这里唤醒（唤醒词，或者点小博）；听你说话时只显示一条小栏，滚动显示识别到的字；识别完开始干活时才弹出卡片，显示你的要求、
  它的思考过程（中文）、调用的每个工具、干活时说的话，最后是回答；计划到点也显示在这里；点 X 退出语音。地图会移到它找到的每个地点，先展示整条算好的路线，然后才开始导航。
- **小家**：四个房间的灯、窗帘、空调，每个房间的人体传感器，入户门锁，扫地机，以及场景（回家、离家、睡觉、观影、起床）。
- **读数会变**：温度会朝空调设定或室外温度变化，「模拟行驶」会耗电、缩短导航距离，所以「车里降到 24 度以下就把车窗都关上」、
  「客厅超过 28 度就开空调」这类计划几分钟内就会触发。两个服务都把状态发布成资源（`playground://cabin/state`、
  `playground://home/state`），Bothy 的计划可以监听。
- **也能手动改**：点座位让人上下车，长按座位让那里的人说话，点房间开关灯；侧栏里有全部控件和读数。手动改的会在记录里标「手动」，
  和真实变化一样会触发计划。

演示方法：两个 App 都装上，先打开 Bothy Playground，再在 Bothy 的「连接应用」里连上「虚拟座舱」和「虚拟小家」（不需要 key）。
让 Playground 留在前台，直接在车机里跟小博对话。电脑上的 MCP 客户端可以用 `adb forward tcp:8790 tcp:8790` 连同样的服务。

导航需要你自己的高德 Android key，绑定包名 `cc.noharry.bothy.playground` 和你签名用的 SHA1：在
`workspace/apps/android/local.properties` 里写 `AMAP_ANDROID_KEY=…`（这个文件不会提交）。没有 key 时导航工具会说明缺什么。
车机接管助手靠签名级权限，所以两个 App 要用同一个签名（签名方法见[运行演示 App](#运行演示-app)）。

## 快速开始

Bothy 暂未发布到 Maven，先以 Gradle 项目依赖的方式使用：

```bash
git clone https://github.com/NoHarry/bothy.git
```

```kotlin
// 你的 App 的 settings.gradle.kts：把 runtime 和它依赖的模块 include 进来
val bothy = file("path/to/bothy/workspace/apps/android")
listOf("agent-kernel-api", "agent-kernel-core", "agent-vfs", "agent-runtime", "agent-runtime-android").forEach { name ->
    include(":$name")
    project(":$name").projectDir = bothy.resolve(name)
}

// App 模块的 build.gradle.kts
dependencies {
    implementation(project(":agent-runtime-android"))
}
```

```kotlin
val runtime = AgentRuntimeBuilder.create()
    .modelClient(myModelClient)          // OpenAI 或 Anthropic 兼容的客户端
    .toolProvider(myMcpProvider)         // 可选：来自 MCP 服务的工具
    .workspaceRoot(filesDir)             // 可选：agent 的沙箱工作区
    .build()

runtime.run(AgentRequest(requestId = "r1", sessionId = "s1", input = "帮我规划去成都三天的行程"))
    .collect { event -> /* 文本增量、工具调用、完成 */ }
```

完整的接入示例见演示 App：模型配置、MCP 服务、skill、工作区挂载、计划、语音和事件处理。

## 运行演示 App

```bash
cd workspace/apps/android
./gradlew :bothy-sample:assembleDebug
./gradlew :bothy-playground:assembleDebug   # 可选：给 Bothy 操作的车和家
```

模型服务预置来自 `bothy-sample/src/main/assets/model-provider-profiles.json`，它会打进 APK，所以**不含任何 API key**。
key 写在被 Git 忽略的 `workspace/apps/android/local.properties`：

```properties
# local.properties（永不提交）
AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY=your-key
```

key 名为 `AGENT_RUNTIME_PROVIDER_PROFILE_{PROFILE_ID}_API_KEY`，profile id 转大写、`-` 换成 `_`。为了方便，
`local.properties` 里的 key 会编进 debug 包；要发给别人的 APK 请加 `-PsampleEmbedLocalKeys=none`（或 `tts`）构建，
或者在 App 里填 key。

两个 App 默认用本机的调试签名；`local.properties` 里指定了自己的签名文件时，debug 和 release 都用它签，
换哪台电脑打的包都能覆盖安装：

```properties
BOTHY_SIGNING_STORE_FILE=/仓库外的路径/bothy-release.jks
BOTHY_SIGNING_STORE_PASSWORD=…
BOTHY_SIGNING_KEY_ALIAS=bothy
BOTHY_SIGNING_KEY_PASSWORD=…
```

签名文件和密码要备份好：丢了以后，新打的包就不能覆盖安装旧的了。

设备端语音模型和 sherpa-onnx 原生库已经提交在仓库里，arm64 设备上开箱即用；`tools/voice/fetch_voice_assets.py`
可以重新下载并校验它们。

## 环境要求

- 库：Android `minSdk 23`；演示 App：`minSdk 24`、`compileSdk 37`
- JDK 17、Gradle 9.6、Android Gradle Plugin 9.4、Kotlin 2.4

## 许可证

[MIT](LICENSE)。第三方组件和提交的模型文件保留各自的许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
