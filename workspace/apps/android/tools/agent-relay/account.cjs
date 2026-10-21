'use strict';

// Accounts (owner request 2026-10-12): a name and password that hold one connection to the user's own Grok, so a
// second car signs in instead of copying the MCP token, and a connection can be looked after without SSH.
//   GET    /account                     the page (HTML)
//   POST   /account/register            page {name, password} → session cookie
//   POST   /account/login               page {name, password} → session cookie
//   POST   /account/logout              page
//   GET    /account/me                  page: the account, its connection, cars and recent tasks
//   POST   /account/password            page {password, new_password}; every session ends
//   POST   /account/devices/link        page {code}: the car showing that pairing code joins the connection
//   POST   /account/devices/rename      page {device_id, label}
//   POST   /account/devices/remove      page {device_id}: the car is forgotten; the connection stays
//   POST   /account/webhook             page {webhook_url, webhook_key}: the routine's new address and key
//   POST   /account/mcp-token           page: a new MCP token, shown once, with the line for the Grok connector
//   POST   /connect/device/login        car (bearer car token) {name, password, register?}: sign in, joining or
//                                       handing over the connection (see AccountStore.signInDevice)
//   POST   /connect/device/logout       car: leave the account; the car stays connected
// The page's session is an HttpOnly, SameSite=Strict cookie on /account; account POSTs from a browser must come from
// the relay's own origin. Sign-ins are limited per address and per name.
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes } = require('node:crypto');
const { readJson, send, clientIp, isObject } = require('./http.cjs');
const { OWNER, nameKey } = require('./accounts.cjs');
const { webhookProblem, carDevice, CONNECTOR_NAME } = require('./connect.cjs');

const MAX_ACCOUNT_BYTES = 8 * 1024;
const MAX_KEY_CHARS = 512;
const COOKIE = 'relay_account';
const SESSION_MAX_AGE_S = 30 * 24 * 3600;
const RECENT_TASKS = 30;
const ERRORS = {
  name: '用户名 2–32 个字符，只能用字母、数字、汉字和 _ . -。',
  name_taken: '这个用户名已经有人用了。',
  password: '密码 8–128 个字符。',
  password_wrong: '原密码不对。',
  full: '中转服务的账号数量已满。',
  credentials: '用户名或密码不对。',
  connection_taken: '这台设备连着的 Grok 已经属于另一个账号，请用那个账号登录。',
  code: '配对码不对或已过期，请在设备上刷新配对码后再试。',
  no_connection: '这个账号还没有连上 Grok：先在一台设备上连接，或者在一台已经连接的设备上登录这个账号。',
  server_config: '这是服务器自带的连接，Webhook 和令牌在服务器的 relay.env 里改。',
  device: '这台设备不在这个账号里。',
  signed_out: '请先登录。',
  origin: '请从账号页操作。',
  busy: '尝试太频繁，请一小时后再试。',
};

const iso = millis => (millis ? new Date(millis).toISOString() : null);
const clip = (text, limit) => (typeof text === 'string' && text.length > limit ? `${text.slice(0, limit)}…` : text || '');
const fail = (res, status, key) => send(res, status, { error: ERRORS[key] || key });

function cookies(req) {
  const found = {};
  for (const part of (req.headers.cookie || '').split(';')) {
    const at = part.indexOf('=');
    if (at > 0) found[part.slice(0, at).trim()] = part.slice(at + 1).trim();
  }
  return found;
}

function sessionCookie(ctx, token, maxAge = SESSION_MAX_AGE_S) {
  const secure = ctx.config.publicUrl.startsWith('https:') ? '; Secure' : '';
  return `${COOKIE}=${token}; Path=/account; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secure}`;
}

/** A browser's POST must come from the relay's own page (SameSite already keeps the cookie off other sites' requests). */
function sameOrigin(ctx, req) {
  const origin = req.headers.origin;
  return !origin || origin === new URL(ctx.config.publicUrl).origin;
}

/** The signed-in account, or null after answering 401. */
function pageLogin(ctx, req, res) {
  const login = ctx.accounts.sessionLogin(cookies(req)[COOKIE]);
  if (!login) fail(res, 401, 'signed_out');
  return login;
}

async function body(req) {
  const parsed = await readJson(req, MAX_ACCOUNT_BYTES);
  return isObject(parsed) ? parsed : {};
}

const text = (parsed, name) => (typeof parsed[name] === 'string' ? parsed[name] : '');

/** Sign-in attempts per address and per name. */
function allowed(ctx, req, name) {
  return ctx.loginLimit.take(`ip:${clientIp(req)}`) && (!name || ctx.loginLimit.take(`name:${name.trim().toLowerCase()}`));
}

