# bothy-server：一个服务端，多套前端

## 目标

Bothy 的手机界面、平板/车机控制台和悬浮窗要能各自长成不同的样子，但跑的是同一个服务端，像 Codex 的 app-server：TUI、IDE 插件、桌面端都是它的客户端，只通过协议说话。

- 通用的运行时、会话、模型、MCP、计划、A2A、记忆、用量等逻辑在 `agent-app-server`，Bothy 自己的语音、本机模型、检查和 Playground 在 `bothy-server`，都不含界面。
- 前端只通过协议（通用的 `agent-app-protocol` 加上 Bothy 的 `bothy-protocol`）和服务端说话：发请求、收通知、答服务端发来的请求（审批）。
- 同一个协议既在进程内用（三套前端），也从进程外用（PC 上 `adb forward` 后直接连）。

不在这次范围：界面改版（Stitch 手机稿另开一批）；SDK 仓的任何改动。

## 模块

```
agent-app-protocol  纯 Kotlin/JVM：消息、类型、JSON-RPC 编解码、客户端和状态归并、两边共用的纯函数（view 包）、通用的 Finding 码
      ^         ^
      |     bothy-protocol    纯 Kotlin/JVM：Bothy 自己的方法、扩展分区、BothyFindingCode、悬浮卡片投影、完整的方法表和 schema
      |
agent-app-server  Android library：通用的服务端（运行时、会话、模型、MCP、计划、A2A、记忆、用量），实现协议；不认识任何 App
      ^
bothy-server      Android library：Bothy 的插件（语音、本机模型、检查、Playground、自己的设置页）和配置；对外只公开 BothyServer
      ^
bothy-app         Android application：控制台、手机、悬浮窗、设置界面；BothyApplication 负责接线
```

- 依赖只能向下：两个协议模块不依赖 Android 和 SDK，`bothy-protocol` 依赖 `agent-app-protocol`；`agent-app-server` 依赖 SDK 和 `agent-app-protocol`（不依赖 `bothy-protocol`），不依赖 `bothy-voice`、`bothy-llm` 和 Compose；`bothy-server` 再加上 `bothy-protocol`、`bothy-voice`、`bothy-llm` 和 `agent-app-server`（`implementation`）；`bothy-app` 依赖 `bothy-protocol` 和 `bothy-server`，看不到 SDK、`agent-app-server` 和服务端的 `internal`。
- 包名：`cc.noharry.agent.appserver.protocol.*`、`cc.noharry.bothy.protocol.*`、`cc.noharry.agent.appserver.*`、`cc.noharry.bothy.server.*`、`cc.noharry.bothy.app.*`。`agent-app-protocol` 和 `agent-app-server` 先放在本仓，稳定后搬到内核仓。
- 前端绕过协议直接调逻辑，编译就过不了：`bothy-server` 的实现一律 `internal`，`agent-app-server` 只公开宿主要用的东西（见下文的 API 清单），依赖它的只有宿主（`bothy-server`、`agent-app-sample`），`bothy-app` 看不到它。`harness.yaml` 的架构层和 `lint-arch` 再按包前缀查一遍，`agent-app-server` 也不许导入 `bothy-server`。
- 资源各管各的：服务端自己说出口的文字（检查结果、错误、选项名）用 `agent-app-server` 或 `bothy-server` 的字符串（谁的代码说谁放），界面用 `bothy-app` 的；两边都要的同一条字符串各留一份。运行时的 assets（skill、子 agent、模型预置、唤醒词）是 Bothy 的，在 `bothy-server`。

## 协议

### 信封

JSON-RPC 2.0 的子集，和 Codex 一样不写 `"jsonrpc"` 字段：

- 请求 `{"id": 1, "method": "turn/start", "params": {...}}`
- 响应 `{"id": 1, "result": {...}}` 或 `{"id": 1, "error": {"code": -32602, "message": "..."}}`
- 通知 `{"method": "item/updated", "params": {...}}`（没有 `id`）
- 服务端也会发请求（审批），客户端按同样的格式回响应。

