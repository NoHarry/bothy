# Agent relay：把设备任务交给用户自己的 Grok Bot

设备不直接访问 Grok。relay 部署在一台服务器上，对设备提供 A2A 0.3 JSON-RPC 接口，对每个用户自己的 Grok Bot 调用其 Routine 的 Webhook；Grok Bot 做完后调用 relay 的 MCP 工具 `car_task_report` 汇报（Grok 账号里接入的自定义 MCP 服务器），设备再查询任务拿到结果。Node.js 20+，不依赖 npm 包。

谁都可以用（没有邀请码）：设备在「连接应用」里点 Grok Bot → 连接，屏幕显示配对码和二维码；手机扫码打开 `/connect`，复制一段话发给自己的 Grok Bot，Bot 自己建例行任务、调 `/connect/claim` 完成配对、接入 car-relay 连接器。用户只需批准 Bot 执行一次命令，并在 Auto-review 里放行两个工具。服务器所有者自己的 Grok 也可以照旧写在 `relay.env` 里（下称 owner）。

```text
设备 ──A2A（Bearer）──▶ relay ──Webhook（Bearer）──▶ Grok Bot Routine
  ▲                       ▲                              │
  └──── tasks/get 轮询 ───┘ ◀── MCP car_task_report ──────┘
```

用 MCP 而不用 `curl`：Bot 在云端电脑跑终端命令要经过 Grok 的审批（无人值守时约 10 分钟后过期，命令不会执行）；MCP 工具调用是结构化的，Auto-review 里只需放行这两个工具。

## 接口

| 路径 | 调用方 | 说明 |
|---|---|---|
| `GET /.well-known/agent-card.json` | 任何人 | Agent Card，不含秘密；`streaming:false` |
| `POST /a2a` | 设备，`Authorization: Bearer <配对得到的设备令牌，或 RELAY_CLIENT_TOKENS 之一>` | `message/send`、`tasks/get`、`tasks/cancel`；`message/stream` 返回 -32004；还没配对完成的设备 403 |
| `POST /mcp` | Grok 的自定义 MCP 连接器，`Authorization: Bearer <配对得到的 MCP 令牌，或 RELAY_MCP_TOKENS 之一>` | MCP（Streamable HTTP，只回 JSON）：`car_task_get`、`car_task_report`，以及 `car_list_connections`、`car_disconnect`（列出 / 解除这个用户的设备，后两个不放进 Auto-review 规则，每次由用户确认）；只看得到这个用户的任务 |
| `GET /connect` | 手机浏览器 | 配对页：一段发给 Bot 的话（含配对码、例行任务指令、`/connect/claim` 命令），手动配置作为备用 |
| `POST /connect/devices` | 设备，无令牌 | 注册：`{label}` → `{device_id, token, code, code_expires_at, connect_url}`；按来源地址每小时限 `RELAY_CONNECT_PER_HOUR` 次 |
| `GET` / `DELETE /connect/device`，`POST /connect/device/code`、`POST /connect/device/unpair` | 设备，设备令牌 | 配对状态 `{state: pending|connected, verified}`；删除这台设备；换一个配对码 |
| `POST /connect/claim` | Bot（或手动配置页） | `{code, webhook_url, webhook_key}` → `{mcp_url, mcp_token, connector_name, device_label}`；带 `mcp_token` 时把设备加到已有连接（owner 的 `RELAY_MCP_TOKENS` 也行）；按来源地址限次 |
| `POST /connect/test`、`GET /connect/test/<任务 ID>` | 手动配置页，MCP 令牌 | 让这个用户的 Grok 走一遍测试任务（设备上的「测试连接」直接走 `/a2a`） |
| `POST /connect/device/login`、`POST /connect/device/logout` | 设备，设备令牌 | 用账号登录（`{name, password, register?}` → `{account, state, account_url}`）或退出；见下文「账号」 |
| `GET /account` 及 `/account/…` | 手机浏览器，账号会话 cookie | 账号页：注册、登录、退出，查看连接、设备、24 小时内的委托，用配对码加设备、改名、移除，更新例行任务 Webhook，重置 MCP 令牌，改密码 |
| `POST /cb/<任务 ID>` | Grok Bot，`Authorization: Bearer <本轮回调令牌>` | 不用 MCP 时的回调 `{"state":"working|input-required|completed|failed","text":"…","data":{…}}` |
| `GET /files/<任务 ID>/<n>.<ext>` | 设备，`Authorization: Bearer <设备令牌>` | 汇报附带的图片，只给创建该任务的设备 |
| `GET /health` | 任何人 | 返回 `ok` |

