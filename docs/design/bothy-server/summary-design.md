# bothy-server：一个服务端，多套前端

## 目标

Bothy 的手机界面、平板/车机控制台和悬浮窗要能各自长成不同的样子，但跑的是同一个服务端，像 Codex 的 app-server：TUI、IDE 插件、桌面端都是它的客户端，只通过协议说话。

- 通用的运行时、会话、模型、MCP、用量等逻辑在运行时仓的 `agent-app-server`；模型服务（含 DeepSeek）、操作屏幕和设备工具的插件在运行时发布的 `agent-app-models`、`agent-android-use`、`agent-device-capabilities` 里，从它们的 manifest 加载；卡片、画页面、记忆、生图、计划、A2A 这些第一方插件各是本仓 `workspace/apps/android/plugins/` 下的一个模块（不发布、不依赖 Bothy）；Bothy 自己的语音、本机模型、检查和 Playground 在 `bothy-server`，都不含界面。
- 前端只通过协议（通用的 `agent-app-protocol` 加上 Bothy 的 `bothy-protocol`）和服务端说话：发请求、收通知、答服务端发来的请求（审批）。
- 同一个协议既在进程内用（三套前端），也从进程外用（PC 上 `adb forward` 后直接连）。

不在这次范围：界面改版（Stitch 手机稿另开一批）；SDK 仓的任何改动。

## 模块

```
agent-app-protocol  （运行时仓）纯 Kotlin/JVM：消息、类型、JSON-RPC 编解码、客户端和状态归并、两边共用的纯函数（view 包）、通用的 Finding 码
      ^         ^
      |     bothy-protocol    纯 Kotlin/JVM：Bothy 自己的方法、扩展分区、BothyFindingCode、悬浮卡片投影、完整的方法表和 schema
      |
agent-app-server  （运行时仓）Android library：通用的服务端（运行时、会话、模型、MCP、用量、插件内核），实现协议；不认识任何 App
      ^
plugins/agent-app-*  Android library（DeepSeek 的协议部分是纯 Kotlin）：第一方插件，各一个模块，只依赖运行时的构件和彼此
      ^
bothy-server      Android library：Bothy 的插件（语音、本机模型、检查、Playground、自己的设置页）和配置；对外只公开 BothyServer
      ^
bothy-app         Android application：控制台、手机、悬浮窗、设置界面；BothyApplication 负责接线
```

- 依赖只能向下：两个协议模块不依赖 Android 和 SDK，`bothy-protocol` 依赖 `agent-app-protocol`；`agent-app-server` 依赖 SDK 和 `agent-app-protocol`（不依赖 `bothy-protocol`），不依赖 `bothy-voice`、`bothy-llm` 和 Compose；`bothy-server` 再加上 `bothy-protocol`、`bothy-voice`、`bothy-llm` 和 `agent-app-server`（`implementation`）；`bothy-app` 依赖 `bothy-protocol` 和 `bothy-server`，看不到 SDK、`agent-app-server` 和服务端的 `internal`。
- 包名：`cc.noharry.agent.appserver.protocol.*`、`cc.noharry.bothy.protocol.*`、`cc.noharry.agent.appserver.*`、`cc.noharry.bothy.server.*`、`cc.noharry.bothy.app.*`。`agent-app-protocol`、`agent-app-server` 和示例 App 在运行时仓（实验性，发布的两个构件在 `agent-bom` 里），Bothy 经坐标依赖，`includeBuild` 从源码构建；第一方插件在本仓 `plugins/`，沿用 `cc.noharry.agent.appserver.*` 的包名。
- 前端绕过协议直接调逻辑，编译就过不了：`bothy-server` 的实现一律 `internal`，`agent-app-server` 只公开宿主要用的东西（见下文的 API 清单），本仓依赖它的只有宿主 `bothy-server`，`bothy-app` 看不到它。`harness.yaml` 的架构层和 `lint-arch` 再按包前缀查一遍 Bothy 的模块；运行时仓的 `checkAppServerLayering` 不许它的模块提到 Bothy 的包。
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
- 边做边说的进度不写在正文里：宿主可以给工具调用加一个「说给用户」的参数（`RunAddOns.toolNote`，Bothy 的叫 `say`）。`AppModelClient` 把它加进模型读到的工具参数表（查找类和收尾类工具不加），模型调用工具时填一句；调用交给运行时之前这个参数就被摘掉，任何工具都收不到，写了什么存在 `ToolNotes` 里，播报器在这次调用开始时取出来念（只念第一句，一次运行最多四句）。模型之后的请求里，它自己的调用仍带着当时写的那句。正文、思考和工具状态都不播报；服务方自己执行的搜索没有工具调用，带不了这个参数。
- 运行状态同理：`run` 分区给 `activity`（`RunActivity`：思考、运行某个工具、等审批……）和 `tool`，上一轮的结果给 `lastRun`（`RunResult`：结局和毫秒数），`model` 分区给 `stream`、`simulated` 布尔值和 `configSource`。`runtimeStatus` 是运行时自己的英文状态行，只给调试视图看，界面不据此措辞。

### 方法

客户端 → 服务端的请求按功能区分命名空间：

| 命名空间 | 作用 |
|---|---|
| `initialize` | 握手 |
| `thread/*`、`turn/*` | 会话列表、新建、切换、删除、读取、跟随；发一句话（可以发到非当前会话）、运行中插话、停止 |
| `approval/respond`、`question/answer` | 回答审批和提问 |
| `model/select`、`run/set`、`provider/*`、`config/reload`、`modelSettings/*`、`services/*`、`webLookup/*`、`onDevice/*` | 模型、变体、确认模式、服务商配置、上下文窗口和输出上限、认识的模型服务和它们的余额与中转检测、联网搜索、本机模型 |
| `voice/*`、`voiceSettings/*`、`realtime/*`、`companion/*` | 唤醒、麦克风、按住说话、播报；语音设置和四个密钥；端到端语音；陪聊 |
| `mcp/*`、`connectors/*`、`a2a/profile/*`、`a2aClient/*` | MCP 服务器和工具、连接应用、A2A 档案、A2A 客户端模式（连的是本机 A2A 端点，宿主不开端点时开不起来） |
| `tools/*`、`skills/*`、`subagents/*`、`jev/*`、`plans/*`、`preferences/*` | 基础工具、技能、子 agent、工具检索、计划、会话策略 |
| `memory/*`、`usage/*`、`imageModel/*`、`files/*`、`workspace/*`、`data/*`、`trajectory/*` | 记忆、用量和价格、生图模型、工作区浏览、清数据、轨迹分页 |
| `checks/run`、`onboarding/*`、`app/*`、`playground/*` | 一键检查、首次设置、前台/语言变化、Playground |