进程外按行分帧（JSONL），一行一条消息；进程内走一对内存通道，消息照样是编码后的 JSON，和进程外一个样。

### 生命周期

1. 客户端发 `initialize`，带 `clientInfo`（名字、版本）和 `capabilities`（是否接审批、是否要轨迹）。
2. 服务端回 `protocolVersion`、`serverInfo` 和当前状态快照。
3. 客户端发 `initialized` 通知，之后开始收通知。握手前发出的请求等握手完再发。

协议版本从 1 开始；只加字段、加方法时不升版本，删改才升。未知字段两边都忽略。

### Schema

和 Codex 的 `app-server generate-json-schema` / `generate-ts` 一样，协议另外发布成 schema，别的语言写客户端照着它来：

- `bothy-protocol/schema/bothy-protocol.schema.json`（JSON Schema 2020-12）：`methods` 下按谁发分四组（clientRequests、clientNotifications、serverRequests、serverNotifications），每个方法的 params 和 result；`$defs` 是所有类型。
- `bothy-protocol/schema/bothy-protocol.d.ts`：同样的内容写成 TypeScript 类型，四组方法各是一个从方法名到 params、result 的接口。
- 两个文件都由 `./gradlew :bothy-protocol:generateSchema` 从 Kotlin 类型生成并提交；生成器在 `bothy-protocol` 的 `src/schema`，不打进 App。sealed 类型带 `type`，成员按所属类型加 `type` 命名（`StateSectionRun` 是 `run` 分区）。
- 方法清单分两层：通用的在 `agent-app-protocol` 的 `AppServerMethods`，Bothy 的 `ProtocolMethods` 在它后面加上自己的；一个方法一行，新方法除了定义，还要在对应的清单里加一行。schema 按完整清单生成（Bothy 的 App 说的就是这些）。

防漂移的测试：`SchemaFilesTest` 检查提交的 schema 和代码一致、清单列全了协议里定义的方法；`bothy-server` 的 `ProtocolRoutesTest` 检查服务端答的请求正好是清单里的客户端请求；`tools/bothy-protocol` 的 `node --test` 检查 Node 客户端发的、等的方法都在 schema 里。

### 会话、轮次、条目

和 Codex 的 thread / turn / item 对应：

| 概念 | Bothy | 协议 |
|---|---|---|
| 会话 | session | thread |
| 一次运行 | run | turn |
| 时间线上一行 | `ConversationItem` | item：userMessage、message（助手、思考、状态、提示、错误、运行时）、toolCall、approval、runtimePhase |

Bothy 有一个全局的“当前会话”，语音和悬浮窗都认它：`thread/select` 切当前会话，每个客户端都跟着它。除此之外，客户端可以像 Codex 那样各看各的会话：

- `thread/read` 读任意会话的条目；`thread/subscribe` 跟随一个会话，先拿到它现在的条目，之后它的条目、轮次和审批通知都发给这个客户端（流式输出也是实时的）；`thread/unsubscribe` 取消。客户端把跟随的会话放在 `ServerState.threads`。
- `turn/start` 带 `threadId` 和 `focus: false` 时，这一轮在那个会话里跑，当前会话不变；`turn/interrupt` 可以带 `threadId` 停指定会话的运行。
- 服务端除了当前会话，还让“已加载”的会话各有一份活的条目：有运行在跑的，或有客户端跟随的。运行的事件写进它自己会话的那份；切换当前会话时两份互换，进行中的状态不丢；没人跟随、也没在跑时放掉，之后从存储的轮次读。
- `run` 分区的 `runningThreads` 列出所有在跑的会话。非当前会话的审批只发给跟随它的、能接审批的客户端；当前会话的审批发给所有能接审批的客户端。

条目在线上是不可变的快照。服务端每次发布状态时把当前会话的条目和上次发出去的比较：新的发 `item/started`，变了的发 `item/updated`（只在末尾追加文字时发 `item/delta`），没了的发 `item/removed`。折叠、展开这类纯展示状态留在客户端。