- `message/send` 立即返回 `working` 的任务，不等 Grok 做完；设备用 `tasks/get` 查询。结果放在 artifact（`metadata.channel=speech`、`phase=final`）里，Grok 回传的 `data` 作为同一 artifact 的 data part。
- `input-required` 时，设备用同一个 `taskId` 再发 `message/send` 作为回答。Grok 每次运行互不相通，所以下一轮把原任务、Grok 的问题和回答写成一段完整指令再发过去。
- 每一轮有自己的回调令牌，只存哈希；这一轮结束（完成、失败、追问、取消、超时）后令牌作废，迟到或重放的回调返回 409/401，不会改动任务。MCP 汇报必须带当前进行中的 `round`，其他轮次或这一轮已结束时返回错误，同样改不了任务。
- 图文结果：汇报的 `data.card`（`title`、`value`、`subtitle`、最多 4 个 `fields`、最多 6 个 `items`）对应设备 `show_card`；`data.images` 是图片地址。relay 先把 data 规整成这个格式（Bot 用了自由字段名时转成 fields/items，卡片里的图片地址和裸 URL 归到 images），再自己下载图片：只要公网 https 主机（拒绝私网、回环、CGNAT 等地址，每次重定向都检查），只收 jpg/png/webp，每张不超过 2 MB，最多 3 张，存到任务目录，`images` 改写成 `/files/...` 路径；下载失败的计入 `images_failed`。设备只从这个路径取图，不访问任意网址。
- 配对：配对码 8 位（去掉易混字符），15 分钟有效，用一次就失效，只存哈希；设备令牌、MCP 令牌也只存哈希，Webhook 密钥因为要发给 Grok 只能原样存在 `data/accounts.json`（0600）。用户的 Webhook 地址必须是 https、在 `RELAY_WEBHOOK_HOSTS`（默认 `cursor.sh`，Grok Bot 例行任务的 Webhook 在 `api2.cursor.sh`）之下，且解析到公网地址。24 小时内没配对的设备自动删除；一个用户的最后一台设备断开时，这个用户（含 Webhook 密钥）一起删除。
- 解除绑定两边都能做：设备上「解除绑定」（`POST /connect/device/unpair`：如果是这个用户的最后一台设备，relay 先给 Bot 发最后一条委托，让它删除例行任务、用 `car_task_report` 回报、再删除 car-relay 连接器，并用 WakeParent 在主聊天留记录；设备最多等三分钟、显示回报，然后 `DELETE /connect/device`；同一用户还有别的设备或是 owner 时直接解除，Bot 那边的设置保留），或者对 Bot 说「解除设备绑定」（`car_disconnect`，可指定一台）。设备每次打开控制台和关掉「连接应用」时会向 relay 核对，发现已被 Bot 解除就删掉自己的 A2A 档案和令牌，并留一条记录。
- 记录：Webhook 触发的是例行任务的子运行，默认只记在该例行任务的运行历史里；每一轮的 notes 要求 Bot 汇报后调用 WakeParent，把「设备委托（第 n 轮）：原话 → 结果」同步到主聊天。设备端的连接、测试、解除绑定记在「连接应用」的最近记录里，并在控制台显示一行 Notice。
- 配对用户的任务一律用 MCP 汇报，发给 Grok 的任务里只有 `report_with`，不带回调地址和令牌，所以不会触发终端命令审批。owner 只有配了 `RELAY_MCP_TOKENS` 才这样，否则退回 `curl` 回调。MCP 令牌和设备令牌必须不同。
- 账号：一个用户名加密码对应一个连接（也就是一组 Webhook 和 MCP 令牌）。
  - 设备在「连接应用」里登录：账号已有连接时，设备加入这个连接，不用扫码，也不用 MCP 令牌；账号还没有连接、而设备已经连着时，账号接管设备的连接（已属于别的账号的连接不给，返回 409）；两边都没有时，设备仍按配对流程连，配好的连接归这个账号。
  - 账号页上用新设备的配对码也能把它加进来。
  - 账号持有的连接，最后一台设备解绑时不删除，也不会让 Bot 删除例行任务。
  - 账号页可以更新 Webhook 地址和密钥（同样只认 `RELAY_WEBHOOK_HOSTS` 下的公网 https），可以重置 MCP 令牌（新令牌只显示一次，并附一句发给 Bot 改连接器的话）。owner 的连接这两项要在 relay.env 里改。
  - 安全措施：
    - 密码用 scrypt（N=16384，r=8，p=1）加盐存储；
    - 会话令牌只存哈希，cookie 设为 `HttpOnly; SameSite=Strict; Path=/account`，https 下加 `Secure`；
    - 账号页的 POST 必须来自本站 Origin；
    - 登录按来源地址和用户名每小时限 `RELAY_LOGIN_PER_HOUR` 次（默认 10）；
    - 改密码会退出所有会话。
  - 存储：账号和会话都存在 `data/accounts.json`（0600）。