密钥只进不出，进程内外的客户端都一样：状态和设置里只有 `hasApiKey`、`hasKey` 之类的标记，表单里留空表示沿用已存的 key，取模型列表、查余额、检测也由服务端按 profile 取已存的 key。

一键检查和首次设置的“保存并检查”查当前模型（`evidence/ChatModelCheck`）时，模型列表和设置里的“获取模型”走同一条路（`provider/fetchModels`，按服务的规则取：Gemini 去掉 id 前面的 `models/`，有自己列表地址的用那个地址），不自己去问 `<base URL>/models`。没有模型列表的服务（`services/list` 里 `listsModels` 为 false，如智谱、阿里云百炼）拿到的只是服务数据里的模型，什么 key 都会通过，所以改为按运行时的方式（线协议、端点、是否流式）让当前模型回一个 token：key 被拒报“key 不对”（HTTP 401/403，或服务自己的规则判成鉴权失败），模型不认且不在服务数据里报“不在列出的模型里”，其他失败照常报状态码或连不上。

添加模型服务先选服务商：`services/list` 列出的服务（有国内、国际等几个地区的地址时再选地区），或者自定义（协议、地址、模型都自己填）。选了服务就填好名称、协议和端点、地址、默认模型和模型列表，key 输入框下面用界面语言显示到哪里申请 key（服务按地区给的创建 key 的页面 `keyPages`，Bothy 自己用中英文措辞，网址可以点开；各地区是不同站点的服务再加一句 key 只能在创建它的地区用；没给页面的服务显示它自己的 `keyHint`），只差粘贴 key，填好的都还能改；`services/list` 是空的（模型服务插件关了）就只有自定义。平板和手机的设置、首次设置的“模型 key”用的是同一套选择（`bothy-app` 的 `model/ServicePicker`、`ServiceChoice`）；首次设置不改选择时 key 存进当前选中的配置（新装是 Bothy 的 DeepSeek 预设），换了服务商就用 `provider/create` 新建一个配置（新建即选中）、存它的模型列表，再用 `onboarding/saveModelKey` 检查。已保存的配置显示服务端按地址认出的服务（`ProviderEditState.service`，认不出就是自定义）；余额按服务的 `balance` 显示，认不出服务的配置（中转）对每个有 `relayCheck` 的服务显示检测，界面不再自己认 DeepSeek 的地址。每个模型的输出上限存在 `modelSettings/save` 的 `outputOverride`，留空用服务数据里的默认值（`ModelFacts.requestOutputTokens`），输入框里显示它和上限（`maxOutputTokens`）。

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