### 状态分区

条目之外的状态按分区整块下发（`state/changed`），客户端用 `ServerState` 归并，前端照旧按状态渲染：

`run`、`model`、`context`、`demo`、`mcp`、`a2a`、`workspace`、`artifact`、`trajectory`、`a2aClient`、`plans`、`delegations`、`voice`、`preferences`、`memory`、`usage`、`onDevice`、`checks`、`offers`、`speech`、`subagents`。

- `trajectory` 只发给声明了 `capabilities.trajectory` 的客户端（控制台）。
- `usage` 只带版本号：账本每次模型调用都会长，页面看到版本变了再用 `usage/read` 取。
- `speech` 是播报的响度，播报时约 20 ms 一次；`voice` 里的麦克风电平同理。

### 下发的是数据，不是文案

像 Codex 的 app-server 只发条目和数据、由客户端渲染一样，服务端发布的状态分区和设置页请求的结果里没有为某个界面写好的话，手机和控制台各自措辞、排版：

- 可以由服务端给：内容（人、模型、工具说的话，用户起的名字，计划和记忆的正文）、目录数据（连接应用、内置技能和子 agent、音色、模型的名字和说明）、id、枚举、原始单位的数字和时间（毫秒、epoch 毫秒、字节）、外部来的原话（异常信息、服务的回复）。
- 不由服务端给：枚举值的标签、拼好的状态句和摘要（"用时 12 秒"、"已加载 3/6"）、格式化过的数字金额百分比、选项名、字符串资源里的固定句子、按语言措辞的词。
- 服务端发现、拒绝、报告的事情用 `Finding`：`code` 说是什么（界面用同名字符串措辞，`CHECKS_MIC_OK` 对应 `R.string.checks_mic_ok`），`args` 是它说到的值，`more` 是嵌在里面的 finding（原因、检查的各项），`count` 决定单复数，`balance` 是 DeepSeek 余额；`TEXT`、`PARTS`、`LIST`、`ALTERNATIVES`、`LINES` 只负责排列。界面用 `Resources.words(finding)` 措辞，`FindingWordingUiTest` 保证每个 code 在中英文里都有措辞且取同样的值。
- 服务端自己说出口的话仍在服务端：播报和 TTS、通知、给模型和工具看的文字、A2A 回复、会话记录里的行（`ConversationItem`、A2A 客户端模式的条目）。
- 运行状态同理：`run` 分区给 `activity`（`RunActivity`：思考、运行某个工具、等审批……）和 `tool`，上一轮的结果给 `lastRun`（`RunResult`：结局和毫秒数），`model` 分区给 `stream`、`simulated` 布尔值和 `configSource`。`runtimeStatus` 是运行时自己的英文状态行，只给调试视图看，界面不据此措辞。

### 方法

客户端 → 服务端的请求按功能区分命名空间：

| 命名空间 | 作用 |
|---|---|
| `initialize` | 握手 |
| `thread/*`、`turn/*` | 会话列表、新建、切换、删除、读取、跟随；发一句话（可以发到非当前会话）、运行中插话、停止 |
| `approval/respond`、`question/answer` | 回答审批和提问 |
| `model/select`、`run/set`、`provider/*`、`config/reload`、`modelSettings/*`、`deepseek/*`、`webLookup/*`、`onDevice/*` | 模型、变体、确认模式、服务商配置、上下文窗口、DeepSeek 余额和检测、联网搜索、本机模型 |
| `voice/*`、`voiceSettings/*`、`realtime/*`、`companion/*` | 唤醒、麦克风、按住说话、播报；语音设置和四个密钥；端到端语音；陪聊 |
| `mcp/*`、`connectors/*`、`a2a/profile/*`、`a2aClient/*` | MCP 服务器和工具、连接应用、A2A 档案、A2A 客户端模式（连的是本机 A2A 端点，宿主不开端点时开不起来） |
| `tools/*`、`skills/*`、`subagents/*`、`jev/*`、`plans/*`、`preferences/*` | 基础工具、技能、子 agent、工具检索、计划、会话策略 |
| `memory/*`、`usage/*`、`imageModel/*`、`files/*`、`workspace/*`、`data/*`、`trajectory/*` | 记忆、用量和价格、生图模型、工作区浏览、清数据、轨迹分页 |
| `checks/run`、`onboarding/*`、`app/*`、`playground/*` | 一键检查、首次设置、前台/语言变化、Playground |

