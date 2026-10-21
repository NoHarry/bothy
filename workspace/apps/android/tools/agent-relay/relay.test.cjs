'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { createRelay } = require('./relay.cjs');
const { TaskStore, DELIVERED_TEXT, CANCELED_TEXT } = require('./tasks.cjs');
const { AccountStore } = require('./accounts.cjs');
const { loadConfig, parseEnvFile, readSettings } = require('./config.cjs');
const { deliver, payload } = require('./grok.cjs');
const { attachImages, privateAddress } = require('./images.cjs');
const { normalizeReport } = require('./report.cjs');

const TOKEN = 'car-token-0123456789abcdefghij';
const OTHER = 'other-token-0123456789abcdefgh';
const MCP = 'grok-mcp-token-0123456789abcdef';
const SETTINGS = {
  RELAY_PUBLIC_URL: 'https://relay.test/', RELAY_CLIENT_TOKENS: `${TOKEN}, ${OTHER}`,
  GROK_WEBHOOK_URL: 'https://grok.test/hook', GROK_WEBHOOK_KEY: 'webhook-key', RELAY_WEBHOOK_HOSTS: 'grok.test',
};
const INSIDE = 'inside.grok.test';
const webhookLookup = async host => [{ address: host === INSIDE ? '10.20.30.40' : '93.184.216.34', family: 4 }];

