'use strict';

// Connecting a car to its user's own Grok Bot. The car registers and shows a pairing code (and a QR code of the
// /connect link); on a phone the user opens /connect, enters the code and their Grok routine's webhook, and gets the
// MCP connector line to paste into Grok; a test task then proves the whole loop. Open to anyone who can reach the
// relay; registrations and claims are limited per address.
//   GET    /connect                 the page (HTML)
//   POST   /connect/devices         car registers {label} → {device_id, token, code, code_expires_at, connect_url}
//   GET    /connect/device          car (bearer car token) → {state: pending|connected, verified, …}
//   POST   /connect/device/code     car: a new pairing code
//   POST   /connect/device/unpair   car: unpair; the user's last car first asks the bot to remove its routine and
//                                   connector and report (the car follows that task, then calls DELETE)
//   DELETE /connect/device          car: disconnect and forget this car
//   POST   /connect/claim           page {code, webhook_url, webhook_key, mcp_token?} → the MCP connector details
//   POST   /connect/test            page (bearer MCP token): sends a test task to the user's Grok
//   GET    /connect/test/<task id>  page (bearer MCP token): that test task's state
// A car can also sign in to an account instead of being claimed (POST /connect/device/login, see account.cjs).
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes } = require('node:crypto');
const dns = require('node:dns/promises');
const { readJson, send, bearer, matchToken, clientIp, isObject } = require('./http.cjs');
const { OWNER } = require('./accounts.cjs');
const { publicHost } = require('./images.cjs');
const { ROUTINE_TEXT } = require('./grok.cjs');

const MAX_CONNECT_BYTES = 8 * 1024;
const MAX_KEY_CHARS = 512;
const TEST_PATH = /^\/connect\/test\/([0-9a-f-]{36})$/;
const CONNECTOR_NAME = 'car-relay';
const TEST_INSTRUCTION = '这是设备连接测试，不用做任何别的事：直接调用 car_task_report 汇报 state=completed，text 写「连接成功，可以开始用了」。';
/**
 * The last car of a user going away: the bot cleans up its side, says what it did, and leaves a note in the chat. The
 * connector goes after the report because afterwards the bot cannot report any more. The routine's rule holds back
 * deletions unless the instruction says 已确认; the user confirmed on the car, so the instruction says so (the first
 * bench run came back input-required without it, 2026-10-12).
 */
function unpairInstruction(label, at) {
  return [
    `设备「${label}」要和你解除绑定（${at}），这是最后一条委托。用户已经在设备上点了「解除绑定」并确认：已确认，下面的删除直接执行，不用再问。请按顺序做：`,
    '1. 删除（做不到就停用）名为「车载语音助手任务」的例行任务，也就是收到这条任务的例行任务（例如用 UpdateRoutine）。',
    '2. 调用 car_task_report 汇报：state=completed，text 用一两句话说清删掉了什么；哪一步需要用户批准而没做成，也写进去，不要一直等。',
    '3. 汇报之后，删除名为 car-relay 的 MCP 服务器（例如用 UninstallMcpServer；删掉以后就不能再汇报了）。',
    `4. 最后调用 WakeParent，让主聊天留一条记录：「设备「${label}」已解除绑定：<删掉了什么，还剩什么要我手动处理>」。`,
  ].join('\n');
}
const CLAIM_ERRORS = {
  code: '配对码不对或已过期，请在设备上刷新配对码后再试。',
  mcp_token: 'MCP 令牌不对。不填它就会新建一个连接。',
  webhook: '请填写 Webhook 地址和密钥。',
};

const iso = millis => (millis ? new Date(millis).toISOString() : null);
const connectUrl = (config, code) => `${config.publicUrl}/connect?code=${encodeURIComponent(code)}`;

/** Why `url` cannot be a routine webhook, or null: https on an allowed host that resolves to public addresses only. */
async function webhookProblem(url, hosts, lookup = dns.lookup) {
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return 'Webhook 地址格式不对。';
  }
  const host = parsed.hostname.toLowerCase();
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password) return 'Webhook 地址必须是 https 地址。';
  if (!hosts.some(allowed => host === allowed || host.endsWith(`.${allowed}`))) {
    return `Webhook 地址应该在 ${hosts.join('、')} 下，请从 Grok Bot 例行任务里复制。`;
  }
  try {
    if (!(await publicHost(host, lookup))) return 'Webhook 地址指向内网，不能使用。';
  } catch {
    return 'Webhook 地址的域名解析失败。';
  }
  return null;
}

function codeView(ctx, device, code) {
  return { code, code_expires_at: iso(device.codeExpiresAt), connect_url: connectUrl(ctx.config, code) };
}