密钥只进不出，进程内外的客户端都一样：状态和设置里只有 `hasApiKey`、`hasKey` 之类的标记，表单里留空表示沿用已存的 key，取模型列表、查余额、检测也由服务端按 profile 取已存的 key。

服务端 → 客户端的通知：

| 通知 | 内容 |
|---|---|
| `thread/list/changed`、`thread/selected` | 会话列表和当前会话；切换后当前会话的完整快照 |
| `turn/started`、`turn/completed` | 一轮开始、结束（含用时摘要） |
| `item/started`、`item/updated`、`item/delta`、`item/removed` | 条目变化 |
| `state/changed` | 变了的状态分区 |
| `approval/resolved` | 别的客户端已经答了 |
| `voice/dismissed` | 说“退下吧”收起了唤醒的会话 |

服务端 → 客户端的请求只有 `approval/request`（哪个工具、什么参数、哪个域），客户端回 `{decision: approve / deny}`。几个客户端同时声明能接审批时，先回的生效，其余收到 `approval/resolved`。提问（ask）放在状态里，由 `question/answer` 回答。

## 服务端

- 通用的 `AppServer` 和 Bothy 自己的东西分开：`AppServer` 只有运行时、会话、模型、MCP、计划、A2A、记忆、用量和协议本身，不知道车机、语音或 Playground。宿主 App 用 `AppServerConfig` 交给它：
  - 名字（握手时的 `serverInfo.name`）；
  - 人设（`Persona`）：助手叫什么，系统提示词开头那句“你是谁、在哪个 App 里”；不给就是通用的“助手”。提示词和工具说明里模型读到的关于自己的话一律写“你”，不写名字；
  - 插件（`AppServerFeature`）：进程启动时起什么（`install`）、答哪些请求（`routes`）、往状态里发布什么（`publish`，多是 `ExtensionSection`）、App 进出前台和悬浮窗来去时做什么，每样都可以不要；
  - 接到运行时上的口子：`VoicePort`（播报和语音会话，没有就静音照跑）、`HostModels`（宿主自己跑的模型，比如本机模型；模拟后端的模型 `simulated`，Bothy 给的是演示车机的脚本，不给就用通用的：问它是谁就自我介绍，别的只说收到了什么）、`RunAddOns`（每次运行加的上下文、系统提示里的规则、工具调用的闸门、远端工具的说明、工具包装、自带的工具、skill 和子 agent 类型，哪些工具服务算设备：没开「允许子 agent 操作设备」时子 agent 只能查询它们；卡片显示前的检查和补充、回复怎么收尾（`CardHooks`）；回放检查里哪些请求必须调用服务、回答还有什么问题；回复里哪几行是语音边做边说的进度（`progressWords`），屏幕、会话记录和别的 agent 拿到的回复不带这些行，只有进度的回复也不算最终回答。通用层默认都不加：Bothy 的下单确认、网上核实、天气活动走联网搜索、长回答画页面、行程卡片的时间检查和配图、车机请求的回放检查、`TTS:` 进度行都在 `BothyRuns` 里）；
  - 模型预置（`ProviderPresets`）：构建时带的模型服务，以及配置文件里宿主自己的段（`sections`，段名对键前缀，比如 Bothy 的 `tts` 读成 `MIMO_TTS_*`；核心不认识任何具体的语音服务）；
  - 位置（`HostPlaces`）：工具说给模型的话里要提到的界面位置，记忆在哪看和改（`memoryPage`）、生成的图片显示在哪（`picturesShownOn`）；不给就不提。
  - 自带功能（`BuiltInFeatures`）：计划（工具、定时和盯状态的计划、闹钟）、操作屏幕（开关打开时服务端自己把无障碍服务列上）、设备媒体、卡片和选项工具、开机和更新后自启（计划要它把闹钟重新设上）。默认都关：一个只带服务端的 App 不会开机自启，也不会去动无障碍设置。关着的功能没有自己的请求（问了回“方法不存在”），计划关着时计划的分区是空的、计划本也不读；握手时 `serverInfo.features` 列出开着的（`plans`、`androidUse`、`deviceMedia`、`cards`），客户端可以据此只给这些出界面。Bothy 全开，并在自己的 manifest 里把 `BootReceiver` 打开，带来这个开关的那次更新也照样自启。
  - 模型服务适配（`providerAdapters`，`ProviderAdapter`）：某家模型服务聊天之外的东西——联网查找（搜索和抓页的 API，联网搜索和抓页兜底都走它）、运行里发它自己的历史更新（DeepSeek 的 system 更新和 `tool_addition`）、它自己的请求（余额、中转检测）、它的预设配置共用一把 key。默认没有：一个 DeepSeek 配置在通用宿主里只有聊天。DeepSeek 的这些都在服务端的 `deepseek` 包里（`DeepSeekAdapter`），协议在 `protocol.deepseek`；Bothy 装它。DeepSeek 的用量字段、价格和上下文窗口仍按模型名认，和 Claude、GPT 一样。
  - 额外信任根（`extraTrustRoots`）：系统根证书之外，服务端所有 HTTPS 还信任的根（PEM，宿主的 raw 资源）；默认不加。Bothy 给的是部分车机系统证书库里缺的那几张。服务端设 A2A 配置和生图模型的两个调试广播只在 debug 包里。
  - A2A（`A2aHost`）：本机的 A2A 端点（`127.0.0.1:8766/a2a`）开不开，别的客户端交来的请求里宿主自己的命令（`intercept`），别的客户端的任务开始时宿主做什么（`inboundStarted`），交给别的 agent 的任务把来源写成什么（`contextSource`）；不给就不开端点、什么都不弹，来源写 `device`。Bothy 开端点，外部任务一来就弹出卡片，认 `carOverlay=show|hide`、`carOverlayOnly=true`，来源写 `car`（中转那边已配对的 Bot 认这个名字）。