async function signIn(ctx, req, res, registering) {
  if (!sameOrigin(ctx, req)) return fail(res, 403, 'origin');
  const parsed = await body(req);
  const [name, password] = [text(parsed, 'name'), text(parsed, 'password')];
  if (!allowed(ctx, req, registering ? '' : name)) return fail(res, 429, 'busy');
  let login;
  if (registering) {
    const made = await ctx.accounts.register(name, password);
    if (made.error) return fail(res, 400, made.error);
    login = made.login;
    ctx.log(`account ${login.id} registered`);
  } else {
    login = await ctx.accounts.authenticate(name, password);
    if (!login) return fail(res, 401, 'credentials');
  }
  const token = ctx.accounts.newSession(login);
  res.setHeader('Set-Cookie', sessionCookie(ctx, token));
  send(res, 200, { name: login.name });
}

function logout(ctx, req, res) {
  if (!sameOrigin(ctx, req)) return fail(res, 403, 'origin');
  ctx.accounts.endSession(cookies(req)[COOKIE]);
  res.setHeader('Set-Cookie', sessionCookie(ctx, '', 0));
  send(res, 200, { ok: true });
}

/** How the connection is doing, from its recent tasks: the last delivery and the last report. */
function connectionView(ctx, login) {
  if (!login.userId) return null;
  const owner = login.userId === OWNER;
  const user = owner ? null : ctx.accounts.user(login.userId);
  if (!owner && !user) return null;
  const tasks = ctx.store.tasksOf(login.userId);
  const lastFailure = tasks.find(task => task.state === 'failed');
  const lastAnswer = tasks.find(task => task.result && task.state !== 'failed');
  let webhookHost = null;
  try {
    webhookHost = new URL(owner ? ctx.config.webhookUrl : user.webhookUrl).host;
  } catch {
    webhookHost = null;
  }
  return {
    agent_name: ctx.config.agentName,
    server_config: owner,
    webhook_host: webhookHost,
    verified_at: owner ? null : iso(user.verifiedAt),
    last_answer_at: iso(lastAnswer?.updatedAt),
    last_failure: lastFailure ? { at: iso(lastFailure.updatedAt), text: clip(lastFailure.statusText, 200) } : null,
  };
}

function me(ctx, req, res) {
  const login = pageLogin(ctx, req, res);
  if (!login) return;
  const devices = login.userId ? ctx.accounts.devicesOf(login.userId) : [];
  const labels = new Map(devices.map(device => [device.id, device.label]));
  const tasks = login.userId ? ctx.store.tasksOf(login.userId).slice(0, RECENT_TASKS) : [];
  send(res, 200, {
    name: login.name,
    connection: connectionView(ctx, login),
    devices: devices.map(device => ({
      id: device.id, label: device.label, connected_at: iso(device.connectedAt), seen_at: iso(device.seenAt),
      signed_in: device.loginId === login.id,
    })),
    tasks: tasks.map(task => ({
      id: task.id, at: iso(task.createdAt), updated_at: iso(task.updatedAt), state: task.state, round: task.round,
      device: labels.get(task.client) || (String(task.client).startsWith('test:') ? '连接页测试' : '已移除的设备'),
      request: clip(task.instruction, 80), answer: clip(task.result?.text || task.statusText, 160),
    })),
  });
}

async function changePassword(ctx, req, res) {
  if (!sameOrigin(ctx, req)) return fail(res, 403, 'origin');
  const login = pageLogin(ctx, req, res);
  if (!login) return;
  if (!allowed(ctx, req, login.name)) return fail(res, 429, 'busy');
  const parsed = await body(req);
  const changed = await ctx.accounts.changePassword(login, text(parsed, 'password'), text(parsed, 'new_password'));
  if (changed.error) return fail(res, 400, changed.error);
  // Every session ended with the old password; this page signs in again.
  const token = ctx.accounts.newSession(login);
  res.setHeader('Set-Cookie', sessionCookie(ctx, token));
  send(res, 200, { ok: true });
}

/** A page POST for the signed-in account: same origin, signed in, then `action(login, body)`. */
function accountAction(action) {
  return async (ctx, req, res) => {
    if (!sameOrigin(ctx, req)) return fail(res, 403, 'origin');
    const login = pageLogin(ctx, req, res);
    if (!login) return;
    await action(ctx, req, res, login, await body(req));
  };
}

const linkDevice = accountAction((ctx, req, res, login, parsed) => {
  if (!ctx.connectLimit.take(`link:${clientIp(req)}`)) return fail(res, 429, 'busy');
  const linked = ctx.accounts.linkByCode(login, text(parsed, 'code'));
  if (linked.error) return fail(res, 400, linked.error);
  ctx.log(`device ${linked.device.id} joined account ${login.id}`);
  send(res, 200, { id: linked.device.id, label: linked.device.label });
});

const renameDevice = accountAction((ctx, req, res, login, parsed) => {
  const device = ctx.accounts.deviceOf(login, text(parsed, 'device_id'));
  if (!device) return fail(res, 404, 'device');
  send(res, 200, { id: device.id, label: ctx.accounts.renameDevice(device, text(parsed, 'label')).label });
});

const removeDevice = accountAction((ctx, req, res, login, parsed) => {
  const device = ctx.accounts.deviceOf(login, text(parsed, 'device_id'));
  if (!device) return fail(res, 404, 'device');
  ctx.accounts.removeDevice(device);
  ctx.log(`device ${device.id} removed by account ${login.id}`);
  send(res, 200, { ok: true });
});

