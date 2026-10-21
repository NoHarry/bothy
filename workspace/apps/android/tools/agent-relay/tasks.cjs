'use strict';

// Task state for delegated work. Grok only accepts a webhook call; everything after that (who asked, which round,
// whether a callback may still land, when to stop waiting) lives here. A callback token is kept only as a hash and
// stops working once its round ends, so a late or replayed callback can never change a finished task.
const fs = require('node:fs');
const path = require('node:path');
const { createHash, randomBytes, randomUUID, timingSafeEqual } = require('node:crypto');

const TERMINAL = new Set(['completed', 'failed', 'canceled']);
const CALLBACK_STATES = new Set(['working', 'input-required', 'completed', 'failed']);
const MAX_TEXT_CHARS = 4000;
const DELIVERED_TEXT = '已交给 Grok Bot，正在处理。';
/** Delivered this long without the bot reading the task: it is queued or held for approval (owner 2026-10-12). */
const STALLED_MS = 90_000;
const STALLED_TEXT = '已交给 Grok Bot，它还没开始处理（可能在排队或等审批）。';
const STEP_STATES = new Set(['done', 'doing', 'todo']);
const MAX_STEPS = 12;
const MAX_STEP_CHARS = 60;

/** A working report's plan: up to 12 `{title, status: done|doing|todo}`; null when there is none. */
function normalizeSteps(raw) {
  if (!Array.isArray(raw)) return null;
  const steps = raw.filter(step => step && typeof step.title === 'string' && step.title.trim())
    .slice(0, MAX_STEPS)
    .map(step => ({ title: step.title.trim().slice(0, MAX_STEP_CHARS), status: STEP_STATES.has(step.status) ? step.status : 'todo' }));
  return steps.length ? steps : null;
}

/**
 * Where the open round stands, for the car: delivered (the webhook took it), started (the bot read the task),
 * working (the bot reported progress), stalled (delivered, unread for [STALLED_MS]); otherwise the task's state.
 */
function stage(task, now) {
  if (task.state !== 'working') return task.state;
  if (task.progressAt) return 'working';
  if (task.pickedUpAt) return 'started';
  return now - (task.roundStartedAt || task.updatedAt) >= STALLED_MS ? 'stalled' : 'delivered';
}
const CANCELED_TEXT = '已停止等待。Grok 那边无法取消，之后回来的结果会被丢弃。';

const hash = value => createHash('sha256').update(value).digest();

class TaskStore {
  constructor({ file = '', filesRoot = '', now = Date.now, taskTimeoutMs = 15 * 60_000, retentionMs = 24 * 3600_000, maxTasks = 500 } = {}) {
    Object.assign(this, { file, now, taskTimeoutMs, retentionMs, maxTasks });
    this.filesRoot = filesRoot || (file ? path.join(path.dirname(file), 'files') : '');
    this.tasks = new Map();
  }

  /** Where a task's attached pictures live; removed with the task. */
  taskDir(id) {
    return path.join(this.filesRoot, id);
  }

  load() {
    if (!this.file || !fs.existsSync(this.file)) return this;
    const saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    for (const task of saved.tasks || []) this.tasks.set(task.id, task);
    return this;
  }