async function relay(t, { limit, file = '', deliverImpl, mcp = false, filesRoot = '', imageDeps = {}, accountsFile = '', connectPerHour, loginPerHour, taskTimeoutMs = 60_000 } = {}) {
  let clock = Date.UTC(2026, 9, 11, 10, 0, 0);
  const now = () => clock;
  const deliveries = [];
  const config = loadConfig({
    ...SETTINGS, RELAY_MAX_TASKS_PER_HOUR: limit, RELAY_MCP_TOKENS: mcp ? MCP : undefined, RELAY_CONNECT_PER_HOUR: connectPerHour,
    RELAY_LOGIN_PER_HOUR: loginPerHour,
  });
  const store = new TaskStore({ file, filesRoot, now, taskTimeoutMs });
  const accounts = new AccountStore({ file: accountsFile, now });
  const record = async (target, task, token) => {
    deliveries.push({ token, target, payload: payload(target, task, token, now) });
    return { ok: true };
  };
  const server = createRelay({ config, store, accounts, deliverImpl: deliverImpl || record, now, imageDeps, lookup: webhookLookup });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (url, token, body) => fetch(`${base}${url}`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
  const rpc = async (method, params, token = TOKEN) => {
    const response = await post('/a2a', token, { jsonrpc: '2.0', id: 'r1', method, params });
    return { status: response.status, body: await response.json() };
  };
  const callback = async (taskId, token, body) => (await post(`/cb/${taskId}`, token, body)).status;
  const mcpCall = async (method, params, token = MCP, id = 'm1') => {
    const response = await post('/mcp', token, id === null ? { jsonrpc: '2.0', method, params } : { jsonrpc: '2.0', id, method, params });
    const json = response.headers.get('content-type')?.includes('application/json');
    return { status: response.status, body: json ? await response.json() : await response.text() };
  };
  const tool = async (name, args, token = MCP) => (await mcpCall('tools/call', { name, arguments: args }, token)).body.result;
  const request = async (method, url, { token, body } = {}) => {
    const response = await fetch(`${base}${url}`, {
      method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const json = response.headers.get('content-type')?.includes('application/json');
    return { status: response.status, headers: response.headers, body: json ? await response.json() : await response.text() };
  };
  return { base, rpc, callback, mcpCall, tool, request, deliveries, store, accounts, advance: millis => { clock += millis; } };
}

const message = (text, extra = {}) => ({ message: { kind: 'message', messageId: 'm1', role: 'user', parts: [{ kind: 'text', text }], ...extra } });

async function started(t, options) {
  const fixture = await relay(t, options);
  const { body } = await fixture.rpc('message/send', message('给我自己发封测试邮件', { metadata: { context: { timezone: 'Asia/Shanghai' } } }));
  return { ...fixture, task: body.result, token: fixture.deliveries.at(-1).token };
}

test('agent card is public and describes polling, health answers ok', async t => {
  const { base } = await relay(t);
  const card = await (await fetch(`${base}/.well-known/agent-card.json`)).json();
  assert.equal(card.name, 'Grok Bot');
  assert.equal(card.url, 'https://relay.test/a2a');
  assert.equal(card.capabilities.streaming, false);
  assert.equal(await (await fetch(`${base}/health`)).text(), 'ok');
  assert.equal((await fetch(`${base}/elsewhere`)).status, 404);
});

test('the car must present a known token', async t => {
  const { rpc, deliveries } = await relay(t);
  assert.equal((await rpc('message/send', message('hi'), 'wrong-token-0123456789abcdef')).status, 401);
  assert.equal(deliveries.length, 0);
});

test('message/send hands the task to Grok and answers working at once', async t => {
  const { task, deliveries } = await started(t);
  assert.equal(task.kind, 'task');
  assert.equal(task.status.state, 'working');
  assert.equal(task.status.message.parts[0].text, DELIVERED_TEXT);
  const sent = deliveries[0].payload;
  assert.equal(sent.task_id, task.id);
  assert.equal(sent.instruction, '给我自己发封测试邮件');
  assert.equal(sent.callback_url, `https://relay.test/cb/${task.id}`);
  assert.equal(sent.callback_token, deliveries[0].token);
  assert.equal(sent.context.timezone, 'Asia/Shanghai');
  assert.ok(sent.context.relay_sent_at && sent.notes.includes('必须在 text 里说明'));
});

test('a completed callback ends the task once, with the spoken result and data', async t => {
  const { rpc, callback, task, token } = await started(t);
  assert.equal(await callback(task.id, 'not-the-token', { state: 'completed', text: 'x' }), 401);
  const data = { scheduled_at: '2026-10-12T14:00:00+08:00' };
  assert.equal(await callback(task.id, token, { state: 'completed', text: '邮件已发出。', data }), 200);
  const done = (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal(done.status.state, 'completed');
  assert.equal(done.status.message.parts[0].text, '邮件已发出。');
  assert.deepEqual(done.artifacts[0].metadata, { channel: 'speech', phase: 'final' });
  assert.deepEqual(done.artifacts[0].parts, [{ kind: 'text', text: '邮件已发出。' }, { kind: 'data', data }]);
  assert.equal(await callback(task.id, token, { state: 'failed', text: '重放' }), 409);
  assert.equal((await rpc('tasks/get', { id: task.id })).body.result.status.state, 'completed');
});

test('working callbacks report progress and push the deadline back', async t => {
  const { rpc, callback, task, token, advance } = await started(t);
  advance(50_000);
  assert.equal(await callback(task.id, token, { state: 'working', text: '正在写邮件' }), 200);
  advance(50_000);
  const current = (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal(current.status.state, 'working');
  assert.equal(current.status.message.parts[0].text, '正在写邮件');
});

test('input-required asks the car, and the answer starts a self-contained second round', async t => {
  const { rpc, callback, task, token, deliveries } = await started(t);
  assert.equal(await callback(task.id, token, { state: 'input-required', text: '发给哪个邮箱？' }), 200);
  const asked = (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal(asked.status.state, 'input-required');
  assert.equal(asked.artifacts[0].name, 'question');
  assert.equal(await callback(task.id, token, { state: 'completed', text: '旧轮次' }), 401);
  const resumed = (await rpc('message/send', message('发到我的工作邮箱', { taskId: task.id }))).body.result;
  assert.equal(resumed.status.state, 'working');
  const second = deliveries[1];
  assert.equal(second.payload.round, 2);
  assert.match(second.payload.instruction, /原任务：给我自己发封测试邮件[\s\S]*你问：发给哪个邮箱？[\s\S]*我答：发到我的工作邮箱/);
  assert.notEqual(second.token, token);
  assert.equal(await callback(task.id, token, { state: 'completed', text: '旧令牌' }), 401);
  assert.equal(await callback(task.id, second.token, { state: 'completed', text: '已发到工作邮箱。' }), 200);
});

test('answers only reach a waiting task the same car owns', async t => {
  const { rpc, callback, task, token } = await started(t);
  assert.equal((await rpc('message/send', message('答', { taskId: task.id }))).body.error.code, -32602);
  assert.equal((await rpc('message/send', message('答', { taskId: '00000000-0000-0000-0000-000000000000' }))).body.error.code, -32001);
  assert.equal((await rpc('tasks/get', { id: task.id }, OTHER)).body.error.code, -32001);
  await callback(task.id, token, { state: 'input-required', text: '哪个？' });
  assert.equal((await rpc('message/send', message('答', { taskId: task.id }), OTHER)).body.error.code, -32001);
});

test('cancel stops waiting and drops late results', async t => {
  const { rpc, callback, task, token } = await started(t);
  const canceled = (await rpc('tasks/cancel', { id: task.id })).body.result;
  assert.equal(canceled.status.state, 'canceled');
  assert.equal(canceled.status.message.parts[0].text, CANCELED_TEXT);
  assert.deepEqual(canceled.artifacts, []);
  assert.equal(await callback(task.id, token, { state: 'completed', text: '迟到' }), 409);
  assert.equal((await rpc('tasks/cancel', { id: task.id })).body.error.code, -32002);
});

test('a round without a callback times out as failed and refuses late results', async t => {
  const { rpc, callback, task, token, advance } = await started(t);
  advance(61_000);
  const expired = (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal(expired.status.state, 'failed');
  assert.match(expired.artifacts[0].parts[0].text, /没有回执/);
  assert.equal(await callback(task.id, token, { state: 'completed', text: '迟到' }), 409);
});

test('a webhook failure fails the task with a spoken reason', async t => {
  const { rpc } = await relay(t, { deliverImpl: async () => ({ ok: false, reason: 'Webhook 返回 HTTP 403' }) });
  const task = (await rpc('message/send', message('查天气'))).body.result;
  assert.equal(task.status.state, 'failed');
  assert.equal(task.artifacts[0].parts[0].text, '没能交给 Grok Bot：Webhook 返回 HTTP 403');
});

test('each car is limited per hour', async t => {
  const { rpc } = await relay(t, { limit: 2 });
  await rpc('message/send', message('一'));
  await rpc('message/send', message('二'));
  assert.equal((await rpc('message/send', message('三'))).body.error.code, -32000);
  assert.equal((await rpc('message/send', message('四'), OTHER)).body.result.status.state, 'working');
});

test('unsupported, unknown and malformed requests get JSON-RPC errors', async t => {
  const { base, rpc } = await relay(t);
  assert.equal((await rpc('message/stream', message('hi'))).body.error.code, -32004);
  assert.equal((await rpc('tasks/list', {})).body.error.code, -32601);
  assert.equal((await rpc('message/send', message('  '))).body.error.code, -32602);
  const response = await fetch(`${base}/a2a`, { method: 'POST', headers: { Authorization: `Bearer ${TOKEN}` }, body: '{' });
  assert.equal((await response.json()).error.code, -32700);
});

test('callbacks with a bad body are rejected without touching the task', async t => {
  const { rpc, callback, task, token } = await started(t);
  assert.equal(await callback(task.id, token, '{oops'), 400);
  assert.equal(await callback(task.id, token, { state: 'done', text: 'x' }), 400);
  assert.equal(await callback(task.id, token, { state: 'completed', text: '  ' }), 400);
  assert.equal(await callback(task.id, token, { state: 'completed', text: 'x'.repeat(70 * 1024) }), 413);
  assert.equal((await rpc('tasks/get', { id: task.id })).body.result.status.state, 'working');
});

test('tasks survive a restart and the file keeps only token hashes', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-relay-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'data', 'tasks.json');
  const { task, token } = await started(t, { file });
  const saved = fs.readFileSync(file, 'utf8');
  assert.ok(!saved.includes(token));
  const reloaded = new TaskStore({ file, now: () => Date.UTC(2026, 9, 11, 10, 0, 30) }).load();
  assert.equal(reloaded.get(task.id).state, 'working');
  assert.equal(reloaded.callback(task.id, token, { state: 'completed', text: '好了' }), 200);
});

test('deliver posts the payload with the webhook key and reports failures without throwing', async t => {
  const seen = [];
  const hook = http.createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    seen.push({ auth: req.headers.authorization, body: JSON.parse(body) });
    res.statusCode = seen.length === 1 ? 200 : 403;
    res.end();
  });
  hook.listen(0, '127.0.0.1');
  await once(hook, 'listening');
  t.after(() => hook.close());
  const config = loadConfig({ ...SETTINGS, GROK_WEBHOOK_URL: `http://127.0.0.1:${hook.address().port}/hook` });
  const store = new TaskStore();
  const task = store.create({ instruction: '查天气', client: 'client-0' });
  const token = store.openRound(task);
  assert.deepEqual(await deliver(config, task, token), { ok: true });
  assert.equal(seen[0].auth, 'Bearer webhook-key');
  assert.equal(seen[0].body.callback_token, token);
  assert.deepEqual(await deliver(config, task, token), { ok: false, reason: 'Webhook 返回 HTTP 403' });
  const closed = loadConfig({ ...SETTINGS, GROK_WEBHOOK_URL: 'http://127.0.0.1:9/hook' });
  assert.deepEqual(await deliver(closed, task, token), { ok: false, reason: 'Webhook 网络错误' });
});

test('config names every problem without echoing secrets', () => {
  assert.throws(() => loadConfig({ RELAY_CLIENT_TOKENS: 'short', GROK_WEBHOOK_URL: 'http://grok.test', GROK_WEBHOOK_KEY: 'secret-value' }), error => {
    assert.match(error.message, /RELAY_PUBLIC_URL 未设置/);
    assert.match(error.message, /GROK_WEBHOOK_URL 必须是 https/);
    assert.match(error.message, /至少 24 个字符/);
    assert.ok(!error.message.includes('secret-value') && !error.message.includes('short'));
    return true;
  });
  assert.deepEqual(parseEnvFile('# note\nA=1\nB = "two" \n\nC=\'3\'\nbad line'), { A: '1', B: 'two', C: '3' });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-relay-env-'));
  const file = path.join(dir, 'relay.env');
  fs.writeFileSync(file, 'RELAY_PORT=1\nRELAY_AGENT_NAME=文件\n');
  assert.deepEqual(readSettings(file, { RELAY_PORT: '2', UNRELATED: 'x' }), { RELAY_PORT: '2', RELAY_AGENT_NAME: '文件' });
  fs.rmSync(dir, { recursive: true, force: true });
});

test('the MCP endpoint refuses unknown tokens and the car token', async t => {
  const off = await relay(t);
  assert.equal((await off.mcpCall('tools/list', {})).status, 401);
  const on = await relay(t, { mcp: true });
  assert.equal((await on.mcpCall('tools/list', {}, TOKEN)).status, 401);
  assert.equal((await fetch(`${on.base}/mcp`)).status, 405);
  assert.throws(() => loadConfig({ ...SETTINGS, RELAY_MCP_TOKENS: TOKEN }), /不能和 RELAY_CLIENT_TOKENS 相同/);
});

test('MCP initialize, notifications and the tool list', async t => {
  const { mcpCall } = await relay(t, { mcp: true });
  const init = (await mcpCall('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'grok' } })).body.result;
  assert.equal(init.protocolVersion, '2025-06-18');
  assert.equal(init.serverInfo.name, 'car-relay');
  assert.deepEqual(init.capabilities, { tools: { listChanged: false } });
  assert.equal((await mcpCall('initialize', { protocolVersion: '2099-01-01' })).body.result.protocolVersion, '2025-11-25');
  assert.equal((await mcpCall('notifications/initialized', {}, MCP, null)).status, 202);
  const tools = (await mcpCall('tools/list', {})).body.result.tools;
  assert.deepEqual(tools.map(tool => tool.name), ['car_task_get', 'car_task_report', 'car_list_connections', 'car_disconnect']);
  assert.equal(tools.find(tool => tool.name === 'car_disconnect').annotations.destructiveHint, true);
  assert.equal((await mcpCall('tools/call', { name: 'rm_rf', arguments: {} })).body.error.code, -32602);
  assert.equal((await mcpCall('resources/list', {})).body.error.code, -32601);
});

test('with MCP the webhook payload names the report tool and carries no shell callback', async t => {
  const { task, deliveries } = await started(t, { mcp: true });
  const sent = deliveries[0].payload;
  assert.equal(sent.task_id, task.id);
  assert.match(sent.report_with, /car_task_report/);
  assert.equal(sent.callback_url, undefined);
  assert.equal(sent.callback_token, undefined);
});

test('the bot reads the task and reports through MCP; the car sees the result', async t => {
  const { rpc, tool, task } = await started(t, { mcp: true });
  const view = await tool('car_task_get', { task_id: task.id });
  assert.equal(view.isError, false);
  assert.equal(view.structuredContent.instruction, '给我自己发封测试邮件');
  assert.equal(view.structuredContent.round, 1);
  assert.equal(view.structuredContent.context.timezone, 'Asia/Shanghai');
  const data = { sent_to: 'me@example.com' };
  const report = await tool('car_task_report', { task_id: task.id, round: 1, state: 'completed', text: '邮件已发出。', data });
  assert.equal(report.isError, false);
  const done = (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal(done.status.state, 'completed');
  assert.deepEqual(done.artifacts[0].parts, [{ kind: 'text', text: '邮件已发出。' }, { kind: 'data', data }]);
  const again = await tool('car_task_report', { task_id: task.id, round: 1, state: 'failed', text: '重放' });
  assert.equal(again.isError, true);
  assert.equal((await rpc('tasks/get', { id: task.id })).body.result.status.state, 'completed');
});

test('MCP reports must name the open round and a valid state', async t => {
  const { rpc, tool, task } = await started(t, { mcp: true });
  assert.equal((await tool('car_task_report', { task_id: task.id, round: 2, state: 'completed', text: 'x' })).isError, true);
  assert.equal((await tool('car_task_report', { task_id: task.id, round: 1, state: 'done', text: 'x' })).isError, true);
  assert.equal((await tool('car_task_report', { task_id: 'nope', round: 1, state: 'completed', text: 'x' })).isError, true);
  assert.equal((await tool('car_task_get', { task_id: 'nope' })).isError, true);
  assert.equal((await rpc('tasks/get', { id: task.id })).body.result.status.state, 'working');
  assert.equal((await tool('car_task_report', { task_id: task.id, round: 1, state: 'input-required', text: '发到哪个邮箱？' })).isError, false);
  assert.equal((await rpc('tasks/get', { id: task.id })).body.result.status.state, 'input-required');
});

const PNG = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');
const publicLookup = async () => [{ address: '93.184.216.34', family: 4 }];

function imageFetch(routes) {
  return async url => {
    const route = routes[url];
    if (!route) return new Response('missing', { status: 404 });
    if (route.redirect) return new Response(null, { status: 302, headers: { location: route.redirect } });
    return new Response(route.body ?? PNG, { status: 200, headers: { 'content-type': route.type ?? 'image/png' } });
  };
}

function tempDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-relay-files-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test('private, loopback, CGNAT and link-local addresses are never fetched', () => {
  for (const address of ['10.20.30.40', '127.0.0.1', '100.104.1.2', '169.254.1.1', '192.168.1.1', '172.20.0.1', '::1', 'fd7a::1', '::ffff:10.0.0.1']) {
    assert.ok(privateAddress(address), address);
  }
  for (const address of ['93.184.216.34', '8.8.8.8', '2606:4700::1111']) assert.ok(!privateAddress(address), address);
});

test('attached pictures are fetched from public hosts only, checked, and stored under the task', async t => {
  const dir = tempDir(t);
  const fetchImpl = imageFetch({
    'https://img.example.com/a.png': {},
    'https://img.example.com/moved': { redirect: 'https://img.example.com/a.png' },
    'https://img.example.com/page': { type: 'text/html', body: '<html>' },
    'https://img.example.com/huge.jpg': { type: 'image/jpeg', body: Buffer.alloc(3 * 1024 * 1024) },
    'https://inside.example.com/x.png': {},
  });
  const lookup = async host => [{ address: host === 'inside.example.com' ? '10.20.30.40' : '93.184.216.34', family: 4 }];
  const data = await attachImages({
    card: { title: '香香川菜' },
    images: [{ url: 'https://img.example.com/a.png', caption: '门头' }, { url: 'https://img.example.com/moved' }, { url: 'https://img.example.com/page' }],
  }, 'task-1', dir, { fetchImpl, lookup });
  assert.deepEqual(data.card, { title: '香香川菜' });
  assert.deepEqual(data.images.map(image => image.path), ['/files/task-1/1.png', '/files/task-1/2.png']);
  assert.equal(data.images[0].caption, '门头');
  assert.equal(data.images_failed, 1);
  assert.deepEqual(fs.readFileSync(path.join(dir, '1.png')), PNG);
  const blocked = await attachImages({ images: [
    { url: 'https://inside.example.com/x.png' }, { url: 'http://img.example.com/a.png' }, { url: 'https://img.example.com/huge.jpg' },
  ] }, 'task-2', path.join(dir, 'two'), { fetchImpl, lookup });
  assert.deepEqual(blocked, { images_failed: 3 });
});

test('a reported picture reaches only the car that owns the task', async t => {
  const filesRoot = tempDir(t);
  const imageDeps = { fetchImpl: imageFetch({ 'https://img.example.com/a.png': {} }), lookup: publicLookup };
  const { base, rpc, tool, task } = await started(t, { mcp: true, filesRoot, imageDeps });
  const card = { title: '香香川菜（张江店）', value: '人均 90', fields: [{ label: '地址', value: '紫薇路' }] };
  const report = await tool('car_task_report', {
    task_id: task.id, round: 1, state: 'completed', text: '挑好了。', data: { card, images: [{ url: 'https://img.example.com/a.png', caption: '门头' }] },
  });
  assert.equal(report.isError, false);
  const done = (await rpc('tasks/get', { id: task.id })).body.result;
  const data = done.artifacts[0].parts[1].data;
  assert.deepEqual(data.card, card);
  assert.equal(data.images[0].path, `/files/${task.id}/1.png`);
  const get = token => fetch(`${base}${data.images[0].path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  const mine = await get(TOKEN);
  assert.equal(mine.status, 200);
  assert.equal(mine.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await mine.arrayBuffer()), PNG);
  assert.equal((await get(OTHER)).status, 404);
  assert.equal((await get(null)).status, 401);
  assert.equal((await get(MCP)).status, 401);
  assert.equal((await fetch(`${base}/files/${task.id}/..%2Ftasks.json`, { headers: { Authorization: `Bearer ${TOKEN}` } })).status, 404);
});

test('a dropped task takes its pictures with it', t => {
  const filesRoot = tempDir(t);
  const store = new TaskStore({ filesRoot });
  const task = store.create({ instruction: 'x', client: 'client-0' });
  fs.mkdirSync(store.taskDir(task.id), { recursive: true });
  store.drop(task.id);
  assert.ok(!fs.existsSync(store.taskDir(task.id)));
});

test('free-form cards and bare picture URLs are brought into the documented shape', () => {
  const data = normalizeReport({
    card: {
      title: '明天中午川菜推荐', 店名: '香香川菜（张江店）', 人均: '约91元', 招牌菜: '水煮鱼', 区域: '紫薇路217号', 推荐理由: '口碑稳',
      门店图片路径: 'https://upload.wikimedia.org/a/Shuizhufish.jpg',
    },
    images: ['https://img.example.com/b.png', { image_url: 'https://img.example.com/c.webp', title: '菜品' }, 'http://plain.example.com/d.png'],
    restaurant: { name: '香香川菜' },
  });
  assert.deepEqual(data.card, {
    title: '明天中午川菜推荐',
    fields: [{ label: '店名', value: '香香川菜（张江店）' }, { label: '人均', value: '约91元' }, { label: '招牌菜', value: '水煮鱼' }, { label: '区域', value: '紫薇路217号' }],
    items: [{ title: '推荐理由', detail: '口碑稳' }],
  });
  assert.deepEqual(data.images, [
    { url: 'https://img.example.com/b.png', caption: '' },
    { url: 'https://img.example.com/c.webp', caption: '菜品' },
    { url: 'https://upload.wikimedia.org/a/Shuizhufish.jpg', caption: '门店图片路径' },
  ]);
  assert.deepEqual(data.restaurant, { name: '香香川菜' });
  const documented = { title: 'A', value: '1', fields: [{ label: 'x', value: 'y' }], items: [{ title: 't', detail: 'd' }] };
  assert.deepEqual(normalizeReport({ card: documented }).card, documented);
  assert.equal(normalizeReport({ card: { 备注: '' } }).card, undefined);
});

const HOOK = 'https://hooks.grok.test/automations/webhook/abc';

/** A registered car and, unless `connect` is false, its claim with a new webhook. */
async function paired(fixture, { label = 'TB323FU', connect = true, webhook = HOOK, key = 'user-hook-key' } = {}) {
  const made = (await fixture.request('POST', '/connect/devices', { body: { label } })).body;
  if (!connect) return { car: made };
  const claim = await fixture.request('POST', '/connect/claim', { body: { code: made.code, webhook_url: webhook, webhook_key: key } });
  return { car: made, claim };
}

test('a car registers, shows a code, and once claimed its tasks go to that user\'s own Grok', async t => {
  const fixture = await relay(t, { mcp: true });
  const { request, rpc, tool, deliveries } = fixture;
  const { car } = await paired(fixture, { connect: false });
  assert.match(car.code, /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/);
  assert.match(car.token, /^car_/);
  assert.equal(car.connect_url, `https://relay.test/connect?code=${car.code}`);
  assert.equal((await request('GET', '/connect/device', { token: car.token })).body.state, 'pending');
  assert.equal((await rpc('message/send', message('查天气'), car.token)).status, 403);

  const typed = car.code.toLowerCase().replace('-', ' ');
  const claim = await request('POST', '/connect/claim', { body: { code: typed, webhook_url: HOOK, webhook_key: 'user-hook-key' } });
  assert.equal(claim.status, 200);
  assert.match(claim.body.mcp_token, /^mcp_/);
  assert.equal(claim.body.mcp_url, 'https://relay.test/mcp');
  assert.equal(claim.body.device_label, 'TB323FU');
  const again = await request('POST', '/connect/claim', { body: { code: car.code, webhook_url: HOOK, webhook_key: 'x' } });
  assert.equal(again.status, 400);
  const status = (await request('GET', '/connect/device', { token: car.token })).body;
  assert.equal(status.state, 'connected');
  assert.equal(status.verified, false);

  const task = (await rpc('message/send', message('查天气'), car.token)).body.result;
  const sent = deliveries.at(-1);
  assert.equal(sent.target.webhookUrl, HOOK);
  assert.equal(sent.target.webhookKey, 'user-hook-key');
  assert.match(sent.payload.report_with, /car_task_report/);
  const userToken = claim.body.mcp_token;
  assert.equal((await tool('car_task_get', { task_id: task.id }, MCP)).isError, true);
  assert.equal((await tool('car_task_get', { task_id: task.id }, userToken)).structuredContent.instruction, '查天气');
  const owners = (await rpc('message/send', message('owner task'))).body.result;
  assert.equal((await tool('car_task_get', { task_id: owners.id }, userToken)).isError, true);
  assert.equal((await tool('car_task_report', { task_id: task.id, round: 1, state: 'completed', text: '晴' }, userToken)).isError, false);
  assert.equal((await rpc('tasks/get', { id: task.id }, car.token)).body.result.status.state, 'completed');
  assert.equal((await rpc('tasks/get', { id: task.id })).body.error.code, -32001);
  assert.equal((await request('GET', '/connect/device', { token: car.token })).body.verified, true);
});

test('claims check the code and the webhook, and a second car can join with the MCP token', async t => {
  const fixture = await relay(t);
  const { request, rpc, deliveries } = fixture;
  const { car } = await paired(fixture, { connect: false });
  const claim = body => request('POST', '/connect/claim', { body: { code: car.code, ...body } });
  assert.match((await claim({ code: 'ZZZZ-ZZZZ', webhook_url: HOOK, webhook_key: 'k' })).body.error, /配对码/);
  assert.match((await claim({ webhook_url: 'http://hooks.grok.test/x', webhook_key: 'k' })).body.error, /https/);
  assert.match((await claim({ webhook_url: 'https://evil.example.com/x', webhook_key: 'k' })).body.error, /grok\.test/);
  assert.match((await claim({ webhook_url: `https://${INSIDE}/x`, webhook_key: 'k' })).body.error, /内网/);
  assert.match((await claim({ webhook_url: HOOK, webhook_key: 'has space' })).body.error, /密钥/);
  assert.match((await claim({})).body.error, /Webhook/);
  assert.match((await claim({ mcp_token: 'mcp_wrong' })).body.error, /MCP 令牌/);
  const first = await claim({ webhook_url: HOOK, webhook_key: 'k1' });
  assert.equal(first.status, 200);

  const { car: second } = await paired(fixture, { label: '台架', connect: false });
  const joined = await request('POST', '/connect/claim', { body: { code: second.code, mcp_token: first.body.mcp_token } });
  assert.equal(joined.status, 200);
  assert.equal(joined.body.mcp_token, null);
  await rpc('message/send', message('从台架发'), second.token);
  assert.equal(deliveries.at(-1).target.webhookKey, 'k1');
  assert.equal(fixture.accounts.users.size, 1);
});

test('a pairing code expires, can be refreshed, and unclaimed cars are dropped after a day', async t => {
  const fixture = await relay(t);
  const { request } = fixture;
  const { car } = await paired(fixture, { connect: false });
  fixture.advance(16 * 60_000);
  assert.equal((await request('POST', '/connect/claim', { body: { code: car.code, webhook_url: HOOK, webhook_key: 'k' } })).status, 400);
  const fresh = (await request('POST', '/connect/device/code', { token: car.token })).body;
  assert.notEqual(fresh.code, car.code);
  assert.equal((await request('POST', '/connect/claim', { body: { code: fresh.code, webhook_url: HOOK, webhook_key: 'k' } })).status, 200);
  const { car: idle } = await paired(fixture, { connect: false });
  fixture.advance(25 * 3600_000);
  fixture.accounts.prune();
  assert.equal((await request('GET', '/connect/device', { token: idle.token })).status, 401);
  assert.equal((await request('GET', '/connect/device', { token: car.token })).body.state, 'connected');
});

test('disconnecting forgets the car, and the user goes with its last car', async t => {
  const fixture = await relay(t);
  const { request, rpc, mcpCall } = fixture;
  const { car, claim } = await paired(fixture);
  assert.equal((await mcpCall('tools/list', {}, claim.body.mcp_token)).status, 200);
  assert.deepEqual((await request('DELETE', '/connect/device', { token: car.token })).body, { ok: true });
  assert.equal((await request('GET', '/connect/device', { token: car.token })).status, 401);
  assert.equal((await rpc('message/send', message('还在吗'), car.token)).status, 401);
  assert.equal((await mcpCall('tools/list', {}, claim.body.mcp_token)).status, 401);
});

test('the page test sends a task to the user\'s Grok and shows its report', async t => {
  const fixture = await relay(t);
  const { request, tool, deliveries } = fixture;
  const { claim } = await paired(fixture);
  const token = claim.body.mcp_token;
  assert.equal((await request('POST', '/connect/test')).status, 401);
  const started = await request('POST', '/connect/test', { token });
  assert.equal(started.status, 202);
  assert.equal(started.body.state, 'working');
  assert.match(deliveries.at(-1).payload.instruction, /连接测试/);
  assert.equal(deliveries.at(-1).target.webhookUrl, HOOK);
  await tool('car_task_report', { task_id: started.body.task_id, round: 1, state: 'completed', text: '连接成功，可以开始用了' }, token);
  const done = await request('GET', `/connect/test/${started.body.task_id}`, { token });
  assert.deepEqual([done.body.state, done.body.text], ['completed', '连接成功，可以开始用了']);
  const { claim: other } = await paired(fixture, { label: '别人的车' });
  assert.equal((await request('GET', `/connect/test/${started.body.task_id}`, { token: other.body.mcp_token })).status, 404);
});

test('registrations and claims are limited per address', async t => {
  const fixture = await relay(t, { connectPerHour: 2 });
  const { request } = fixture;
  assert.equal((await request('POST', '/connect/devices', { body: {} })).status, 201);
  assert.equal((await request('POST', '/connect/devices', { body: {} })).status, 201);
  assert.equal((await request('POST', '/connect/devices', { body: {} })).status, 429);
  for (let i = 0; i < 2; i += 1) await request('POST', '/connect/claim', { body: { code: 'AAAA-AAAA', webhook_url: HOOK, webhook_key: 'k' } });
  assert.equal((await request('POST', '/connect/claim', { body: { code: 'AAAA-AAAA', webhook_url: HOOK, webhook_key: 'k' } })).status, 429);
});

test('the connect page carries the routine text under a nonce-only CSP', async t => {
  const { request } = await relay(t);
  const page = await request('GET', '/connect');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /text\/html/);
  const csp = page.headers.get('content-security-policy');
  const nonce = /script-src 'nonce-([^']+)'/.exec(csp)[1];
  assert.match(csp, /default-src 'none'/);
  assert.equal(page.headers.get('referrer-policy'), 'no-referrer');
  assert.ok(page.body.includes('car_task_report'));
  assert.ok(page.body.includes('UpdateRoutine'));
  assert.ok(page.body.includes('data-paste="webhook-key"'));
  assert.ok(!page.body.includes('{{'));
  assert.equal(page.body.split('<script').length, page.body.split(`<script nonce="${nonce}"`).length);
});

test('accounts survive a restart and keep tokens and codes only as hashes', t => {
  const dir = tempDir(t);
  const file = path.join(dir, 'accounts.json');
  const store = new AccountStore({ file });
  const { device, token, code } = store.registerDevice('车');
  const claimed = store.claim({ code, webhookUrl: HOOK, webhookKey: 'hook-key' });
  const saved = fs.readFileSync(file, 'utf8');
  // POSIX modes only: Windows reports 0666 for every file; the relay itself runs on Linux.
  if (process.platform !== 'win32') assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  for (const secret of [token, claimed.mcpToken, code, code.replace('-', '')]) assert.ok(!saved.includes(secret));
  const reloaded = new AccountStore({ file }).load();
  assert.equal(reloaded.deviceByToken(token).id, device.id);
  assert.equal(reloaded.userByMcpToken(claimed.mcpToken).webhookKey, 'hook-key');
});

test('config without the owner\'s own Grok needs only the public URL', () => {
  const config = loadConfig({ RELAY_PUBLIC_URL: 'https://relay.test' });
  assert.deepEqual([config.clientTokens, config.mcpTokens, config.webhookUrl, config.webhookHosts], [[], [], '', ['cursor.sh']]);
  assert.throws(() => loadConfig({ RELAY_PUBLIC_URL: 'https://relay.test', RELAY_MCP_TOKENS: MCP }), /只用于自带的 Grok/);
  assert.throws(() => loadConfig({ RELAY_PUBLIC_URL: 'https://relay.test', GROK_WEBHOOK_URL: 'https://grok.test/hook' }), /GROK_WEBHOOK_KEY 未设置/);
});

test('a car joins the owner\'s own Grok with the owner\'s MCP token', async t => {
  const fixture = await relay(t, { mcp: true });
  const { request, rpc, tool, deliveries } = fixture;
  const { car } = await paired(fixture, { connect: false });
  const claim = await request('POST', '/connect/claim', { body: { code: car.code, mcp_token: MCP } });
  assert.equal(claim.status, 200);
  assert.equal(claim.body.mcp_token, null);
  assert.equal(fixture.accounts.users.size, 0);
  const status = (await request('GET', '/connect/device', { token: car.token })).body;
  assert.deepEqual([status.state, status.verified], ['connected', true]);
  const task = (await rpc('message/send', message('查天气'), car.token)).body.result;
  assert.equal(deliveries.at(-1).target.webhookUrl, 'https://grok.test/hook');
  assert.equal((await tool('car_task_report', { task_id: task.id, round: 1, state: 'completed', text: '晴' })).isError, false);
  assert.equal((await rpc('tasks/get', { id: task.id }, car.token)).body.result.status.state, 'completed');
  const test = await request('POST', '/connect/test', { token: MCP });
  assert.equal(test.status, 202);
  assert.equal(deliveries.at(-1).target.webhookUrl, 'https://grok.test/hook');
  assert.equal((await request('GET', `/connect/test/${test.body.task_id}`, { token: MCP })).body.state, 'working');
  await request('DELETE', '/connect/device', { token: car.token });
  assert.equal((await rpc('tasks/get', { id: task.id })).body.error.code, -32001);
  assert.equal((await fixture.mcpCall('tools/list', {})).status, 200);
});

test('the bot lists its cars and can disconnect one or all of them', async t => {
  const fixture = await relay(t);
  const { request, tool, mcpCall, rpc } = fixture;
  const { car: first, claim } = await paired(fixture, { label: '平板' });
  const userToken = claim.body.mcp_token;
  const { car: second } = await paired(fixture, { label: '台架', connect: false });
  await request('POST', '/connect/claim', { body: { code: second.code, mcp_token: userToken } });
  const { claim: other } = await paired(fixture, { label: '别人的车' });

  const listed = await tool('car_list_connections', {}, userToken);
  assert.deepEqual(listed.structuredContent.cars.map(car => car.label).sort(), ['台架', '平板']);
  const firstId = listed.structuredContent.cars.find(car => car.label === '平板').car_id;
  assert.equal((await tool('car_disconnect', { car_id: 'car-nope' }, userToken)).isError, true);
  const othersCar = (await tool('car_list_connections', {}, other.body.mcp_token)).structuredContent.cars[0].car_id;
  assert.equal((await tool('car_disconnect', { car_id: othersCar }, userToken)).isError, true);

  const one = await tool('car_disconnect', { car_id: firstId }, userToken);
  assert.deepEqual(one.structuredContent, { removed: 1, remaining: 1 });
  assert.equal((await request('GET', '/connect/device', { token: first.token })).status, 401);
  assert.equal((await request('GET', '/connect/device', { token: second.token })).body.state, 'connected');

  const all = await tool('car_disconnect', {}, userToken);
  assert.deepEqual(all.structuredContent, { removed: 1, remaining: 0 });
  assert.match(all.content[0].text, /令牌已作废/);
  assert.equal((await rpc('message/send', message('还在吗'), second.token)).status, 401);
  assert.equal((await mcpCall('tools/list', {}, userToken)).status, 401);
  assert.equal((await tool('car_list_connections', {}, other.body.mcp_token)).structuredContent.cars.length, 1);
});

test('unpairing a user\'s last car asks the bot to clean up and report before the car goes', async t => {
  const fixture = await relay(t);
  const { request, rpc, tool, deliveries } = fixture;
  const { car, claim } = await paired(fixture);
  const userToken = claim.body.mcp_token;
  const started = await request('POST', '/connect/device/unpair', { token: car.token });
  assert.equal(started.status, 202);
  assert.equal(started.body.state, 'cleaning');
  assert.match(deliveries.at(-1).payload.instruction, /名为「车载语音助手任务」的例行任务/);
  assert.match(deliveries.at(-1).payload.instruction, /设备「TB323FU」已解除绑定/);
  assert.match(deliveries.at(-1).payload.notes, /每一轮汇报之后都要调用 WakeParent/);
  assert.match(deliveries.at(-1).payload.instruction, /UninstallMcpServer/);
  assert.match(deliveries.at(-1).payload.instruction, /已确认/);
  assert.equal(deliveries.at(-1).target.webhookUrl, HOOK);
  assert.equal((await tool('car_task_report', { task_id: started.body.task_id, round: 1, state: 'completed', text: '例行任务已删除，接着删连接器。' }, userToken)).isError, false);
  const done = (await rpc('tasks/get', { id: started.body.task_id }, car.token)).body.result;
  assert.equal(done.status.state, 'completed');
  assert.deepEqual((await request('DELETE', '/connect/device', { token: car.token })).body, { ok: true });
  assert.equal(fixture.accounts.users.size, 0);
});

test('unpairing goes at once while other cars remain or for the owner\'s own Grok', async t => {
  const fixture = await relay(t, { mcp: true });
  const { request, deliveries } = fixture;
  const { car: first, claim } = await paired(fixture);
  const { car: second } = await paired(fixture, { label: '台架', connect: false });
  await request('POST', '/connect/claim', { body: { code: second.code, mcp_token: claim.body.mcp_token } });
  const sent = deliveries.length;
  const shared = await request('POST', '/connect/device/unpair', { token: first.token });
  assert.deepEqual([shared.status, shared.body.state], [200, 'disconnected']);
  assert.match(shared.body.text, /还有 1 台设备/);
  assert.equal(deliveries.length, sent);
  assert.equal((await request('GET', '/connect/device', { token: first.token })).status, 401);

  const { car: owners } = await paired(fixture, { label: '自己的车', connect: false });
  await request('POST', '/connect/claim', { body: { code: owners.code, mcp_token: MCP } });
  const owner = await request('POST', '/connect/device/unpair', { token: owners.token });
  assert.deepEqual([owner.status, owner.body.text], [200, '已解除绑定。服务器自带的连接保留。']);
  const { car: unclaimed } = await paired(fixture, { label: '没连上', connect: false });
  assert.equal((await request('POST', '/connect/device/unpair', { token: unclaimed.token })).body.text, '已解除绑定。');
});

/** A browser-like call on the account page: JSON, an optional session cookie and Origin; the cookie it was given. */
async function pageCall(fixture, method, url, { cookie, body, origin } = {}) {
  const response = await fetch(`${fixture.base}${url}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}), ...(origin ? { Origin: origin } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookie = response.headers.get('set-cookie');
  const json = response.headers.get('content-type')?.includes('application/json');
  return { status: response.status, body: json ? await response.json() : await response.text(), setCookie, cookie: setCookie?.split(';')[0] || null };
}

const NAME = 'noharry';
const PASSWORD = 'correct horse battery';

test('an account registers and signs in, and its page needs the session cookie from its own origin', async t => {
  const fixture = await relay(t);
  const registered = await pageCall(fixture, 'POST', '/account/register', { body: { name: NAME, password: PASSWORD } });
  assert.equal(registered.status, 200);
  assert.match(registered.setCookie, /^relay_account=sess_[\w-]+; Path=\/account; HttpOnly; SameSite=Strict; Max-Age=\d+; Secure$/);
  assert.equal((await pageCall(fixture, 'GET', '/account/me')).status, 401);
  const me = (await pageCall(fixture, 'GET', '/account/me', { cookie: registered.cookie })).body;
  assert.deepEqual([me.name, me.connection, me.devices, me.tasks], [NAME, null, [], []]);

  assert.match((await pageCall(fixture, 'POST', '/account/register', { body: { name: 'NoHarry', password: PASSWORD } })).body.error, /已经有人用了/);
  assert.match((await pageCall(fixture, 'POST', '/account/register', { body: { name: 'x', password: PASSWORD } })).body.error, /用户名/);
  assert.match((await pageCall(fixture, 'POST', '/account/register', { body: { name: 'other', password: 'short' } })).body.error, /密码/);
  assert.equal((await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: 'wrong password' } })).status, 401);
  assert.equal((await pageCall(fixture, 'POST', '/account/login', { body: { name: 'nobody', password: PASSWORD } })).status, 401);
  const evil = await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD }, origin: 'https://evil.example' });
  assert.equal(evil.status, 403);

  const signedIn = await pageCall(fixture, 'POST', '/account/login', { body: { name: 'NOHARRY', password: PASSWORD }, origin: 'https://relay.test' });
  assert.equal(signedIn.status, 200);
  const out = await pageCall(fixture, 'POST', '/account/logout', { cookie: signedIn.cookie, body: {} });
  assert.match(out.setCookie, /Max-Age=0/);
  assert.equal((await pageCall(fixture, 'GET', '/account/me', { cookie: signedIn.cookie })).status, 401);
  assert.equal((await pageCall(fixture, 'GET', '/account/me', { cookie: registered.cookie })).status, 200);
});

test('a connected car hands its connection to a new account, and a second car joins by signing in', async t => {
  const fixture = await relay(t);
  const { request, rpc, tool, deliveries } = fixture;
  const { car, claim } = await paired(fixture);
  const signIn = await request('POST', '/connect/device/login', { token: car.token, body: { name: NAME, password: PASSWORD, register: true } });
  assert.deepEqual([signIn.status, signIn.body.account, signIn.body.state], [200, NAME, 'connected']);
  assert.equal(signIn.body.account_url, 'https://relay.test/account');
  assert.equal((await request('GET', '/connect/device', { token: car.token })).body.account, NAME);

  const { car: second } = await paired(fixture, { label: '台架', connect: false });
  assert.equal((await request('POST', '/connect/device/login', { token: second.token, body: { name: NAME, password: 'wrong password' } })).status, 401);
  const joined = await request('POST', '/connect/device/login', { token: second.token, body: { name: NAME, password: PASSWORD } });
  assert.deepEqual([joined.status, joined.body.state], [200, 'connected']);
  const task = (await rpc('message/send', message('从台架发'), second.token)).body.result;
  assert.equal(deliveries.at(-1).target.webhookUrl, HOOK);
  assert.equal((await tool('car_task_get', { task_id: task.id }, claim.body.mcp_token)).structuredContent.instruction, '从台架发');
  assert.equal(fixture.accounts.users.size, 1);

  const page = await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD } });
  const me = (await pageCall(fixture, 'GET', '/account/me', { cookie: page.cookie })).body;
  assert.deepEqual(me.devices.map(device => [device.label, device.signed_in]), [['TB323FU', true], ['台架', true]]);
  assert.equal(me.connection.webhook_host, 'hooks.grok.test');
  assert.deepEqual(me.tasks.map(entry => [entry.request, entry.device, entry.state]), [['从台架发', '台架', 'working']]);

  assert.deepEqual((await request('POST', '/connect/device/logout', { token: second.token })).body, { ok: true });
  const after = (await request('GET', '/connect/device', { token: second.token })).body;
  assert.deepEqual([after.state, after.account], ['connected', null]);
});

test('a connection another account holds is not handed over, and signing in moves a car into the account\'s connection', async t => {
  const fixture = await relay(t);
  const { request } = fixture;
  const { car: held } = await paired(fixture);
  await request('POST', '/connect/device/login', { token: held.token, body: { name: NAME, password: PASSWORD, register: true } });
  await pageCall(fixture, 'POST', '/account/register', { body: { name: 'second', password: PASSWORD } });
  const taken = await request('POST', '/connect/device/login', { token: held.token, body: { name: 'second', password: PASSWORD } });
  assert.equal(taken.status, 409);
  assert.match(taken.body.error, /另一个账号/);

  const { car: elsewhere } = await paired(fixture, { label: '另一台', webhook: 'https://hooks.grok.test/automations/webhook/other', key: 'k2' });
  assert.equal(fixture.accounts.users.size, 2);
  const moved = await request('POST', '/connect/device/login', { token: elsewhere.token, body: { name: NAME, password: PASSWORD } });
  assert.equal(moved.body.state, 'connected');
  // The connection the car left had no other car and no account: it is gone.
  assert.equal(fixture.accounts.users.size, 1);
  await fixture.rpc('message/send', message('换过来了'), elsewhere.token);
  assert.equal(fixture.deliveries.at(-1).target.webhookUrl, HOOK);
});

test('the account page adds a car by its pairing code, renames and removes cars, and keeps the connection', async t => {
  const fixture = await relay(t);
  const { request, deliveries } = fixture;
  const { car } = await paired(fixture);
  const page = await pageCall(fixture, 'POST', '/account/register', { body: { name: NAME, password: PASSWORD } });
  const cookie = page.cookie;
  const { car: fresh } = await paired(fixture, { label: '新平板', connect: false });
  assert.match((await pageCall(fixture, 'POST', '/account/devices/link', { cookie, body: { code: fresh.code } })).body.error, /还没有连上/);
  await request('POST', '/connect/device/login', { token: car.token, body: { name: NAME, password: PASSWORD } });
  assert.match((await pageCall(fixture, 'POST', '/account/devices/link', { cookie, body: { code: 'ZZZZ-ZZZZ' } })).body.error, /配对码/);
  const linked = await pageCall(fixture, 'POST', '/account/devices/link', { cookie, body: { code: fresh.code.toLowerCase() } });
  assert.deepEqual([linked.status, linked.body.label], [200, '新平板']);
  assert.equal((await request('GET', '/connect/device', { token: fresh.token })).body.state, 'connected');

  const renamed = await pageCall(fixture, 'POST', '/account/devices/rename', { cookie, body: { device_id: linked.body.id, label: '  客厅平板\u0007 ' } });
  assert.equal(renamed.body.label, '客厅平板');
  const { car: stranger } = await paired(fixture, { label: '别人的车', webhook: 'https://hooks.grok.test/automations/webhook/x', key: 'k3' });
  const strangerId = fixture.accounts.deviceByToken(stranger.token).id;
  assert.equal((await pageCall(fixture, 'POST', '/account/devices/remove', { cookie, body: { device_id: strangerId } })).status, 404);
  assert.equal((await pageCall(fixture, 'POST', '/account/devices/remove', { cookie, body: { device_id: linked.body.id } })).status, 200);
  assert.equal((await request('GET', '/connect/device', { token: fresh.token })).status, 401);

  const sent = deliveries.length;
  const last = await request('POST', '/connect/device/unpair', { token: car.token });
  assert.deepEqual([last.status, last.body.state], [200, 'disconnected']);
  assert.match(last.body.text, /账号「noharry」的连接保留/);
  assert.equal(deliveries.length, sent, 'the bot is not asked to remove a routine an account still holds');
  const me = (await pageCall(fixture, 'GET', '/account/me', { cookie })).body;
  assert.deepEqual([me.devices, me.connection.webhook_host], [[], 'hooks.grok.test']);
  const { car: back } = await paired(fixture, { label: '回来的车', connect: false });
  assert.equal((await request('POST', '/connect/device/login', { token: back.token, body: { name: NAME, password: PASSWORD } })).body.state, 'connected');
});

test('the account updates its routine webhook and rotates its MCP token', async t => {
  const fixture = await relay(t);
  const { request, rpc, mcpCall, deliveries } = fixture;
  const { car, claim } = await paired(fixture);
  await request('POST', '/connect/device/login', { token: car.token, body: { name: NAME, password: PASSWORD, register: true } });
  const { cookie } = await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD } });
  assert.match((await pageCall(fixture, 'POST', '/account/webhook', { cookie, body: { webhook_url: 'https://evil.example.com/x', webhook_key: 'k' } })).body.error, /grok\.test/);
  assert.match((await pageCall(fixture, 'POST', '/account/webhook', { cookie, body: { webhook_url: HOOK } })).body.error, /Webhook/);
  const NEW_HOOK = 'https://hooks.grok.test/automations/webhook/rebuilt';
  const changed = await pageCall(fixture, 'POST', '/account/webhook', { cookie, body: { webhook_url: NEW_HOOK, webhook_key: 'new-key' } });
  assert.deepEqual([changed.status, changed.body.webhook_host], [200, 'hooks.grok.test']);
  await rpc('message/send', message('换了 routine'), car.token);
  assert.deepEqual([deliveries.at(-1).target.webhookUrl, deliveries.at(-1).target.webhookKey], [NEW_HOOK, 'new-key']);

  const rotated = await pageCall(fixture, 'POST', '/account/mcp-token', { cookie, body: {} });
  assert.match(rotated.body.mcp_token, /^mcp_/);
  assert.match(rotated.body.message, /car-relay/);
  assert.ok(rotated.body.message.includes(rotated.body.mcp_token));
  assert.equal((await mcpCall('tools/list', {}, claim.body.mcp_token)).status, 401);
  assert.equal((await mcpCall('tools/list', {}, rotated.body.mcp_token)).status, 200);
});

test('an account holding the server owner\'s own Grok cannot change its webhook or token', async t => {
  const fixture = await relay(t, { mcp: true });
  const { request } = fixture;
  const { car } = await paired(fixture, { connect: false });
  await request('POST', '/connect/claim', { body: { code: car.code, mcp_token: MCP } });
  await request('POST', '/connect/device/login', { token: car.token, body: { name: NAME, password: PASSWORD, register: true } });
  const { cookie } = await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD } });
  assert.equal((await pageCall(fixture, 'GET', '/account/me', { cookie })).body.connection.server_config, true);
  assert.match((await pageCall(fixture, 'POST', '/account/mcp-token', { cookie, body: {} })).body.error, /relay\.env/);
  assert.match((await pageCall(fixture, 'POST', '/account/webhook', { cookie, body: { webhook_url: HOOK, webhook_key: 'k' } })).body.error, /relay\.env/);
});

test('sign-ins are limited per name, and a password change ends the other sessions', async t => {
  const fixture = await relay(t, { loginPerHour: 3 });
  const registered = await pageCall(fixture, 'POST', '/account/register', { body: { name: NAME, password: PASSWORD } });
  for (let tries = 0; tries < 2; tries += 1) {
    assert.equal((await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: 'guess guess' } })).status, 401);
  }
  assert.equal((await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD } })).status, 429);
  fixture.advance(3600_001);
  const other = await pageCall(fixture, 'POST', '/account/login', { body: { name: NAME, password: PASSWORD } });
  assert.equal(other.status, 200);
  assert.equal((await pageCall(fixture, 'POST', '/account/password', { cookie: registered.cookie, body: { password: 'nope nope', new_password: 'another secret' } })).status, 400);
  const changed = await pageCall(fixture, 'POST', '/account/password', { cookie: registered.cookie, body: { password: PASSWORD, new_password: 'another secret' } });
  assert.equal(changed.status, 200);
  assert.equal((await pageCall(fixture, 'GET', '/account/me', { cookie: other.cookie })).status, 401);
  assert.equal((await pageCall(fixture, 'GET', '/account/me', { cookie: changed.cookie })).status, 200);
});

test('accounts keep passwords and sessions only as hashes and survive a restart', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-accounts-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'accounts.json');
  const store = new AccountStore({ file });
  const { login } = await store.register(NAME, PASSWORD);
  const token = store.newSession(login);
  const saved = fs.readFileSync(file, 'utf8');
  assert.ok(!saved.includes(PASSWORD) && !saved.includes(token));
  assert.match(JSON.parse(saved).logins[0].passwordHash, /^scrypt\$16384\$8\$1\$/);
  // POSIX modes only: Windows reports 0666 for every file; the relay itself runs on Linux.
  if (process.platform !== 'win32') assert.equal((fs.statSync(file).mode & 0o777).toString(8), '600');
  const reloaded = new AccountStore({ file }).load();
  assert.equal(reloaded.sessionLogin(token).name, NAME);
  assert.equal((await reloaded.authenticate(NAME, PASSWORD)).id, login.id);
});

test('the account page is served under a nonce-only CSP and fills every slot', async t => {
  const fixture = await relay(t);
  const response = await fixture.request('GET', '/account');
  assert.equal(response.status, 200);
  const nonce = /script-src 'nonce-([\w-]+)'/.exec(response.headers.get('content-security-policy'))[1];
  assert.ok(response.body.includes(`<script nonce="${nonce}">`));
  assert.doesNotMatch(response.body, /\{\{[A-Z_]+\}\}/);
  assert.match(response.body, /设备账号/);
});

test('the car sees where the round stands: delivered, stalled, started, then working with the bot\'s steps', async t => {
  // The production round timeout (15 min), so the 90 s stall shows before the round expires.
  const fixture = await started(t, { mcp: true, taskTimeoutMs: 15 * 60_000 });
  const { rpc, tool, task, deliveries, advance } = fixture;
  const relayOf = async () => (await rpc('tasks/get', { id: task.id })).body.result;
  assert.equal((await relayOf()).metadata.relay.stage, 'delivered');
  assert.match(deliveries.at(-1).payload.notes, /data\.steps/);

  advance(91_000);
  const stalled = await relayOf();
  assert.equal(stalled.metadata.relay.stage, 'stalled');
  assert.match(stalled.status.message.parts[0].text, /还没开始处理/);

  await tool('car_task_get', { task_id: task.id });
  const read = await relayOf();
  assert.equal(read.metadata.relay.stage, 'started');
  assert.ok(read.metadata.relay.pickedUpAt);
  assert.equal(read.status.message.parts[0].text, DELIVERED_TEXT);

  const steps = [{ title: ' 查航班 ', status: 'done' }, { title: '比价格', status: 'doing' }, { title: '发邮件', status: 'later' }, { status: 'done' }];
  await tool('car_task_report', { task_id: task.id, round: 1, state: 'working', text: '正在比价格', data: { steps } });
  const working = await relayOf();
  assert.equal(working.metadata.relay.stage, 'working');
  assert.deepEqual(working.metadata.relay.steps, [
    { title: '查航班', status: 'done' }, { title: '比价格', status: 'doing' }, { title: '发邮件', status: 'todo' },
  ]);
  assert.equal(working.status.message.parts[0].text, '正在比价格');
  assert.deepEqual(working.status.message.parts[1].data.relay.steps, working.metadata.relay.steps);
  // A progress line without steps keeps the plan it had.
  await tool('car_task_report', { task_id: task.id, round: 1, state: 'working', text: '快好了' });
  assert.equal((await relayOf()).metadata.relay.steps.length, 3);

  await tool('car_task_report', { task_id: task.id, round: 1, state: 'completed', text: '订好了' });
  assert.equal((await relayOf()).metadata.relay.stage, 'completed');
});

test('a progress report counts as the bot having started even without reading the task first', async t => {
  const fixture = await started(t, { mcp: true });
  await fixture.tool('car_task_report', { task_id: fixture.task.id, round: 1, state: 'working', text: '在查了' });
  const relay = (await fixture.rpc('tasks/get', { id: fixture.task.id })).body.result.metadata.relay;
  assert.deepEqual([relay.stage, Boolean(relay.pickedUpAt), relay.steps], ['working', true, undefined]);
});
