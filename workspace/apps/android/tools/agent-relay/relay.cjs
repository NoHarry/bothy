'use strict';

// Agent relay: an A2A 0.3 JSON-RPC face for each user's own Grok Bot. The car sends a task; the relay calls that
// user's Grok routine webhook and answers at once with a working task; Grok reports back when done and the car polls
// tasks/get. Routes: GET /health, GET /.well-known/agent-card.json, POST /a2a (car, bearer), POST /mcp (the user's
// Grok connector, bearer: car_task_get / car_task_report), POST /cb/<task id> (shell fallback, round token),
// GET /files/<task id>/<name> (car, bearer: pictures a report attached), /connect… (pairing, see connect.cjs),
// /account… (accounts holding a connection, see account.cjs).
// Cars and users come from the /connect flow (accounts.cjs); the server owner's own Grok and car tokens from relay.env
// still work as the user "owner".
const http = require('node:http');
const path = require('node:path');
const { readSettings, loadConfig, dataPaths } = require('./config.cjs');
const { TaskStore } = require('./tasks.cjs');
const { AccountStore, OWNER } = require('./accounts.cjs');
const { deliver, NOTES } = require('./grok.cjs');
const { handleMcp } = require('./mcp.cjs');
const { connectHandler } = require('./connect.cjs');
const { accountHandler } = require('./account.cjs');
const { attachImages, storedImage } = require('./images.cjs');
const { normalizeReport } = require('./report.cjs');
const { BodyTooLarge, readBody, send, bearer, tokenIndex, HourlyLimit, isObject } = require('./http.cjs');
const wire = require('./wire.cjs');

const MAX_RPC_BYTES = 256 * 1024;
const MAX_CALLBACK_BYTES = 64 * 1024;
const MAX_INSTRUCTION_CHARS = 8000;
const SWEEP_MS = 30_000;
const CARD_PATHS = new Set(['/.well-known/agent-card.json', '/.well-known/agent.json']);
const CALLBACK_PATH = /^\/cb\/([0-9a-f-]{36})$/;
const FILE_PATH = /^\/files\/([0-9a-f-]{36})\/([^/]+)$/;
const CALLBACK_REPLIES = { 200: 'ok', 400: 'bad callback body', 401: 'unauthorized', 404: 'unknown task', 409: 'task closed' };

/** The car behind a request: `{ client, user }` (user null until the car is connected), or null for an unknown token. */
function carFor(ctx, req) {
  const index = tokenIndex(req, ctx.config.clientTokens);
  if (index >= 0) return { client: `client-${index}`, user: OWNER };
  const device = ctx.accounts.deviceByToken(bearer(req));
  return device ? { client: device.id, user: ctx.accounts.connected(device) ? device.userId : null } : null;
}

/** Where a user's rounds go; null once the user is gone (the car disconnected). */
function targetFor(ctx, userId) {
  const { config } = ctx;
  if (userId === OWNER) {
    return config.webhookUrl
      ? { publicUrl: config.publicUrl, webhookUrl: config.webhookUrl, webhookKey: config.webhookKey, reportWithMcp: config.mcpTokens.length > 0 }
      : null;
  }
  const user = ctx.accounts.user(userId);
  return user ? { publicUrl: config.publicUrl, webhookUrl: user.webhookUrl, webhookKey: user.webhookKey, reportWithMcp: true } : null;
}

/** Opens the task's next round and hands it to the user's Grok; a round that cannot be delivered fails the task. */
async function startRound(ctx, task) {
  const token = ctx.store.openRound(task);
  const target = targetFor(ctx, task.user || OWNER);
  const delivery = target ? await ctx.deliver(target, task, token) : { ok: false, reason: '设备和 Grok 已解除绑定，请在设备上重新连接' };
  if (!delivery.ok) ctx.store.failDelivery(task, delivery.reason);
  ctx.log(`task ${task.id} round ${task.round} ${delivery.ok ? 'delivered' : `not delivered: ${delivery.reason}`}`);
}

/** The task a new message starts, or the input-required task it answers; an error envelope when neither fits. */
function taskFor(ctx, id, message, text, car) {
  if (!message.taskId) {
    const context = isObject(message.metadata?.context) ? message.metadata.context : null;
    return { task: ctx.store.create({ instruction: text, contextId: message.contextId, context, client: car.client, user: car.user }) };
  }
  const found = ctx.store.get(message.taskId);
  if (!found || found.client !== car.client) return { error: wire.rpcError(id, 'taskNotFound') };
  const answered = ctx.store.answer(found.id, text);
  return answered.error ? { error: wire.rpcError(id, 'invalidParams', 'task is not waiting for input') } : answered;
}