- 进度：
  - `tasks/get` 的 `metadata.relay` 带这一轮的阶段 `stage`（delivered / started / working / stalled / 结束状态）、`pickedUpAt`（Bot 第一次 `car_task_get` 的时间）、`progressAt` 和 `steps`（`working` 汇报的 `data.steps`，最多 12 步，每步 status 为 done、doing 或 todo）。
  - 同一个 `relay` 对象也作为状态消息的 data part 发出，因为设备上的客户端只读得到消息。
  - 送达 90 s 还没被读取算 stalled，状态消息改成"可能在排队或等审批"。
  - notes 要求多步骤任务先报计划，每做完一步报一次。
- `tasks/cancel` 只是停止等待：Grok 那边没有取消接口，之后的结果会被丢弃。
- 一轮超过 `RELAY_TASK_TIMEOUT_MINUTES`（默认 15 分钟）没有回调，任务按失败结束并说明"没有回执"；`working` 回调会把期限往后推。
- 每个设备令牌每小时最多发 `RELAY_MAX_TASKS_PER_HOUR`（默认 30）条消息；设备只能查询、回答、取消自己的任务。
- 任务存在 `data/tasks.json`（0600），完成的任务保留 24 小时。日志只记任务 ID 和状态，不记指令、结果或令牌。

## Grok Bot 里的设置（owner 手动配置；配对用户由 `/connect` 页带着做）

1. **接入 MCP**：在 Bot 聊天里说"添加一个自定义 MCP 服务器（custom server，不要装目录里的插件）：名称 car-relay，URL `https://<你的域名>/mcp`，请求头 `Authorization: Bearer <RELAY_MCP_TOKENS>`，不用 OAuth"，确认审批卡片。连接器对同一账号的所有 Bot 生效。
2. **Auto-review**（设置 → 通用 → Auto-review）加一条 Allow automatically 规则：自动允许调用 car-relay 插件的 `car_task_get` 和 `car_task_report`。发邮件、付款、删除等仍保留 Grok 默认的审批。
3. **Routine**：新建一个 Webhook 触发的 Routine，URL 和密钥写进 `relay.env`，指令如下：

> 你收到的 JSON 是我设备上的语音助手委托给你的任务，字段有 task_id、round、instruction、report_with、context、notes。请执行 instruction，并遵守 notes。
> 这一轮结束时**必须**调用 car-relay 插件的 car_task_report 汇报一次，不管成功还是失败：带上 task_id、round、state（completed / input-required / failed）、text（一两句适合语音播报的中文）和可选的 data。需要完整内容或之前的问答时调用 car_task_get。不要用终端或 curl 回调。
> - 需要我做决定或补充信息时，state 写 input-required，text 写清楚要问我什么。
> - 付款、下单、删除这类操作，除非 instruction 里明确写了"已确认"，否则一律不执行，改用 input-required 问我。
> - 实际执行的时间、对象或内容和要求不一样时，必须在 text 里说明；completed 要在操作真正生效之后再汇报。
> - text 里不要包含密码、令牌等敏感信息。

`context` 是设备带来的上下文（时区、当地时间等），由 relay 原样转交并加上 `relay_sent_at`。不配 MCP 时，任务里带 `callback_url` 和 `callback_token`，Routine 指令改为用终端 `curl` 回调（需要在 Auto-review 里放行这条命令）。

## 部署

relay 是一个不依赖 npm 包的 Node.js 20+ 进程，监听 `RELAY_HOST:RELAY_PORT`（默认 `127.0.0.1:18790`），对外由任意 HTTPS 反向代理转发（Caddy、nginx 都行）：

1. 把本目录的 `*.cjs`（测试文件除外）和 `*.html` 复制到服务器，比如 `~/agent-relay/`。
2. 按 `relay.env.example` 写 `~/agent-relay/relay.env`（`umask 077`）：`RELAY_PUBLIC_URL` 填对外的 https 地址。只给别人配对用时这一项就够；要接你自己的 Grok（下称 owner），再填设备令牌、MCP 令牌（各用 `openssl rand -base64 32` 生成）和 Webhook。
3. 按 `agent-relay.service` 改好用户和路径，安装为 systemd 服务并启动：`sudo systemctl enable --now agent-relay`。
4. 反向代理把你的域名转到 relay，例如 Caddy：

   ```caddy
   relay.example.com {
     reverse_proxy http://127.0.0.1:18790 {
       flush_interval -1
     }
   }
   ```

App 从 `workspace/apps/android/local.properties` 的 `AGENT_RELAY_URL`（例如 `https://relay.example.com`）知道去哪配对；这是地址不是密钥，每种打包方式都会带上。没有设置时，「连接应用」里的 Grok Bot 会提示先配置中转地址。

协议里的名字（MCP 工具 `car_task_get`、`car_task_report`，连接器 `car-relay`，任务来源 `source: car`）沿用最初的命名，改名会让已经配对的 Bot 失效，所以保持不变。

## 测试

```sh
node --test workspace/apps/android/tools/agent-relay/relay.test.cjs
```

测试不需要网络。