function deviceStatus(ctx, device) {
  const connected = ctx.accounts.connected(device);
  return {
    state: connected ? 'connected' : 'pending',
    agent_name: ctx.config.agentName,
    label: device.label,
    connected_at: connected ? iso(device.connectedAt) : null,
    // The owner's own Grok has been answering through this relay all along.
    verified: device.userId === OWNER || Boolean(ctx.accounts.user(device.userId)?.verifiedAt),
    code_expires_at: iso(device.codeExpiresAt),
    account: ctx.accounts.login(device.loginId)?.name || null,
  };
}

async function registerDevice(ctx, req, res) {
  if (!ctx.connectLimit.take(`register:${clientIp(req)}`)) return send(res, 429, { error: '注册太频繁，请稍后再试。' });
  const body = await readJson(req, MAX_CONNECT_BYTES);
  const made = ctx.accounts.registerDevice(isObject(body) ? body.label : '');
  if (!made) return send(res, 503, { error: '中转服务的设备数量已满。' });
  ctx.log(`device ${made.device.id} registered`);
  send(res, 201, { device_id: made.device.id, token: made.token, ...codeView(ctx, made.device, made.code) });
}

function carDevice(ctx, req, res) {
  const device = ctx.accounts.deviceByToken(bearer(req));
  if (!device) send(res, 401, { error: '设备令牌不对，请重新连接。' });
  return device;
}

function deviceRoute(ctx, req, res) {
  const device = carDevice(ctx, req, res);
  if (!device) return;
  if (req.method === 'GET') return send(res, 200, deviceStatus(ctx, device));
  ctx.accounts.removeDevice(device);
  ctx.log(`device ${device.id} disconnected`);
  send(res, 200, { ok: true });
}

/**
 * Unpairs the car. When it is a user's last car the bot is asked to clean up first: the answer names the task the car
 * follows over /a2a before it calls DELETE. Otherwise (other cars remain, the owner's Grok, a connection an account
 * holds, or never connected) the car goes at once and the bot's side stays.
 */
async function unpair(ctx, req, res) {
  const device = carDevice(ctx, req, res);
  if (!device) return;
  const others = ctx.accounts.devicesOf(device.userId).length - 1;
  const holder = ctx.accounts.loginOf(device.userId);
  if (!ctx.accounts.connected(device) || device.userId === OWNER || others > 0 || holder) {
    ctx.accounts.removeDevice(device);
    ctx.log(`device ${device.id} unpaired`);
    const kept = holder ? `账号「${holder.name}」的连接保留，在别的设备上登录这个账号就能接着用。`
      : device.userId === OWNER ? '服务器自带的连接保留。' : others > 0 ? `还有 ${others} 台设备连着 ${ctx.config.agentName}，它那边的设置保留。` : '';
    return send(res, 200, { state: 'disconnected', text: `已解除绑定。${kept}` });
  }
  const at = new Date(ctx.now()).toISOString();
  const instruction = unpairInstruction(device.label, at);
  const task = ctx.store.create({ instruction, context: { source: 'unpair' }, client: device.id, user: device.userId });
  await ctx.startRound(task);
  ctx.log(`device ${device.id} unpairing with task ${task.id}`);
  send(res, 202, { state: 'cleaning', task_id: task.id, text: task.statusText });
}

function refreshCode(ctx, req, res) {
  const device = carDevice(ctx, req, res);
  if (!device) return;
  if (!ctx.connectLimit.take(`code:${device.id}`)) return send(res, 429, { error: '刷新太频繁，请稍后再试。' });
  send(res, 200, codeView(ctx, device, ctx.accounts.newCode(device)));
}

async function claim(ctx, req, res) {
  if (!ctx.connectLimit.take(`claim:${clientIp(req)}`)) return send(res, 429, { error: '尝试太频繁，请一小时后再试。' });
  const body = await readJson(req, MAX_CONNECT_BYTES);
  if (!isObject(body)) return send(res, 400, { error: '请求格式不对。' });
  const field = name => (typeof body[name] === 'string' ? body[name].trim() : '');
  const [webhookUrl, webhookKey, mcpToken] = [field('webhook_url'), field('webhook_key'), field('mcp_token')];
  const toOwner = matchToken(ctx.config.mcpTokens, mcpToken) >= 0;
  if (!toOwner && (webhookUrl || webhookKey || !mcpToken)) {
    if (!webhookUrl || !webhookKey) return send(res, 400, { error: CLAIM_ERRORS.webhook });
    if (webhookKey.length > MAX_KEY_CHARS || /\s/.test(webhookKey)) return send(res, 400, { error: 'Webhook 密钥格式不对。' });
    const problem = await webhookProblem(webhookUrl, ctx.config.webhookHosts, ctx.lookup);
    if (problem) return send(res, 400, { error: problem });
  }
  const claimed = ctx.accounts.claim({ code: field('code'), webhookUrl, webhookKey, mcpToken, toOwner });
  if (claimed.error) return send(res, 400, { error: CLAIM_ERRORS[claimed.error] });
  ctx.log(`device ${claimed.device.id} connected to user ${claimed.user.id}${claimed.mcpToken ? ' (new)' : ''}`);
  send(res, 200, {
    agent_name: ctx.config.agentName, device_label: claimed.device.label, connector_name: CONNECTOR_NAME,
    mcp_url: `${ctx.config.publicUrl}/mcp`, mcp_token: claimed.mcpToken,
  });
}