async function sendMessage(ctx, id, params, car) {
  const text = wire.messageText(params.message);
  if (!text) return wire.rpcError(id, 'invalidParams', 'message needs text');
  if (text.length > MAX_INSTRUCTION_CHARS) return wire.rpcError(id, 'invalidParams', 'message too long');
  if (!ctx.limit.take(car.client)) return wire.rpcError(id, 'rateLimited');
  const { task, error } = taskFor(ctx, id, params.message, text, car);
  if (error) return error;
  await startRound(ctx, task);
  return wire.rpcResult(id, wire.wireTask(task, ctx.now()));
}

function ownTask(ctx, params, client) {
  const task = typeof params.id === 'string' ? ctx.store.get(params.id) : null;
  return task && task.client === client ? task : null;
}

function cancelTask(ctx, id, params, client) {
  const task = ownTask(ctx, params, client);
  if (!task) return wire.rpcError(id, 'taskNotFound');
  const canceled = ctx.store.cancel(task.id);
  if (canceled.error) return wire.rpcError(id, 'taskNotCancelable');
  ctx.log(`task ${task.id} canceled by caller`);
  return wire.rpcResult(id, wire.wireTask(canceled.task, ctx.now()));
}

async function dispatch(ctx, body, car) {
  const { client } = car;
  let request;
  try {
    request = JSON.parse(body);
  } catch {
    return wire.rpcError(null, 'parse');
  }
  if (!isObject(request) || request.jsonrpc !== '2.0' || typeof request.method !== 'string') {
    return wire.rpcError(request?.id, 'invalidRequest');
  }
  const params = isObject(request.params) ? request.params : {};
  switch (request.method) {
    case 'message/send': return sendMessage(ctx, request.id, params, car);
    case 'tasks/get': {
      const task = ownTask(ctx, params, client);
      return task ? wire.rpcResult(request.id, wire.wireTask(task, ctx.now())) : wire.rpcError(request.id, 'taskNotFound');
    }
    case 'tasks/cancel': return cancelTask(ctx, request.id, params, client);
    case 'message/stream':
    case 'tasks/resubscribe': return wire.rpcError(request.id, 'unsupported');
    default: return wire.rpcError(request.id, 'methodNotFound');
  }
}

async function rpcRoute(ctx, req, res) {
  const car = carFor(ctx, req);
  if (!car) return send(res, 401, wire.rpcError(null, 'invalidRequest', 'unauthorized'));
  if (!car.user) return send(res, 403, wire.rpcError(null, 'invalidRequest', 'car is not connected to an agent yet'));
  send(res, 200, await dispatch(ctx, await readBody(req, MAX_RPC_BYTES), car));
}

/** The user a Grok connector belongs to: the owner's RELAY_MCP_TOKENS, or a token issued on /connect. */
function connectorUser(ctx, req) {
  if (tokenIndex(req, ctx.config.mcpTokens) >= 0) return OWNER;
  return ctx.accounts.userByMcpToken(bearer(req))?.id || null;
}

/** A report landed for this user's task, so their connection works end to end. */
function reported(ctx, task) {
  if (task.user && task.user !== OWNER) ctx.accounts.markVerified(task.user);
}

async function mcpRoute(ctx, req, res) {
  const user = connectorUser(ctx, req);
  if (!user) return send(res, 401, wire.rpcError(null, 'invalidRequest', 'unauthorized'));
  let message;
  try {
    message = JSON.parse(await readBody(req, MAX_RPC_BYTES));
  } catch (error) {
    if (error instanceof BodyTooLarge) throw error;
    return send(res, 400, wire.rpcError(null, 'parse'));
  }
  const reply = await handleMcp({ ...ctx, user, owns: task => (task.user || OWNER) === user, onReport: task => reported(ctx, task) }, message);
  if (reply === null) {
    res.writeHead(202);
    return res.end();
  }
  send(res, 200, reply);
}

async function callbackRoute(ctx, req, res, taskId) {
  let body;
  try {
    body = JSON.parse(await readBody(req, MAX_CALLBACK_BYTES));
  } catch (error) {
    if (error instanceof BodyTooLarge) throw error;
    return send(res, 400, CALLBACK_REPLIES[400]);
  }
  const target = ctx.store.callbackTarget(taskId, bearer(req));
  const status = target.task ? ctx.store.apply(target.task, isObject(body) ? { ...body, data: await ctx.attach(target.task, body.data) } : body) : target.status;
  ctx.log(`callback ${taskId} state=${isObject(body) ? body.state : '-'} -> ${status}`);
  send(res, status, CALLBACK_REPLIES[status]);
}