- 通用的 `AppServer` 和 Bothy 自己的东西分开：`AppServer` 只有运行时、会话、模型、MCP、计划、A2A、用量、插件内核和协议本身，不知道车机、语音或 Playground。宿主 App 用 `AppServerConfig` 交给它：
  - 名字（握手时的 `serverInfo.name`）；
  - 人设（`Persona`）：助手叫什么，系统提示词开头那句“你是谁、在哪个 App 里”；不给就是通用的“助手”（`Persona.GENERIC`）。提示词和工具说明里模型读到的关于自己的话一律写“你”，不写名字；
  - 简单宿主要的都是普通字段，不用实现接口：自己的工具（`tools`，每次运行按 `Context` 造的 `ToolProvider`，模型经 `search_tools` 找到、以 `mcp__<providerId>__<tool>` 经 `call_tool` 调用）、系统提示里的规则（`rules`，排在 `RunAddOns.systemRules` 和插件规则前面）、带的 skill（`skills`）、每次工具调用前的闸门（`hooks`，`RuntimeHook`）、构建带的模型服务（`model`，`ModelPreset`：地址、模型、密钥、接口类型，是用户的模型列表里的一个档，新装时就用它，用户之后选别的就以用户的为准，密钥不显示、不记日志、不进状态）、新装时的审批模式（`approvals`，默认“每次都问”，没有界面的宿主给“全部允许”；客户端 `run/set` 改的是用户自己的选择，存在 `app_server_approvals` 里、重启后还在，直到宿主调 `AppServer.resetApprovals()` 回到它给的那个），还有给测试和演示的模拟后端：`simulatedModel`（每次运行造一个模型，通常是 `ScriptedModel`：宿主按“用户说 X 就带这些参数调我的工具 T，再用结果拼回答”写脚本，它替宿主走完 `search_tools`、`call_tool`、`finish`（对话里搜到过或“可用工具”列出过的工具直接调，运行说还没加载的先搜一次再调），跳过用户话后面 App 加的说明；不给就是通用的那个，问它是谁就自我介绍，别的只说收到了什么）和 `simulatedBackend`（一启动就用模拟后端，选了模型档就换成真的）。什么调用先问（owner 2026-11-10 定，`ActingToolGate`）：不自称只读的工具（`effectKind` 不是 `READ_ONLY`，默认 `UNKNOWN` 也算；MCP 工具没标 `readOnlyHint`），不管是谁带来的（宿主、配置的 MCP 服务、插件的工具和工具服务），还有 `write_file`、`edit_file`、`exec_js`，调用前先问，用普通的 `RequireConfirmation`，由审批模式回答：“每次都问”每次问，“允许已确认的”同一对话里每个工具问一次，“全部允许”直接跑（App 自己的轮次里闸门直接放行，不走审批的暂停和恢复），“全部拒绝”直接拒。卡片、选项和画的页面不问；`plan_manage` 建、改、暂停、恢复、删计划时问，列计划不问；记忆的记住、修改、忘掉在哪种模式下都不问，“全部拒绝”也照跑（owner 2026-11-10 定：那是助手自己的笔记，用户在设置里能看能删；记忆插件用 `ownToolServer` 说它不算动东西，工具声明的 effect 不改）；服务端其余自带工具（生图、交给别的 agent、操作屏幕的动作、设备工具）按 `effectKind`。读工作区、联网搜索、协议自己的工具和子 agent 工具不问，`web_fetch` 仍按网站问。宿主把会动东西但不用问的工具写进 `unaskedTools`（全名，`effectKind` 照实写）；宿主的钩子照样能让任何调用先问或直接拒，`Deny` 优先。子 agent 问不了人：会动东西的调用只在“全部允许”时跑；别的 agent 的任务（A2A，按“每次都问”跑，第一次审批就结束任务）里由用户的模式回答：“全部允许”就跑，“全部拒绝”就拒；计划在建的时候问过，到点后它的动作不再问（“全部拒绝”时不跑），计划的任务是一轮普通对话，审批一分钟没人答就过期（`HostApprovalsTest` 用协议把四种模式都走一遍，`ActingToolGateTest` 查哪些调用问）。写给第三方的接入说明在 `agent-app-server/README.md`，里面的代码块都抄自示例（和根构建文件的 plugins 块）里 `guide:start`/`guide:end` 之间的代码，`GuideSnippetsTest` 查它们没走样；它给出一个完整的模块构建文件、说明里用到的每个名字在哪个包和模块、跑一轮拿回答的完整客户端、没有界面时用代码回答审批（声明能力、处理函数、拒绝后模型读到什么）、脚本怎么读失败和被拒的结果、测试里开关插件和同一个类里放几个测试方法要注意什么（它们共用第一个方法起的服务端）、记忆和运行时学到的经验存在哪、怎么看模型拿到了哪些工具；
  - 插件（`AppServerPlugin`）：进程启动时起什么（`install`）、答哪些请求（`routes`）、往状态里发布什么（`publish`，多是 `ExtensionSection`）、App 进出前台和悬浮窗来去时做什么，每样都可以不要；
  - 接到运行时上的口子（实验性）：`VoicePort`（播报和语音会话，没有就静音照跑）、`HostModels`（宿主自己跑的模型，比如本机模型）、`RunAddOns`（跟每次运行有关的部分：运行上下文里的话、读 `RunFacts` 的规则和闸门、循环设置、协议规则、远端工具的说明、工具包装、工具调用上“说给用户”的参数、带的 tools.json 目录这次挂不挂（`mountsBundledTools`）、子 agent 类型，以及子 agent 只能查询的工具服务（`queryOnlyInSubagents`：没开「允许子 agent 操作设备」时）；卡片显示前的检查和补充、回复怎么收尾在卡片插件的 `CardHooks`）、跑分的回放检查（`ReplayCheck`：哪些请求必须调用服务、回答还有什么问题）。通用层默认都不加：Bothy 的下单确认、网上核实、天气活动走联网搜索在 `BothyRuns` 里，长回答画页面是它列的 `PagesPlugin(whenToDraw = listOf(PAGES_RULE))`，它的工具和 skill 是 `tools`、`skills`，演示车机的脚本是 `simulatedModel`，车机请求的回放检查是 `BothyReplayCheck`，审批模式照旧每次启动都是“全部允许”）；
  - 模型预置（`ProviderPresets`）：构建时带的模型服务，以及配置文件里宿主自己的段（`sections`，段名对键前缀，比如 Bothy 的 `tts` 读成 `MIMO_TTS_*`；核心不认识任何具体的语音服务）；
  - 工具说给模型的话里要提到的界面位置由插件的参数给：记忆在哪看和改（`MemoryPlugin(page)`）、生成的图片显示在哪（`ImagesPlugin(shownOn)`）；不给就不提（原来的 `HostPlaces` 去掉了）。
  - 自带功能（`BuiltInFeatures`）：写工作区（`write_file`、`edit_file`）、联网（`web_search`、`web_fetch`，以及模型服务自带的搜索）、脚本（`exec_js`，打开后在基础工具页上仍默认关）、子 agent（`spawn_agent`、`wait_agent`、`cancel_agent` 和子 agent 设置）。计划、记忆和画页面是插件（下面），开机和更新后自启由在跑的插件要（`PluginContext.startOnBoot`）；运行时自己从运行里学到的经验是另一回事，`memory/lessons` 一直在核心里。默认都关：一个只带服务端的 App 只给模型协议需要的工具（`search_tools`、`call_tool`、`finish`、`ask`、`ask_async`、`update_todo` 和读工作区的 `list_dir`、`read_file`、`grep`），不会开机自启。关着的功能的工具不进模型的工具表，基础工具页上没有它们的行，也没有它自己的请求（问了回“方法不存在”），子 agent 关着时子 agent 设置是关、类型为空；握手时 `serverInfo.features` 列出开着的（`fileWriting`、`web`、`scripts`、`subagents`，以及插件带来的 `plans`、`androidUse`、`deviceMedia`、`cards`、`memory`），客户端可以据此只给这些出界面。Bothy 全开，它必开的设置插件要开机自启（关了计划也照样自启，唤醒词重启后就能听），并在自己的 manifest 里把 `BootReceiver` 打开，带来这个开关的那次更新也照样自启。
  - 给宿主开发者的两个设置，默认都关：`debugSockets`（本机抽象套接字，见下面的“进程外”）和 `diagnostics`（每次运行留下的诊断文件，只会越积越多直到清除运行数据：每次模型调用的请求和回复 `files/runtime-debug-logs/`、每次运行的事件和回放检查 `files/debug-run-evidence/`、模拟后端的工具检索 `files/mcp-progressive-evidence/`、基准提示的探针 `files/agent-reliability/`、托管搜索的检查 `files/web-search-evidence/`）。回放检查本身在内存里照做；服务端要用的东西不受影响：会话和它们的事件日志（`no_backup/debug-session-event-log/`，历史和轨迹从这读）、模型读写的工作区（`files/runtime-debug-workspace/`）、图片库（画的页面归页面插件）。Bothy 两个都开（套接字只在 debug 包里）。
  - 插件（`plugins`，`AppServerPlugin`）：宿主带的每个插件有一个稳定的 `id` 和一个 `apply(ctx)`。它要加的东西都对 `PluginContext` 说：自己的请求、状态分区、带来的服务端功能、模型服务适配、每次运行的工具和工具包装、每轮开始前换输入、App 到前台和助手上屏时做的事、给别的插件用的服务，还有一个随插件停下而取消的协程范围。插件停下时这些都自动撤掉（它自己另外占着的东西在 `onStop` 里放）。`PluginHost` 管哪些开着、哪些在跑：一个插件开着并且它需要的插件（`needs`）都在跑才跑，停一个会先停掉需要它的；开关存在设备上（默认开），进程启动时按存下的来，`required` 的关不掉。服务端其余的代码只读一份整体替换的快照（`AppHost.additions`），所以一次运行、一个请求看到的是同一时刻的插件。客户端用 `plugins/list`、`plugins/set` 看和改，插件起停后每个客户端收到 `server/features`；工具、规则这类下一轮就变，协议请求当场就有或没有。插件关着时，找到过它的工具的对话不会把这些工具忘掉（这是运行时的：提供方不在的那几轮，调用按 `TOOL_UNAVAILABLE` 拒，用运行时的话说明；提供方回来后，从下一轮起每个对话（包括老的）都直接调到真的工具，不用再搜；进程重启后，对话也直接调之前几轮里见过的工具，`ToolServerComesBackTest`）。通用层和它的协议不导入任何插件，也不认识任何一家模型服务；模型服务（DeepSeek 在内）是运行时仓发布的 `agent-app-models`，它的插件从 manifest 自动加载，Bothy 不列出。卡片和选项是 `agent-app-cards` 的 `CardsPlugin`：插件还可以声明它带来的服务端功能（握手时告诉客户端）、给每次运行加工具（能看到这次运行已有的后端工具）、包一层后端工具、在每轮开始前把用户的输入换成它要的（选了第几项就换成那一项登记的调用）。插件要用到服务端里不给宿主的东西时，用标了 `@AppServerPluginApi` 的那些。第三、第四个是操作屏幕（`agent-app-android-use` 的 `AndroidUsePlugin`）和设备工具（`agent-app-device` 的 `DeviceToolsPlugin`）：它们连同运行时的 `agent-android-use`、`agent-device-capabilities` 一起只进列了它们的宿主，没列的宿主合并到的 manifest 里没有无障碍服务、日历和通知权限（`agent-app-sample` 每次构建都检查这一点）。为它们内核多了两样：`toolServers`（插件的工具服务，和 MCP 服务列在一起、各有开关，开着的每次运行像配置的服务一样挂上；设备的日历、通知、媒体就是三个这样的服务，开关还存在原来的键下）和 `runTools`（按这次运行的情况给的工具，比如模型能不能看图，挂在所有工具之后、不包装；操作屏幕的工具这样给，要它的技能只在有这个工具服务的运行里读，宿主的技能用 `RuntimeSkill.requiresToolServer` 指名，Bothy 的写 `android_use`）。运行的上下文里“前台是哪个 App”、媒体工具暂停时找前台 App，都向在跑的插件要 `ForegroundApps` 服务（操作屏幕提供），没有就不说。第五个是画的页面（`agent-app-pages` 的 `PagesPlugin`），服务端自己的功能里第一个拆出核心的（2026-11-10）：它给主 agent 用服务模型、界面会报前后台的运行挂 `show_html`，答 `pages/read`，页面存在对话的 `files/drawn-pages/` 下。为它内核又多了几样：`contextLines`（每次运行上下文里的话，排在宿主的 `RunAddOns.contextLines` 之后；对话页在前台时给宿主写的什么时候画，Bothy 给的是 `PAGES_RULE`，不在前台时说这次用文字答）、`ownToolServer`（插件自己的工具服务名，插件和插件包的 MCP 服务不会挂到这个名字下；插件还可以按调用说它算不算动东西的操作，画页面从不问）、`onClearData`（删对话、清运行数据时插件删自己存的东西，`DataClearing`；插件关着时内核改调它的 `AppServerPlugin.clearWhileOff`，所以关着时删对话、清数据也不留下它的东西，画的页面和记忆本都这样）和 `offers`（问某个服务端功能现在有没有，页面说明里提不提卡片看它）。`RunFacts` 多了 `appInFront`（App 自己的界面在不在前台，从没说过是 null）和 `hostModel`（这次是不是宿主自己的模型），去掉了 `drawsPages`。第六个是记忆（`agent-app-memory` 的 `MemoryPlugin`），取代了 `BuiltInFeatures.memory`：插件起来时 `UserMemoryStore` 在自己的线程里读记忆本，停下时放掉记忆本、清空记忆分区，还拿着这个工具的运行再写会被拒（`MemoryRefusal.Off`）；它答 `memory/setEnabled|write|delete|clear`（`memory/lessons` 留在核心的 `LessonRoutes`），用户的记忆开关开着时给运行挂 `user_memory`，并在每轮前给出记住的事。为它内核多了 `contextProviders`（每轮前运行时要问的上下文提供方，排在计划的事实之后，共用一个时间预算）；清运行数据时点名带上它（`data/clear {also: ["memory"]}`，按插件 id 列出还要清哪些插件自己存的东西，`DataClearing.alsoClears`）由它的 `onClearData` 删记忆本。记忆在哪看和改由 `MemoryPlugin(page)` 给（Bothy 是“设置 → 记忆”）。什么话不能记（密码、验证码、长号码）是核心的 `sensitiveIn`，计划存原话时也用它。Bothy 的实时语音和陪聊读 `UserMemoryStore.inUse`、`userMemoryEnabled`，插件关着时就是空的、关的。第七个是生图（`agent-app-images` 的 `ImagesPlugin`）：它答 `imageModel/read|save|models|try`，生图模型设好并打开时给每次运行挂 `image_generate`（挂在别的插件工具之后，和原来同一个位置），把每个请求正在画的图放到 `PictureBoard`、发布到卡片分区的 `offerPictures`（客户端模式按任务 id 读）；停下时清空这块板和这个分区，停下后还在画的运行也不再往板上放（插件造的工具会问它还在不在跑），调试广播 `ImageModelBroadcastReceiver`（类名没变，Bothy 仍在 debug manifest 里声明）插件关着时什么都不做。图片库本身（`files/generated-images/`：`sessionPicturesDir`、`generatedImageFile`、`imageExtension`，删对话时删它的文件夹，清运行数据时整个删）留在核心，因为 A2A 委托的报告也把图存在这里，卡片插件也读。第八个是计划（`agent-app-plans` 的 `PlansPlugin`，2026-11-10）：`plan_manage`（建、改、暂停、恢复、删计划时问，列计划不问，`ownToolServer(..., mainAgentOnly = true)` 让子 agent 调它被拒、也不算用了服务）、每轮前的今天日期和进行中的计划（`contextProviders`）、计划页的请求和分区、`files/plans/` 的计划本、下一个到点计划的闹钟（`PlanAlarmReceiver`，在插件模块自己的 manifest 里，类名没变，之前设下的闹钟照样找得到）、盯 MCP 服务的状态、`automation-policy.json`（插件模块的 assets），以及开机自启和改时间、改时区后重新检查（`startOnBoot`）。到点时用宿主的声音响一下（`voice`）、在助手的弹窗里显示，提醒照原话念、任务交给 agent 跑一轮，都经 `PluginContext.deliver`（`Delivery`：和委托报告、子 agent 报告排同一个队，等设备空下来、结果读完，最多一分钟；还忙就塞进正在跑的那轮，那轮先结束就自己成一轮或念出来）；动作按当时的审批模式（`approvalMode`）在策略内不问就跑，“全部拒绝”时不跑。宿主对工具的说明（`RunAddOns.describeTool`）经 `PluginContext.describeTool` 也给到计划工具。关掉时闹钟和盯的状态都放掉、分区清空、还拿着计划工具的运行再调就拒（什么也不记）；已经到点交出去的照样送到，已经在跑的动作跑完照样记进最近触发；再打开时按计划本重新设上。计划的事实排在记忆之前，Bothy 把 `PlansPlugin` 列在 `MemoryPlugin` 前面。插件和宿主自己的代码不碰核心的全局对象（`RuntimeSession`、`AppHost`、`Presenter`），用内核给的入口（`PluginContext` 上，都标 `@AppServerPluginApi`）：`turns`（屏幕上那个对话的轮次：开始、替换正在跑的、停下、跟着看运行时的状态、开新对话、安静久了才开新对话、记下别处答了的一轮、最近几轮、在对话里加一行 App 自己的话）、`speech`（用户说的话加进正在跑的那轮或开一轮、抢先说的确认、试听、让正在跑的那轮不出声）、`assistant`（屏幕上的助手：唤醒、卡片、起来，给它发请求和通知）和 `request`（像客户端一样发协议请求，核心和在跑插件的路由在主线程上答：选模型、存密钥、MCP 服务、新对话、清运行数据；服务端还没起来就先起来）。Bothy 自己的代码经 `ServerLink`（它的语音插件起来时拿到的上下文）用这些入口。核心不认得任何插件：插件的工具服务是什么由插件自己说（`ownToolServer`：调用问不问、是不是只给主 agent、算不算用了服务、是不是子 agent 只能查询的设备，卡片的 `app_ui` 和操作屏幕的 `android_use` 也这样说），状态按分区合并（运行时的分区取运行时的，别的分区不点名照发），插件在助手上显示的东西都经 `assistant`；计划到点的提示音是 `VoicePort.chime`。
  - 插件在服务端运行时也能加和删（`PluginHost.add`、`replace`、`remove`；起不来的那个不再自动重试，直到被重新打开或重新加），插件还能给运行加技能（`PluginSkill`，和宿主的技能一起列出、一样开关）、系统规则（排在宿主自己的之后，前面一句说明插件的话不能放宽用户允许的范围，合计不超过 3 KiB，超出的整块不放）和 MCP 服务（`PluginMcpServer`，和配置的服务一样挂到每次运行，名字和别的服务不冲突；`askBeforeActions` 的服务里没标只读的工具每次都先问用户，除了“全部拒绝”（直接拒）哪种模式都问（`RequireConfirmation(alwaysAsk = true)`，同意不记住，子 agent 里直接拒；审批项和 `approval/request` 带 `alwaysAsk`，界面就不给“总是允许”）。插件经 `tools(askBeforeActions = true)` 给运行的工具（宿主之外来的，比如插件包的）也一样：每个提供方挂在这一轮别的工具服务都没用的名字下（它的 id，被占了加数字），提供方没描述成只读（`effectKind` READ_ONLY）的工具每次先问。
  - 插件包（`.agentplugin`）：不带编译代码、用户自己下载安装的插件。格式和检查在 `agent-app-server` 的 `packages/`：一个 zip，`plugin.json`（format 1，反向域名 id、语义化版本、要的网络主机、密钥和宿主工具、MCP 服务、技能、规则、图标、用脚本写的工具）加 `signature.json`（其余每个文件的 SHA-256，签名包还有发布者的 ECDSA P-256 公钥和对这份清单的签名；公钥只收命名 P-256 曲线、未压缩点的那一种 91 字节编码）。写盘之前在内存里整包检查：解开后不超过 5 MB、200 个文件，路径只能是包内的普通相对路径，只收 markdown、json（UTF-8）和 png，`scripts/` 下只收 JavaScript（每个不超过 128 KiB），摘要和签名对得上，清单严格（不认识的格式、成员、id、版本都拒），清单点名的文件在且合规（SKILL.md 按运行时的读法检查，`rules.md` 不超过 20 行、2,000 字，工具的脚本在，没有工具指名的脚本拒）。带编译代码（`code/`、`code`、`nativeCode`）的包留给以后的步骤，现在直接拒。MCP 服务的主机必须在它的 `network` 里，走 HTTPS（只有本机回环可以 HTTP），密钥 `${name}` 只能出现在请求头的值里或作为整个查询参数的值。包可以带用 JavaScript 写的工具：`tools` 里每个工具有名字、说明、`inputSchema`（只能用服务端会检查的那部分 JSON Schema）、`script`（`scripts/` 下的 `.js`，没有工具指名的脚本拒）、`readOnly`，以及可选的 `timeoutSeconds`（1 到 120 的整数秒，不写是 10，其他写法按 `BAD_TOOL` 拒），`permissions.hostTools` 列出脚本要调的宿主工具（只能和 `tools` 一起出现），工具最多 20 个。每次调用（`ScriptRunner`）先按 `inputSchema` 检查参数，再在运行时自带的沙箱（`agent-exec-js` 的 `ZiplineExecEngine`，QuickJS）里跑：16 MiB 内存、20 次宿主调用（这些固定，包改不了），时间只有一个限制、连宿主调用在内：工具自己要的秒数，但不超过宿主的上限 `AppServerConfig.maxScriptToolSeconds`（默认 30）；要得比上限多的工具不拒，按上限跑；超时失败时告诉模型生效的秒数。`PackageTool` 同时给出工具要的秒数和生效的秒数，更新里工具要的时间比原来多，也算进 `PackageChange.tools` 给人看。`run(input)` 的返回值按 JSON 交回（最多 64 KiB），抛错就是工具失败。脚本碰宿主只有三个函数：`host.http`（只到包点名的主机，HTTPS，本机才能 HTTP，不跟随重定向，发 64 KiB、读 256 KiB，用这次调用剩下的时间；`${name}` 密钥由宿主在发出时填进请求头的值或整个查询参数值，回答里重复出现的密钥换回占位符；互联网上的名字解析到本机或局域网地址的拒）、`host.tool`（`hostTools` 里点名、并且自称只读的工具，直接在它的提供方上调用，不再问）、`host.log`（调用结束时写进应用日志，标明是哪个包）。被拒的宿主调用是脚本能接住的错误。运行的脚本就是这次启动检查过的那些字节。
  - 安装分两步：`plugins/install`（url、file 或 data 三选一；下载只走 HTTPS，不跟随跳到 HTTP 的重定向）取包、检查，回答它是什么、要什么（每个工具标明会不会先问），更新时还说比已装版本多要了什么（主机、密钥、宿主工具、服务、技能，以及新加的、不再先问的或要的时间变多的工具）；`plugins/confirm`（15 分钟内）解到 `files/app_server/plugins/<id>/<version>/`，密钥按 `McpSecrets` 的做法明文（不加密）存在 no-backup 目录的 `app_server/plugin-secrets/` 下，注册到插件内核并启动。信任由宿主定：`AppServerConfig.trustedPublishers`（公钥）的包确认一次就装，其他签名包和未签名包（只有 `allowUnsignedPackages` 时才收，默认不收）还要 `acknowledgeUntrusted`。Bothy 信任构建时用 `-PbothyTrustedPluginPublishers` 给的公钥，只在 debug 构建里收未签名包。同一个 id 第一次安装时记下公钥，之后的版本必须同一把钥签、不能更旧（同版本可以重装，用来修复）；新版本起来之前旧版本留在盘上，起不来就换回去。每次启动都重新检查文件，不过的不启动、在列表里显示为损坏；记录（`installed.json`）读不出来的包也显示为损坏，文件和密钥留着，删掉之前这个 id 不能再装。启动时只清理安装没做完留下的东西，而且只看名字是包 id 的目录。`plugins/remove` 停掉并删掉它的文件、密钥和技能开关。被拒的包或步骤回 `REJECTED`，data 是 `PackageRefusal`。运行时 `PackagePlugin` 只给清单里写的东西：技能叫 `<id>:<技能>`，头部和正文前一行标明来自哪个包，按需读取（只有受信任发布者的包要求时才每次放进提示词）；规则合成一块，同样标明出处；MCP 服务只挂到允许的主机上，名字是 `<id 最后一段>_<服务 id>`，用它自己的密钥，连接不跟随重定向，没标只读的工具每次先问；发布者不受信任时不认远端服务的 `readOnlyHint`，每个工具都先问。脚本工具合成一个工具服务（`ScriptToolProvider`，名字是 `<id 最后一段>`，被占了加数字），经 `tools(askBeforeActions = true)` 交给内核：内核让它的名字跟这一轮的其他工具服务都不重，并且只有提供方描述成只读（`effectKind` READ_ONLY）的工具才不问；包里的工具只有自称 `readOnly` 而且发布者受信任时才描述成只读，其余每次运行前先问（「每次都问」「批准过的不再问」「全部允许」都是每次问，同意不记住，「全部拒绝」直接拒）。`host.tool` 能调的是这一轮交给插件工具的后端提供方（配置的 MCP 服务、自带示例工具、设备工具插件在跑时开着的设备日历、通知、媒体；不含宿主经 `RunAddOns` 给的那些），去掉各插件自己的 MCP 服务，实际上其中自称只读的只有配置的 MCP 服务里标了 `readOnlyHint` 的工具；运行时的原生工具（`web_search`、`web_fetch`、文件）不是提供方，调不到。插件包加不了钩子、输入改写和工具包装。
  - 模型服务适配（`PluginContext.providerAdapter`，`ProviderAdapter`）：核心只懂三种线协议（OpenAI Chat Completions、Responses、Anthropic Messages），不认识任何一家服务；某家服务的接入地址、模型列表、每个模型的参数（上下文窗口、输出上限、能不能看图、调工具、推理和推理档位）、价格（币种和日期）、错误怎么归类，以及聊天之外的东西——联网查找（搜索和抓页的 API）、运行里发它自己的历史更新（DeepSeek 的 system 更新和 `tool_addition`）、余额和中转检测（`services/balance`、`services/check`、`services/checked`）、它的各个配置共用一把 key——都由适配给。运行时仓发布的 `agent-app-models` 带 DeepSeek、OpenAI、Anthropic、Gemini 等十三家的适配（数据在它的 `services.json`，每个值都注明官方页面和日期），插件从它的 manifest 自动加载；Bothy 依赖它，不列出。宿主可以用 `AppServerConfig.modelFacts`、`modelPrices` 覆盖，用户自己在设置里填的窗口、看图开关和价格（带币种，美元或人民币）又优先于宿主。有国内、国际两份价目的服务按模型实际调用的地址取价。已保存的 key 只发往这个配置自己保存的地址，或者之前带着手输 key 检测通过、服务端记下的中转地址；表单里改了地址又没重新输入 key 时，余额、检测、拉模型列表和保存都不发 key，提示重新输入（`MODEL_KEY_TYPE_AGAIN`）。
  - 额外信任根（`extraTrustRoots`）：系统根证书之外，服务端所有 HTTPS 还信任的根（PEM，宿主的 raw 资源）；默认不加。Bothy 给的是部分车机系统证书库里缺的那几张。设 A2A 配置和生图模型的两个调试广播（`A2aProfileBroadcastReceiver` 在 A2A 插件里，`ImageModelBroadcastReceiver` 在生图插件里，插件关着时都什么也不做）只有 Bothy 在自己 debug 包的 manifest（`bothy-server/src/debug`）里声明，别的宿主合并不到。
  - A2A 是插件（`agent-app-a2a` 的 `A2aPlugin(host)`，2026-11-10 拆出核心，运行时的 `agent-a2a`（直连客户端和官方 SDK 客户端都在里面，官方 SDK 由插件自己加依赖）跟它走，核心不再带）：用户自己的 agent 的配置（`A2aProfiles`，`a2a` 分区和 `a2a/profile/*`；设置不管插件跑不跑都存，客户端只在跑时建）、App 自己的运行里每个启用的配置一个交托工具（`runTools`，子 agent 和编辑器 ACP 的运行里没有，`RunFacts.editor`；`ownToolServer("experimental_a2a*", mainAgentOnly = true)`，按 `effectKind` 问）、点名才发的闸门（`runHooks`）、交出去的任务进展（`contextLines`）、跟进委托直到有结果再交回对话（`A2aDelegations`，`PluginContext.deliver`）、技能的 `a2a` 段（`skillSections`，宿主的技能用 `RuntimeSkill.requiresSection` 要它）、报告轮次的标题（`turnLabel`）、设备上自己开的一轮把焦点拿回来（`onLocalTurn`）。别的客户端交来的任务经 `PluginContext.outsideTurns` 跑成单独的对话，用户的审批模式回答会动东西的调用。关掉时：端点关闭、正在跑的外来任务停下（调用方的连接断开）；客户端模式正在跑的任务取消、客户端关闭；各配置的客户端关闭；委托不再跟进，没结束的留在 `no_backup/a2a-delegations.json`，再打开时像重启后一样接着跟；已经交给对话的报告照样送到；分区清空。宿主的设置（`A2aHost`）：本机的 A2A 端点（`127.0.0.1:8766/a2a`）开不开，别的客户端交来的请求里宿主自己的命令（`intercept`），别的客户端的任务开始时宿主做什么（`inboundStarted`），交给别的 agent 的任务把来源写成什么（`contextSource`）；不给就不开端点（也不答 `a2a/server/token`）、什么都不弹，来源写 `device`。设备上所有 App 共用一个回环地址，所以装着 Bothy 的设备上，在别的 App 里也能看到 8766 有人在听，那是 Bothy 的端点。Bothy 开端点，外部任务一来就弹出卡片，认 `carOverlay=show|hide`、`carOverlayOnly=true`，来源写 `car`（中转那边已配对的 Bot 认这个名字）。
