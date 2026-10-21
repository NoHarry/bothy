'use strict';

// A small MCP server (Streamable HTTP, JSON responses only, tools only) for the user's own agent. The routine webhook
// still wakes the agent; it then reads the delegated car task and reports back with tool calls instead of a shell
// `curl`, so an approval rule can allow exactly those two tools. Two more let the user list and disconnect their cars
// from the bot; they stay outside the approval rule. No resources, prompts or server-initiated streams.
const { OWNER } = require('./accounts.cjs');

const KNOWN_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25'];
const LATEST_VERSION = '2025-11-25';
const SERVER_INFO = { name: 'car-relay', title: '设备委托', version: '1.0.0' };
const REPORT_STATES = ['working', 'input-required', 'completed', 'failed'];
const INSTRUCTIONS = '设备上的语音助手把任务委托给你时，routine 会收到 task_id 和 round。需要完整内容时调用 car_task_get；' +
  '这一轮结束时必须调用一次 car_task_report 汇报（进度可以先用 state=working 汇报）。';

const TOOLS = [
  {
    name: 'car_task_get',
    title: '读取设备委托任务',
    description: '读取设备委托给你的任务：原始请求、之前的问答、设备上的当前时间和时区、当前轮次和办理要求。',
    inputSchema: {
      type: 'object',
      properties: { task_id: { type: 'string', description: 'routine 收到的 task_id' } },
      required: ['task_id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: 'car_task_report',
    title: '向设备汇报任务结果',
    description: '向设备汇报这一轮的结果，设备上会用语音转告用户。completed 完成、input-required 需要用户决定或补充（text 写问题）、' +
      'failed 没办成（text 写原因）、working 只是进度（多步骤的任务在 data.steps 里带上步骤清单 [{"title":"…","status":"done|doing|todo"}]）。text 用一两句适合语音播报的中文；有具体安排时放进 data，例如 ' +
      '{"scheduled_at":"2026-10-12T14:00:00+08:00"}。地点、价格、推荐项这类适合看的结果做成 data.card，有照片或地图截图' +
      '放 data.images（最多 3 张）。只在操作真正生效后报 completed；实际做法和要求不同要在 text 里说明。',
    inputSchema: {
      type: 'object',
      properties: {
        task_id: { type: 'string' },
        round: { type: 'integer', minimum: 1, description: 'routine 收到的 round' },
        state: { type: 'string', enum: REPORT_STATES },
        text: { type: 'string', description: '一两句适合语音播报的中文，不含密码或令牌' },
        data: {
          type: 'object',
          description: '可选的结构化结果。适合在设备屏幕上看的结果放 card；门店照片、地图截图这类放 images（https 图片地址）。',
          properties: {
            card: {
              type: 'object',
              description: '设备信息卡：title 标题，value 主数值（如 人均 90），subtitle 一句概况，fields 最多 4 项名称和数值，items 最多 6 行条目',
              properties: {
                title: { type: 'string' },
                value: { type: 'string' },
                subtitle: { type: 'string' },
                fields: { type: 'array', maxItems: 4, items: { type: 'object', properties: { label: { type: 'string' }, value: { type: 'string' } }, required: ['label', 'value'] } },
                items: { type: 'array', maxItems: 6, items: { type: 'object', properties: { title: { type: 'string' }, detail: { type: 'string' } }, required: ['title'] } },
              },
              required: ['title'],
            },
            images: {
              type: 'array',
              maxItems: 3,
              items: { type: 'object', properties: { url: { type: 'string', description: 'https 的 jpg/png/webp，2 MB 以内' }, caption: { type: 'string' } }, required: ['url'] },
            },
          },
        },
      },
      required: ['task_id', 'round', 'state', 'text'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: 'car_list_connections',
    title: '列出连到这里的设备',
    description: '列出通过 car-relay 连到你这里的设备：car_id、名称、连接时间、最近在线时间。用户问连了哪些设备，或要解除某台设备的绑定之前调用。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: 'car_disconnect',
    title: '解除设备绑定',
    description: '解除设备和你的绑定：带 car_id 只解除那一台，不带就全部解除。解除绑定的设备不能再把任务交给你。只在用户明确要求解除绑定时调用。' +
      '结果里说已经没有设备连着时，接着删除「车载语音助手任务」例行任务和 car-relay 连接器，再告诉用户解除了哪些设备的绑定、删了什么。',
    inputSchema: {
      type: 'object',
      properties: { car_id: { type: 'string', description: 'car_list_connections 返回的 car_id；不填就全部解除' } },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
  },
];

const REPORT_REPLIES = {
  200: '已汇报，设备上会转告用户。',
  400: 'state 或 text 不合要求，没有汇报。',
  404: '没有这个任务。',
  409: '这个任务的这一轮已经结束（已汇报、已取消或已超时）或 round 不对，不要再汇报。',
};

const isObject = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);

const iso = millis => (millis ? new Date(millis).toISOString() : null);

function result(id, value) {
  return { jsonrpc: '2.0', id, result: value };
}

function error(id, code, message) {
  return { jsonrpc: '2.0', id: id ?? null, error: { code, message } };
}

function toolText(text, isError, structured) {
  const value = { content: [{ type: 'text', text }], isError };
  if (structured) value.structuredContent = structured;
  return value;
}

function taskView(task, notes) {
  return {
    task_id: task.id,
    round: task.round,
    state: task.state,
    instruction: task.instruction,
    conversation: task.conversation,
    context: task.context,
    notes,
  };
}

/** Only the tasks of the user this connector belongs to are visible; any other id is unknown. */
function ownTask(ctx, id) {
  const task = typeof id === 'string' ? ctx.store.get(id) : null;
  return task && ctx.owns(task) ? task : null;
}

function getTask(ctx, args) {
  const task = ownTask(ctx, args.task_id);
  if (!task) return toolText(REPORT_REPLIES[404], true);
  // Reading the task is the first sign the bot started, so the car can tell started from stuck in a queue.
  ctx.store.markPickedUp(task);
  const view = taskView(task, ctx.notes);
  return toolText(JSON.stringify(view), false, view);
}

async function reportTask(ctx, args) {
  const target = !Number.isInteger(args.round) ? { status: 400 }
    : ownTask(ctx, args.task_id) ? ctx.store.reportTarget(args.task_id, args.round)
    : { status: 404 };
  const status = target.task
    ? ctx.store.apply(target.task, { state: args.state, text: args.text, data: await ctx.attach(target.task, args.data) })
    : target.status;
  if (status === 200) ctx.onReport(target.task);
  ctx.log(`mcp report ${args.task_id || '-'} round=${args.round ?? '-'} state=${args.state || '-'} -> ${status}`);
  return toolText(REPORT_REPLIES[status], status !== 200);
}

function listConnections(ctx) {
  const cars = ctx.accounts.devicesOf(ctx.user).map(device => ({
    car_id: device.id, label: device.label, connected_at: iso(device.connectedAt), last_seen_at: iso(device.seenAt),
  }));
  const text = cars.length ? cars.map(car => `${car.label}（${car.car_id}）`).join('；') : '现在没有设备连着。';
  return toolText(text, false, { cars });
}

function disconnectCars(ctx, args) {
  const carId = typeof args.car_id === 'string' && args.car_id ? args.car_id : null;
  const removed = ctx.accounts.disconnect(ctx.user, carId);
  ctx.log(`mcp disconnect ${carId || 'all cars'} -> ${removed}`);
  if (removed === 0) return toolText(carId ? '没有这台设备，先用 car_list_connections 看看。' : '现在没有设备连着。', true);
  const left = ctx.accounts.devicesOf(ctx.user).length;
  const after = left > 0 ? `还连着 ${left} 台。`
    : ctx.user === OWNER ? '' : '已经没有设备连着，这个连接器的令牌已作废。请接着删除「车载语音助手任务」例行任务和 car-relay 连接器，再把结果告诉用户。';
  return toolText(`已解除 ${removed} 台设备的绑定。${after}`, false, { removed, remaining: left });
}

const TOOL_HANDLERS = {
  car_task_get: getTask,
  car_task_report: reportTask,
  car_list_connections: listConnections,
  car_disconnect: disconnectCars,
};

async function callTool(ctx, params) {
  const handler = Object.hasOwn(TOOL_HANDLERS, params.name) ? TOOL_HANDLERS[params.name] : null;
  return handler ? handler(ctx, isObject(params.arguments) ? params.arguments : {}) : null;
}

/** One JSON-RPC message in, its response out; null for notifications, which get HTTP 202. */
async function handleMcp(ctx, message) {
  if (!isObject(message) || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
    return error(message?.id, -32600, 'Invalid request');
  }
  const { id, method } = message;
  const params = isObject(message.params) ? message.params : {};
  if (id === undefined) return null;
  switch (method) {
    case 'initialize': {
      const requested = params.protocolVersion;
      if (!KNOWN_VERSIONS.includes(requested)) ctx.log(`mcp initialize asked for protocol ${requested}; answering ${LATEST_VERSION}`);
      return result(id, {
        protocolVersion: KNOWN_VERSIONS.includes(requested) ? requested : LATEST_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: INSTRUCTIONS,
      });
    }
    case 'ping': return result(id, {});
    case 'tools/list': return result(id, { tools: TOOLS });
    case 'tools/call': {
      const value = await callTool(ctx, params);
      return value ? result(id, value) : error(id, -32602, `Unknown tool: ${params.name}`);
    }
    default: return error(id, -32601, 'Method not found');
  }
}

module.exports = { handleMcp, TOOLS, KNOWN_VERSIONS, LATEST_VERSION };