/** The user a page's MCP token belongs to (the owner's own token included), or null after answering 401. */
function connectionUser(ctx, req, res) {
  const token = bearer(req);
  const user = matchToken(ctx.config.mcpTokens, token) >= 0 ? { id: OWNER } : ctx.accounts.userByMcpToken(token);
  if (!user) send(res, 401, { error: 'MCP 令牌不对。' });
  return user;
}

const testView = task => ({ task_id: task.id, state: task.state, text: task.result?.text || task.statusText });

async function startTest(ctx, req, res) {
  const user = connectionUser(ctx, req, res);
  if (!user) return;
  if (!ctx.limit.take(`test:${user.id}`)) return send(res, 429, { error: '测试太频繁，请稍后再试。' });
  const task = ctx.store.create({ instruction: TEST_INSTRUCTION, context: { source: 'connect-test' }, client: `test:${user.id}`, user: user.id });
  await ctx.startRound(task);
  send(res, 202, testView(task));
}

function testStatus(ctx, req, res, pathname) {
  const user = connectionUser(ctx, req, res);
  if (!user) return;
  const task = ctx.store.get(TEST_PATH.exec(pathname)[1]);
  if (!task || task.client !== `test:${user.id}`) return send(res, 404, { error: '没有这个测试。' });
  send(res, 200, testView(task));
}

function page(ctx, req, res) {
  const nonce = randomBytes(16).toString('base64url');
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; ` +
      "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(connectPage(ctx.config, nonce));
}

/** The handler for a /connect route, or null when the method and path are not one. */
function connectHandler(method, pathname) {
  if (method === 'GET' && pathname === '/connect') return page;
  if (method === 'POST' && pathname === '/connect/devices') return registerDevice;
  if (pathname === '/connect/device' && (method === 'GET' || method === 'DELETE')) return deviceRoute;
  if (method === 'POST' && pathname === '/connect/device/code') return refreshCode;
  if (method === 'POST' && pathname === '/connect/device/unpair') return unpair;
  if (method === 'POST' && pathname === '/connect/claim') return claim;
  if (method === 'POST' && pathname === '/connect/test') return startTest;
  if (method === 'GET' && TEST_PATH.test(pathname)) return testStatus;
  return null;
}

/**
 * The first message the user sends to their bot: build (or update) the routine and hand the user the panel links for
 * its webhook address and key. Grok never shows those two values to the bot (asked through the relay, 2026-10-12:
 * it only gets grokbot:// links to the panel), so the user copies them into this page, which claims the car; the
 * page then gives a second message that adds the MCP connector. No shell command, so no approval for a curl.
 */
function setupMessage() {
  return [
    '请帮我把设备连到你这里（一次性设置）：',
    '1. 用 UpdateRoutine 新建一个例行任务，名字叫「车载语音助手任务」，触发方式选 Webhook，指令用文末「例行任务指令」的原文，一字不改；' +
      '已经有同名的例行任务就不要再建，把它的指令更新成这段原文，继续用它。',
    '2. 建好后，把这个例行任务面板里 Webhook 地址和 Webhook 密钥的链接（grokbot:// 开头）发给我，我自己去复制。你看不到这两个值，不用尝试读取。',
    `3. 之后我会再发给你一句添加 MCP 服务器（${CONNECTOR_NAME}）的话，照做就行。`,
    '',
    '例行任务指令：',
    ROUTINE_TEXT,
  ].join('\n');
}

const escapeHtml = text => text.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);

const PAGE = fs.readFileSync(path.join(__dirname, 'connect.html'), 'utf8');

/** connect.html with this relay's values; every `{{NAME}}` slot in it must be one of these. */
function connectPage(config, nonce) {
  const values = {
    NONCE: nonce, NAME: escapeHtml(config.agentName), CONNECTOR: CONNECTOR_NAME,
    ROUTINE: escapeHtml(ROUTINE_TEXT), SETUP: escapeHtml(setupMessage()),
  };
  return PAGE.replace(/\{\{([A-Z_]+)\}\}/g, (slot, key) => {
    if (!(key in values)) throw new Error(`connect.html has an unknown slot ${slot}`);
    return values[key];
  });
}

module.exports = { connectHandler, webhookProblem, connectPage, carDevice, CONNECTOR_NAME };