- `BothyServer`（公开）就是 Bothy 的这份配置：人设是小博，插件是语音（含唤醒、实时、陪聊、Jev 工具检索）、本机模型、检查、Playground 和 Bothy 自己的设置页（它要开机自启），再加上通用的卡片、A2A（`A2aPlugin(BothyA2a)`，交托工具和以前一样排在插件运行工具的最前面）、画页面（带它的 `PAGES_RULE`）、计划、记忆和生图插件（模型服务、操作屏幕和设备工具由 `agent-app-models`、`agent-android-use`、`agent-device-capabilities` 的 manifest 带来，不在列表里，排在列出的插件后面，id 还是 `models`、`android-use`、`device-tools`），口子是 `BothyVoice`、`BothyModels`、`BothyRuns`，配置文件多读一个 `tts` 段，记忆页在“设置 → 记忆”（`MemoryPlugin(page)`），图片显示在“屏幕的悬浮窗上”（`ImagesPlugin(shownOn)`）。对外仍是 `install`（进程启动时由 `BothyApplication` 调）、`connect`（给一个进程内客户端）、`wakeUp`（没有前端时起状态来源，比如唤醒词、计划）。界面语言变化走协议的 `app/languageChanged`。
- `agent-app-server` 的公开面分三层，清单在运行时仓的 `agent-app-server/api/agent-app-server-levels.txt`，每行写着层级，那里的 `apiCheck` 比对，层级变了也要重写清单：`host`（宿主用的：指南里点名的 `AppServer`、`AppServerConfig`、`Persona`、`ModelPreset`、`BuiltInFeatures`、`ScriptedModel`、`RuntimeSkill`、`AppServerPlugin`、`PluginContext` 和它们提到的小类型，宿主可以实现的 `HostModels`、`RunAddOns`、`ReplayCheck`、`VoicePort`、`ProviderAdapter` 等，manifest 要写的 `BootReceiver`）、`plugin`（插件模块还要用的，标 `@AppServerPluginApi`，插件模块和 Bothy 用编译选项 opt-in；`PluginContext` 上指南没讲的成员也在这一层）、`test`（只给别的模块的测试用，标 `@InternalAppServerApi`）；其余是 `internal`。`RuntimeSession`、`Presenter` 是 `internal`。测试跟着被测的代码走：核心和各插件自己的逻辑在各自模块里测（同模块的测试看得见 `internal`），用模块自己的小夹具（核心的在 `agent-app-server/src/test/assets/test-host/`：带工作流清单和分节的 skill、子 agent 类型；A2A 的预置配置在 `agent-app-a2a/src/test/assets/`），不拷 Bothy 的 assets；`bothy-server` 的测试只测 Bothy 自己的东西（它的人设、skill 和类型的内容、规则、语音、连接应用）。别的模块的测试要把服务端放进某个状态，只经一个测试入口 `AppServerTesting`：`install` 装宿主的 `AppServerConfig`（Bothy 的测试装 `bothyConfig()`），`running` 装运行中插件的附加，`completedTurn`、`noteToolCall` 造宿主语音收到的事件，`saveMcpServer`、`deleteMcpServer`、`switchMcpProvider` 像人在 MCP 页那样设置，`prompt` 读一次运行的模型读到的规则和带上的 skill，`requestsAnswered` 列出服务端答的请求（Bothy 拿来对它的协议清单）；插件模块的测试另用插件内核 `PluginHost` 跑自己的插件。测试层只剩这个入口、插件内核（`PluginHost` 和它给的 `PluginAdditions` 及其中的类型、`PluginSwitches`）和 Bothy 的样例评测包要对上的提示词哈希；没有哪个模块的主代码 opt-in `@InternalAppServerApi`。
- 别的 App（手机上的独立 agent、别的场景的快速接入）用同一个 `AppServer`，配自己的插件，或者一个都不配；运行时仓的 `samples/agent-app-sample` 就是一个都不配的最小例子（一个界面，模拟模型，通用人设）。
- 运行的场景（`AppServerConfig.scene`）默认 `app`：运行时按场景存学到的经验、筛选写了 `scenes` 的 skill、放工作区上下文。Bothy 沿用一直以来的 `debug-console`，设备上学到的经验和语音 skill 的场景都在它下面。
- 通用层存的东西用自己的名字：偏好设置是 `app_server_*`（不和宿主自己的撞名），会话记录在工作区的 `sessions` 目录（模型读的是 `/sessions/index.json`）。通用层不读、也不搬早先名字下的东西。Bothy v0.3.0（及更早）把这些放在调试台的名字下（`debug_*` 偏好设置、`debug-sessions` 会话目录），由 Bothy 自己搬：`BothyServer.install` 在启动通用服务端之前先跑 `StoredNames`，把清单里的每个偏好文件并到对应的 `app_server_*` 名字下（两边都有的键以新名字的为准），会话目录逐个移进 `sessions`（新名字下已有的会话保留新的），搬完删掉旧文件和旧目录，模型 key、对话记录都不会留在没人读的地方；两边的格式相同，什么都不丢。搬失败的只记日志，下次启动再搬，不影响启动。v0.3.0 里做完了首次设置、模型设置也搬过来的，服务端起来后检查一次模型能不能跑：能跑就不再显示设置，key 没带过来就重新走一遍设置。Bothy 自己的偏好（`debug_voice`、`debug_onboarding` 等）沿用原名，不用搬。工作区根目录 `runtime-debug-workspace`（`workspace.json` 的挂载源，运行时的存储按它认）、运行证据和事件日志的目录沿用原名。
- 卡片和选项工具的服务名是 `app_ui`（模型看到 `mcp__app_ui__show_card`）。宿主自带的示例工具（`assets/mcp/tools.json`）是什么由宿主说：Bothy 的是演示车机，在它的 `deviceServers` 里；通用层不把它们当设备。服务端自己的工具（卡片、操作屏幕）和在跑的插件说只给主 agent 的工具服务（计划、委托给别的 agent）以外，`isServiceTool` 都认作服务的工具。
- 通用层自己带运行时工作区的布局（`runtime-workspace/workspace.json`、工作区的 `AGENTS.md`）；计划自动执行的安全策略（`automation-policy.json`）在计划插件的模块里；宿主没带的 assets（预置的 A2A 配置、skill、子 agent、模型预置）一律当作没有，不报错。
- 状态来源（`StateSources`，接上运行时、MCP、A2A 等）在第一个要状态的客户端连上时才启动；不要状态的客户端（`capabilities.state = false`）连上不会启动任何东西。
- `ServerHub`：发布状态（`StatePublisher` 算出条目和分区的变化）、管连接、广播通知；`ServerConnection` 一条连接一个，负责握手、审批请求的收发。
- 请求表：核心的会话、轮次、模型、连接在 `ServerRoutes`；其余每个功能区一个 `RouteModule`，自己登记它答的方法。通用的几个（`LessonRoutes`、`McpRoutes`、`ModelSettingsRoutes`……）在 `coreModules`，插件的由插件的 `routes` 给出；新功能区加一个模块，不改核心。
- `StateSources` 订阅运行时和通用功能区的流（会话、计划、用量、子 agent……）合成 `ServerState`，再让每个插件往里发布自己的分区（卡片、操作屏幕、记忆、正在画的图……）。
- 悬浮窗也是普通客户端：`bothy-app` 的 `HostConnection` 在进程启动时连上，声明 `capabilities.presentation = true`、`state = false`。服务端通过它发请求 `presentation/ensureRunning|start|card|wake`（唤醒的来源是宿主自己的词，Bothy 是 `WakeOrigin`）和 `androidUse/begin|point|drag|hide`（指针到位、截图前藏好才回），发通知 `presentation/planDue|planRan`、`androidUse/reveal`；它用 `presentation/state` 通知报悬浮窗是否就绪、用户读到什么时候、控制台是否在前台，服务端缓存着同步查。插件经 `PluginContext.assistant`（`Assistant`）用它：宿主自己的请求用 `assistant.ask` 发，比如 Bothy 调试吉祥物动作的 `presentation/previewMascot`（`BothyPresentationRequests`），计划插件的弹窗和操作屏幕插件的指针也经 `assistant.tell`、`assistant.ask` 发。没有这样的客户端时，这些请求一律答“不在屏幕上”，服务端照常运行。