  save() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
    const temporary = `${this.file}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify({ tasks: [...this.tasks.values()] }), { mode: 0o600 });
    fs.renameSync(temporary, this.file);
  }

  /**
   * A new task for `instruction`; `context` is the caller's own data, passed to Grok as is. `client` is the car (or
   * connection test) that may read it, `user` whose Grok does it.
   */
  create({ instruction, contextId, context, client, user }) {
    const at = this.now();
    const task = {
      id: randomUUID(), contextId: contextId || randomUUID(), client, user, state: 'submitted', createdAt: at, updatedAt: at,
      round: 0, tokenHash: null, deadlineAt: null, instruction, context: context ?? null,
      conversation: [{ role: 'user', text: instruction }], statusText: '', result: null,
    };
    this.tasks.set(task.id, task);
    this.prune();
    return task;
  }

  get(id) {
    this.expire();
    return this.tasks.get(id) || null;
  }

  /** The tasks `userId`'s Grok was asked to do, newest first. */
  tasksOf(userId) {
    this.expire();
    return [...this.tasks.values()].filter(task => (task.user || 'owner') === userId).sort((a, b) => b.createdAt - a.createdAt);
  }

  /** Starts the next round: returns the callback token, which only this round accepts. */
  openRound(task) {
    const token = randomBytes(24).toString('base64url');
    Object.assign(task, {
      round: task.round + 1, tokenHash: hash(token).toString('hex'), state: 'working', statusText: DELIVERED_TEXT,
      result: null, deadlineAt: this.now() + this.taskTimeoutMs, updatedAt: this.now(),
      roundStartedAt: this.now(), pickedUpAt: null, progressAt: null, steps: null,
    });
    this.save();
    return token;
  }

  /** The bot read the open round's task (car_task_get): it has started. */
  markPickedUp(task) {
    if (task.state !== 'working' || task.pickedUpAt) return;
    task.pickedUpAt = this.now();
    this.save();
  }

  /** Grok never got the round: the task fails with `reason` and nothing can call back into it. */
  failDelivery(task, reason) {
    this.finish(task, 'failed', `没能交给 Grok Bot：${reason}`);
    this.save();
  }

  /** The task a shell callback with `token` reports into, or the HTTP status refusing it (401 wrong token, 404, 409 closed). */
  callbackTarget(id, token) {
    const task = this.get(id);
    if (!task) return { status: 404 };
    if (!task.tokenHash || !token || !timingSafeEqual(hash(token), Buffer.from(task.tokenHash, 'hex'))) {
      return { status: TERMINAL.has(task.state) ? 409 : 401 };
    }
    return { status: 200, task };
  }

  /**
   * The task an MCP report reports into: the connector's own token already proved who is calling, so the task's open
   * round number stands in for the round token. Another round, or one already ended, is 409.
   */
  reportTarget(id, round) {
    const task = this.get(id);
    if (!task) return { status: 404 };
    if (task.state !== 'working' || task.round !== round) return { status: 409 };
    return { status: 200, task };
  }

  callback(id, token, body) {
    const { status, task } = this.callbackTarget(id, token);
    return task ? this.apply(task, body) : status;
  }

  report(id, round, body) {
    const { status, task } = this.reportTarget(id, round);
    return task ? this.apply(task, body) : status;
  }

  /** Applies one report to its open round: 200 applied, 400 bad body, 409 when the round ended meanwhile. */
  apply(task, body) {
    if (task.state !== 'working') return 409;
    const state = body && typeof body === 'object' ? body.state : undefined;
    const text = typeof body?.text === 'string' ? body.text.trim().slice(0, MAX_TEXT_CHARS) : '';
    if (!CALLBACK_STATES.has(state) || !text) return 400;
    const data = body.data && typeof body.data === 'object' && !Array.isArray(body.data) ? body.data : null;
    if (state === 'working') {
      Object.assign(task, {
        statusText: text, deadlineAt: this.now() + this.taskTimeoutMs, updatedAt: this.now(),
        pickedUpAt: task.pickedUpAt || this.now(), progressAt: this.now(), steps: normalizeSteps(data?.steps) || task.steps || null,
      });
    } else {
      this.finish(task, state, text, data);
    }
    this.save();
    return 200;
  }

  /** The caller's answer to an input-required question; the next round carries the whole exchange. */
  answer(id, text) {
    const task = this.get(id);
    if (!task) return { error: 'not_found' };
    if (task.state !== 'input-required') return { error: 'not_waiting' };
    task.conversation.push({ role: 'user', text });
    return { task };
  }

  cancel(id) {
    const task = this.get(id);
    if (!task) return { error: 'not_found' };
    if (TERMINAL.has(task.state)) return { error: 'not_cancelable' };
    this.finish(task, 'canceled', CANCELED_TEXT);
    this.save();
    return { task };
  }

  /** Ends the current round. input-required keeps the task open for an answer but still retires the token. */
  finish(task, state, text, data = null) {
    Object.assign(task, { state, statusText: text, tokenHash: null, deadlineAt: null, updatedAt: this.now() });
    task.result = state === 'canceled' ? null : { round: task.round, text, data };
    if (state === 'input-required') task.conversation.push({ role: 'agent', text });
  }

  /** Stops waiting for rounds whose callback is overdue; Grok is never asked again on its own. */
  expire() {
    let changed = false;
    for (const task of this.tasks.values()) {
      if (task.state !== 'working' || task.deadlineAt > this.now()) continue;
      const minutes = Math.round(this.taskTimeoutMs / 60_000);
      this.finish(task, 'failed', `Grok Bot 超过 ${minutes} 分钟没有回执，结果请到 Grok 里查看。`);
      changed = true;
    }
    if (changed) this.save();
    return changed;
  }

  /** Drops finished tasks after the retention window, and the oldest finished ones beyond the cap. */
  prune() {
    const cutoff = this.now() - this.retentionMs;
    for (const [id, task] of this.tasks) if (TERMINAL.has(task.state) && task.updatedAt < cutoff) this.drop(id);
    const finished = [...this.tasks.values()].filter(task => TERMINAL.has(task.state)).sort((a, b) => a.updatedAt - b.updatedAt);
    while (this.tasks.size > this.maxTasks && finished.length) this.drop(finished.shift().id);
  }

  drop(id) {
    this.tasks.delete(id);
    if (this.filesRoot) fs.rmSync(this.taskDir(id), { recursive: true, force: true });
  }
}

module.exports = { TaskStore, TERMINAL, DELIVERED_TEXT, CANCELED_TEXT, STALLED_TEXT, STALLED_MS, stage, normalizeSteps };