- `BothyServer`（公开）就是 Bothy 的这份配置：人设是小博，插件是语音（含唤醒、实时、陪聊、Jev 工具检索）、本机模型、检查、Playground 和 Bothy 自己的设置页，口子是 `BothyVoice`、`BothyModels`、`BothyRuns`，配置文件多读一个 `tts` 段，位置是“设置 → 记忆”和“屏幕的悬浮窗上”。对外仍是 `install`（进程启动时由 `BothyApplication` 调）、`connect`（给一个进程内客户端）、`wakeUp`（没有前端时起状态来源，比如唤醒词、计划）。界面语言变化走协议的 `app/languageChanged`。
- `agent-app-server` 只公开宿主用得上的东西，清单在 `agent-app-server/api/agent-app-server.api`，validate 比对；其余是 `internal`。跑在 Bothy 的 assets 和插件上的那些核心逻辑测试还留在 `bothy-server`，它们要用的名字标 `@InternalAppServerApi`（需要 opt-in，只有 `bothy-server` 的单测打开）；搬去内核仓时这些测试跟着核心走，标记就可以换成 `internal`。
- 别的 App（手机上的独立 agent、别的场景的快速接入）用同一个 `AppServer`，配自己的插件，或者一个都不配；`agent-app-sample` 就是一个都不配的最小例子（一个界面，模拟模型，通用人设）。
- 运行的场景（`AppServerConfig.scene`）默认 `app`：运行时按场景存学到的经验、筛选写了 `scenes` 的 skill、放工作区上下文。Bothy 沿用一直以来的 `debug-console`，设备上学到的经验和语音 skill 的场景都在它下面。
- 通用层存的东西用自己的名字：偏好设置是 `app_server_*`（不和宿主自己的撞名），会话记录在工作区的 `sessions` 目录（模型读的是 `/sessions/index.json`）。早先的版本放在调试台的名字下（`debug_*`、`debug-sessions`），`AppServer.install` 一开始搬：偏好设置把新文件里没有的项复制过去，落盘后清空并删掉旧文件；会话目录没有 `sessions` 就整个改名，有就逐个移进去（旧的 `index.json` 丢掉，打开会话库时会重写）；两边都有的同名会话，旧的没有对话就删掉，有对话就连同剩下的一次挪到 `debug-sessions-unmerged`，记一行日志，以后不再看。每次启动都查，旧名下没东西就什么也不做；设备装回过旧版本、旧名下又写了东西，也照这样并进来，不覆盖新名下已有的。工作区根目录 `runtime-debug-workspace`（`workspace.json` 的挂载源，运行时的存储按它认）、运行证据和事件日志的目录沿用原名。
- 卡片和选项工具的服务名是 `app_ui`（模型看到 `mcp__app_ui__show_card`）。宿主自带的示例工具（`assets/mcp/tools.json`）是什么由宿主说：Bothy 的是演示车机，在它的 `deviceServers` 里；通用层不把它们当设备。服务端自己的工具（卡片、计划、操作屏幕）和委托给别的 agent 的工具以外，`isServiceTool` 都认作服务的工具。
- 通用层自己带运行时工作区的布局（`runtime-workspace/workspace.json`、工作区的 `AGENTS.md`）和计划自动执行的安全策略（`automation-policy.json`）；宿主没带的 assets（预置的 A2A 配置、skill、子 agent、模型预置）一律当作没有，不报错。
- 状态来源（`StateSources`，接上运行时、MCP、A2A 等）在第一个要状态的客户端连上时才启动；不要状态的客户端（`capabilities.state = false`）连上不会启动任何东西。
- `ServerHub`：发布状态（`StatePublisher` 算出条目和分区的变化）、管连接、广播通知；`ServerConnection` 一条连接一个，负责握手、审批请求的收发。
- 请求表：核心的会话、轮次、模型、连接在 `ServerRoutes`；其余每个功能区一个 `RouteModule`，自己登记它答的方法。通用的几个（`ImageModelRoutes`、`MemoryRoutes`、`ModelSettingsRoutes`……）在 `coreModules`，插件的由插件的 `routes` 给出；新功能区加一个模块，不改核心。
- `StateSources` 订阅运行时和通用功能区的流（会话、计划、记忆、用量、子 agent……）合成 `ServerState`，再让每个插件往里发布自己的分区。
- 悬浮窗也是普通客户端：`bothy-app` 的 `HostConnection` 在进程启动时连上，声明 `capabilities.presentation = true`、`state = false`。服务端通过它发请求 `presentation/ensureRunning|start|card|wake`（唤醒的来源是宿主自己的词，Bothy 是 `WakeOrigin`）和 `androidUse/begin|point|drag|hide`（指针到位、截图前藏好才回），发通知 `presentation/planDue|planRan`、`androidUse/reveal`；它用 `presentation/state` 通知报悬浮窗是否就绪、用户读到什么时候、控制台是否在前台，服务端缓存着同步查（`Presenter`）。宿主自己的请求用 `Presenter.ask` 发，比如 Bothy 调试吉祥物动作的 `presentation/previewMascot`（`BothyPresentationRequests`）。没有这样的客户端时，这些请求一律答“不在屏幕上”，服务端照常运行。