## 传输

- **进程内**：`BothyServer.connect()` 给出一对内存通道，三套前端各连一个。
- **进程外**：本机抽象套接字，名字是宿主的 `AppServerConfig.name`（Bothy 是 `bothy`），只在宿主打开 `AppServerConfig.debugSockets` 时开（默认关；Bothy 只在 debug 包里开，示例 App 不开）；对端 UID 只认本 App、shell（adb）和 root，别的 App 连上来直接断开。PC 上 `adb forward tcp:8791 localabstract:bothy` 后按行收发 JSON；读不出的请求行回一个错误（能读出 id 就带上），不会让对方一直等。
- **ACP**：同一个开关还在 `localabstract:<name>-acp`（Bothy 是 `bothy-acp`）上开一个 Agent Client Protocol 入口（`AcpSocketServer`，用运行时的 `agent-acp`），连接规则和上面一样。每一轮在开始时按当时的模型、工具服务、插件和设置建一个运行时（`RuntimePerTurn`，和 App 自己的每一轮一样），所以中途关掉的插件下一轮就没了、打开的下一轮就有；建会话、列会话、加载会话和设模式用每条连接一个、不跑轮次的运行时，同一个存储；一轮在跑时，打断、插话和编辑器设的模式都交给这一轮的运行时（`AcpTurnsTest`）。会话单独存放，不出现在 App 的会话列表里；问不问的规则和 App 里一样（插件里会动东西的工具哪种模式都问）。
- `workspace/apps/android/tools/bothy-protocol/`：Node 写的最小客户端，`state`、`say`、`watch`、`call` 四个命令（`call` 不带方法时列出 schema 里的全部请求），`--approve` 在终端里答审批；`node --test` 跑它自己的测试。