const setWebhook = accountAction(async (ctx, req, res, login, parsed) => {
  const [webhookUrl, webhookKey] = [text(parsed, 'webhook_url').trim(), text(parsed, 'webhook_key').trim()];
  if (!webhookUrl || !webhookKey) return send(res, 400, { error: '请填写 Webhook 地址和密钥。' });
  if (webhookKey.length > MAX_KEY_CHARS || /\s/.test(webhookKey)) return send(res, 400, { error: 'Webhook 密钥格式不对。' });
  const problem = await webhookProblem(webhookUrl, ctx.config.webhookHosts, ctx.lookup);
  if (problem) return send(res, 400, { error: problem });
  const set = ctx.accounts.setWebhook(login, webhookUrl, webhookKey);
  if (set.error) return fail(res, 400, set.error);
  ctx.log(`account ${login.id} changed its webhook`);
  send(res, 200, { webhook_host: new URL(webhookUrl).host });
});

const rotateMcpToken = accountAction((ctx, req, res, login) => {
  const rotated = ctx.accounts.rotateMcpToken(login);
  if (rotated.error) return fail(res, 400, rotated.error);
  ctx.log(`account ${login.id} rotated its MCP token`);
  send(res, 200, {
    mcp_token: rotated.token,
    message: `请把名为 ${CONNECTOR_NAME} 的自定义 MCP 服务器的请求头改成 Authorization: Bearer ${rotated.token}` +
      `（URL 仍是 ${ctx.config.publicUrl}/mcp，不用 OAuth）。改好后不用回复别的。`,
  });
});

/** A car signs in (or registers) with the account's name and password; it joins or hands over the connection. */
async function deviceLogin(ctx, req, res) {
  const device = carDevice(ctx, req, res);
  if (!device) return;
  const parsed = await body(req);
  const [name, password] = [text(parsed, 'name'), text(parsed, 'password')];
  const registering = parsed.register === true;
  if (!allowed(ctx, req, registering ? '' : name)) return fail(res, 429, 'busy');
  let login;
  if (registering) {
    const made = await ctx.accounts.register(name, password);
    if (made.error) return fail(res, 400, made.error);
    login = made.login;
  } else {
    login = await ctx.accounts.authenticate(name, password);
    if (!login) return fail(res, 401, 'credentials');
  }
  const signed = ctx.accounts.signInDevice(device, login);
  if (signed.error) return fail(res, 409, signed.error);
  ctx.log(`device ${device.id} signed in to account ${login.id}${registering ? ' (new)' : ''}`);
  send(res, 200, { account: login.name, state: ctx.accounts.connected(device) ? 'connected' : 'pending', account_url: `${ctx.config.publicUrl}/account` });
}

function deviceLogout(ctx, req, res) {
  const device = carDevice(ctx, req, res);
  if (!device) return;
  ctx.accounts.signOutDevice(device);
  send(res, 200, { ok: true });
}

/** The account signed in on this browser, or null. */
const signedIn = (ctx, req) => ctx.accounts.sessionLogin(cookies(req)[COOKIE]);

function page(ctx, req, res) {
  servePage(res, nonce => accountPage(ctx.config, nonce));
}

/** An HTML page under a strict policy: only its own nonce-tagged script and style run, and it only talks to this relay. */
function servePage(res, render) {
  const nonce = randomBytes(16).toString('base64url');
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; ` +
      "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(render(nonce));
}

const escapeHtml = value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const PAGE = fs.readFileSync(path.join(__dirname, 'account.html'), 'utf8');

/** account.html with this relay's values; every `{{NAME}}` slot in it must be one of these. */
function accountPage(config, nonce) {
  const values = { NONCE: nonce, NAME: escapeHtml(config.agentName), CONNECTOR: CONNECTOR_NAME };
  return PAGE.replace(/\{\{([A-Z_]+)\}\}/g, (slot, key) => {
    if (!(key in values)) throw new Error(`account.html has an unknown slot ${slot}`);
    return values[key];
  });
}

const ROUTES = {
  'GET /account': page,
  'POST /account/register': (ctx, req, res) => signIn(ctx, req, res, true),
  'POST /account/login': (ctx, req, res) => signIn(ctx, req, res, false),
  'POST /account/logout': logout,
  'GET /account/me': me,
  'POST /account/password': changePassword,
  'POST /account/devices/link': linkDevice,
  'POST /account/devices/rename': renameDevice,
  'POST /account/devices/remove': removeDevice,
  'POST /account/webhook': setWebhook,
  'POST /account/mcp-token': rotateMcpToken,
  'POST /connect/device/login': deviceLogin,
  'POST /connect/device/logout': deviceLogout,
};

/** The handler for an account route, or null. */
function accountHandler(method, pathname) {
  return ROUTES[`${method} ${pathname}`] || null;
}

module.exports = { accountHandler, accountPage, signedIn, sameOrigin, servePage, escapeHtml, ACCOUNT_ERRORS: ERRORS };