## 传输

- **进程内**：`BothyServer.connect()` 给出一对内存通道，三套前端各连一个。
- **进程外**：本机抽象套接字，名字是宿主的 `AppServerConfig.name`（Bothy 是 `bothy`，示例 App 是 `agent-app-sample`），只在 debug 包里开；对端 UID 只认本 App、shell（adb）和 root，别的 App 连上来直接断开。PC 上 `adb forward tcp:8791 localabstract:bothy` 后按行收发 JSON；读不出的请求行回一个错误（能读出 id 就带上），不会让对方一直等。
- `workspace/apps/android/tools/bothy-protocol/`：Node 写的最小客户端，`state`、`say`、`watch`、`call` 四个命令（`call` 不带方法时列出 schema 里的全部请求），`--approve` 在终端里答审批；`node --test` 跑它自己的测试。

## 客户端

- `agent-app-protocol` 的 `ServerClient`：发请求、等响应（挂起函数），处理审批请求；把快照和通知归并成 `ServerState`（`StateFlow`）。
- `bothy-app` 里：`FrontEndClient`（控制台和手机，握手后才发请求）、`OverlayConnection`（悬浮窗的状态）、`HostConnection`（悬浮窗的呈现和 App 的通知，不要状态）、`AppClient`（广播接收器等一次性调用）；Compose 里用 `LocalServer` 拿当前状态和发请求。
- 两边共用的纯函数（a2ui 卡片和选项、提问卡片、计划触发条件的文字、HTML 预览、轨迹的时间线、搜索、时长和检查器的标签页、分页……）在 `agent-app-protocol` 的 `view` 包；Bothy 悬浮卡片的投影（阶段、尺寸、可点的回复、计划行、A2A 客户端模式的卡片）、行程的出行方式和播报的进度行（`TTS:`）在 `bothy-protocol` 的 `view` 包，服务端和界面算出来的一样。