/** A picture a report attached, for the car that owns the task. */
function fileRoute(ctx, req, res, taskId, name) {
  const car = carFor(ctx, req);
  if (!car) return send(res, 401, 'unauthorized');
  const task = ctx.store.get(taskId);
  const image = task && task.client === car.client && ctx.store.filesRoot ? storedImage(ctx.store.taskDir(taskId), name) : null;
  if (!image) return send(res, 404, 'not found');
  res.writeHead(200, { 'Content-Type': image.type, 'Content-Length': image.bytes.length, 'Cache-Control': 'private, max-age=86400' });
  res.end(image.bytes);
}

async function route(ctx, req, res) {
  const { pathname } = new URL(req.url, 'http://relay');
  if (req.method === 'GET' && pathname === '/health') return send(res, 200, 'ok');
  if (req.method === 'GET' && CARD_PATHS.has(pathname)) return send(res, 200, wire.agentCard(ctx.config));
  if (req.method === 'POST' && pathname === '/a2a') return rpcRoute(ctx, req, res);
  if (req.method === 'POST' && pathname === '/mcp') return mcpRoute(ctx, req, res);
  // No server-initiated stream and no sessions: GET and DELETE on the MCP endpoint are not offered.
  if (pathname === '/mcp') return send(res, 405, 'method not allowed');
  const callback = CALLBACK_PATH.exec(pathname);
  if (req.method === 'POST' && callback) return callbackRoute(ctx, req, res, callback[1]);
  const file = FILE_PATH.exec(pathname);
  if (req.method === 'GET' && file) return fileRoute(ctx, req, res, file[1], file[2]);
  const account = accountHandler(req.method, pathname);
  if (account) return account(ctx, req, res);
  const connect = connectHandler(req.method, pathname);
  if (connect) return connect(ctx, req, res, pathname);
  send(res, 404, 'not found');
}

/**
 * Normalizes a report's data (documented card shape, pictures as `{url, caption}`) and fetches its pictures into the
 * task's folder; without a folder for files the pictures are dropped and counted.
 */
function imageAttacher(store, imageDeps) {
  return async (task, raw) => {
    const data = normalizeReport(raw);
    if (!isObject(data) || !Array.isArray(data.images)) return data;
    if (!store.filesRoot) {
      const { images, ...rest } = data;
      return { ...rest, images_failed: images.length };
    }
    return attachImages(data, task.id, store.taskDir(task.id), imageDeps);
  };
}

/**
 * The relay HTTP server; `deliverImpl`, `now`, `imageDeps` (fetch and DNS lookup for pictures) and `lookup` (DNS for
 * webhook checks) are seams for tests. Without `accounts` the relay keeps devices and users in memory only.
 */
function createRelay({ config, store, now = Date.now, accounts = new AccountStore({ now }), deliverImpl = deliver, log = () => {}, imageDeps = {}, lookup }) {
  const ctx = {
    config, store, accounts, deliver: deliverImpl, log, notes: NOTES, attach: imageAttacher(store, { log, ...imageDeps }), now,
    limit: new HourlyLimit(config.maxTasksPerHour, now), connectLimit: new HourlyLimit(config.connectPerHour, now),
    loginLimit: new HourlyLimit(config.loginPerHour, now), lookup,
  };
  ctx.startRound = task => startRound(ctx, task);
  const server = http.createServer((req, res) => {
    route(ctx, req, res).catch(error => {
      if (res.headersSent) return res.destroy();
      if (error instanceof BodyTooLarge) return send(res, 413, 'body too large');
      log(`internal error: ${error?.message}`);
      send(res, 500, 'internal error');
    });
  });
  server.requestTimeout = 30_000;
  return server;
}

function main(argv = process.argv.slice(2)) {
  const envFile = path.resolve(argv[0] || path.join(__dirname, 'relay.env'));
  const settings = readSettings(envFile);
  const config = loadConfig(settings);
  const { dataFile, accountsFile } = dataPaths(envFile, settings);
  const store = new TaskStore({ file: dataFile, taskTimeoutMs: config.taskTimeoutMs }).load();
  const accounts = new AccountStore({ file: accountsFile }).load();
  const log = line => console.log(`${new Date().toISOString()} ${line}`);
  const server = createRelay({ config, store, accounts, log });
  setInterval(() => store.expire(), SWEEP_MS).unref();
  setInterval(() => accounts.prune(), 3600_000).unref();
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
  server.listen(config.port, config.host, () => log(`agent relay on ${config.host}:${config.port} for ${config.publicUrl}`));
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = { createRelay, HourlyLimit, tokenIndex };