## 客户端

- `agent-app-protocol` 的 `ServerClient`：发请求、等响应（挂起函数），处理审批请求；把快照和通知归并成 `ServerState`（`StateFlow`）。
- `bothy-app` 里：`FrontEndClient`（控制台和手机，握手后才发请求）、`OverlayConnection`（悬浮窗的状态）、`HostConnection`（悬浮窗的呈现和 App 的通知，不要状态）、`AppClient`（广播接收器等一次性调用）；Compose 里用 `LocalServer` 拿当前状态和发请求。
- 两边共用的纯函数（a2ui 卡片和选项、提问卡片、计划触发条件的文字、HTML 预览、轨迹的时间线、搜索、时长和检查器的标签页、分页……）在 `agent-app-protocol` 的 `view` 包；Bothy 悬浮卡片的投影（阶段、尺寸、可点的回复、计划行、A2A 客户端模式的卡片）、行程的出行方式和播报的进度行（`TTS:`）在 `bothy-protocol` 的 `view` 包，服务端和界面算出来的一样。

## 做法上的约束

- 前端不碰 SDK 和服务端的 `internal`，只用协议和 `BothyServer` 的 `install`、`connect`。
- 服务端不碰 Compose 和 `bothy-app`；要界面说的话，服务端给数据、枚举或 `Finding`，界面自己映射成字符串资源（见上文“下发的是数据，不是文案”）。
- 新的设置页：在 `bothy-protocol` 加视图类型和 `XxxRequests`，在服务端加一个 `RouteModule`，通用的放进 `coreModules`，只有 Bothy 用的放进对应插件的 `routes`；页面用 `LocalServer` 读写。
- 通用层（`AppServer`、`ServerHub`、`StateSources`、运行时）不引用插件的代码；插件要什么，通过 `AppServerPlugin` 和三个口子拿。
- 测试跟着代码走：通用服务端的测试在 `agent-app-server`，插件的测试和跑在 Bothy 的 assets、插件上的那部分核心测试在 `bothy-server`，界面上的文字、布局、投影的测试在 `bothy-app`（`*UiTest`），输入用协议类型构造。

## 风险

- **语音和悬浮窗耦合最深**：唤醒、按住说话、播报和悬浮窗卡片互相等待。语音逻辑在服务端，悬浮窗按语音状态和 `voice/*` 请求驱动；改这一块要在真机上把唤醒、按住说话、计划播报都过一遍。
- **条目对比的开销**：流式输出时每秒几十次变化；对比只做当前会话，文字追加走 `item/delta`。轨迹记录不带事件全文（一次运行的每个完整提示词会跟着每次变化发一遍），检查器打开 Raw 页时才用 `trajectory/raw` 取。小博在对话里画的页面（`show_html`，几百 KB 也可能）同样不进条目：页面插件存在会话的 `drawn-pages/` 目录下，条目只带引用，界面显示时用 `pages/read` 取。播报响度在说话时每秒进状态 50 次，只有悬浮窗用，控制台收到后先去掉再比，不跟着重画。
- **按住说话的时序**：按下时请求还没回就已经开始收手势，所以松手的请求只结束“由按住开启的”会话，服务端据此判断，不会误停别处开的会话。
- **字符串两份**：服务端自己说的话和界面措辞的话分开放；两边都用的同一条字符串各留一份，改一边要记得另一边。