## 做法上的约束

- 前端不碰 SDK 和服务端的 `internal`，只用协议和 `BothyServer` 的 `install`、`connect`。
- 服务端不碰 Compose 和 `bothy-app`；要界面说的话，服务端给数据、枚举或 `Finding`，界面自己映射成字符串资源（见上文“下发的是数据，不是文案”）。
- 新的设置页：在 `bothy-protocol` 加视图类型和 `XxxRequests`，在服务端加一个 `RouteModule`，通用的放进 `coreModules`，只有 Bothy 用的放进对应插件的 `routes`；页面用 `LocalServer` 读写。
- 通用层（`AppServer`、`ServerHub`、`StateSources`、运行时）不引用插件的代码；插件要什么，通过 `AppServerFeature` 和三个口子拿。
- 测试跟着代码走：通用服务端的测试在 `agent-app-server`，插件的测试和跑在 Bothy 的 assets、插件上的那部分核心测试在 `bothy-server`，界面上的文字、布局、投影的测试在 `bothy-app`（`*UiTest`），输入用协议类型构造。

## 风险

- **语音和悬浮窗耦合最深**：唤醒、按住说话、播报和悬浮窗卡片互相等待。语音逻辑在服务端，悬浮窗按语音状态和 `voice/*` 请求驱动；改这一块要在真机上把唤醒、按住说话、计划播报都过一遍。
- **条目对比的开销**：流式输出时每秒几十次变化；对比只做当前会话，文字追加走 `item/delta`。轨迹记录不带事件全文（一次运行的每个完整提示词会跟着每次变化发一遍），检查器打开 Raw 页时才用 `trajectory/raw` 取。小博在对话里画的页面（`show_html`，几百 KB 也可能）同样不进条目：服务端存在会话的 `drawn-pages/` 目录下，条目只带引用，界面显示时用 `pages/read` 取。播报响度在说话时每秒进状态 50 次，只有悬浮窗用，控制台收到后先去掉再比，不跟着重画。
- **按住说话的时序**：按下时请求还没回就已经开始收手势，所以松手的请求只结束“由按住开启的”会话，服务端据此判断，不会误停别处开的会话。
- **字符串两份**：服务端自己说的话和界面措辞的话分开放；两边都用的同一条字符串各留一份，改一边要记得另一边。
