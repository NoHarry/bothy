'use strict';

// A2A 0.3 JSON-RPC shapes the car's packaged client already reads: a task with a status message, and the round's
// result as an artifact on the `speech` channel (phase final) so the car can say it. Structured data from Grok
// rides along as a data part of that artifact.
const { randomUUID } = require('node:crypto');
const { stage, STALLED_TEXT } = require('./tasks.cjs');

const PROTOCOL_VERSION = '0.3.0';
const ERRORS = {
  parse: [-32700, 'Parse error'],
  invalidRequest: [-32600, 'Invalid request'],
  methodNotFound: [-32601, 'Method not found'],
  invalidParams: [-32602, 'Invalid params'],
  rateLimited: [-32000, 'Too many tasks; try again later'],
  taskNotFound: [-32001, 'Task not found'],
  taskNotCancelable: [-32002, 'Task cannot be canceled'],
  unsupported: [-32004, 'Streaming is not supported; poll tasks/get'],
};

const iso = millis => new Date(millis).toISOString();

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id, name, detail) {
  const [code, message] = ERRORS[name];
  return { jsonrpc: '2.0', id: id ?? null, error: { code, message: detail ? `${message}: ${detail}` : message } };
}

function agentMessage(task, text, data = null) {
  const parts = [{ kind: 'text', text }];
  if (data) parts.push({ kind: 'data', data });
  return { kind: 'message', messageId: randomUUID(), role: 'agent', parts, taskId: task.id, contextId: task.contextId };
}

function resultArtifact(task) {
  const parts = [{ kind: 'text', text: task.result.text }];
  if (task.result.data) parts.push({ kind: 'data', data: task.result.data });
  return {
    artifactId: `${task.id}-round-${task.result.round}`,
    name: task.state === 'input-required' ? 'question' : 'result',
    parts,
    metadata: { channel: 'speech', phase: 'final' },
  };
}

/**
 * The task as the car reads it. `metadata.relay` says where the open round stands (see tasks.stage), when the bot
 * read it and last reported progress, and the bot's step list; a stalled round says so in its status message. The
 * same `relay` object rides as a data part of the status message too: the car's packaged client reads message parts
 * but not task metadata.
 */
function wireTask(task, now = Date.now()) {
  const where = stage(task, now);
  const status = { state: task.state, timestamp: iso(task.updatedAt) };
  const relay = { round: task.round, createdAt: iso(task.createdAt), stage: where };
  if (task.pickedUpAt) relay.pickedUpAt = iso(task.pickedUpAt);
  if (task.progressAt) relay.progressAt = iso(task.progressAt);
  if (task.steps) relay.steps = task.steps;
  const text = where === 'stalled' ? STALLED_TEXT : task.statusText;
  if (text) status.message = agentMessage(task, text, { relay });
  return {
    kind: 'task',
    id: task.id,
    contextId: task.contextId,
    status,
    artifacts: task.result ? [resultArtifact(task)] : [],
    metadata: { relay },
  };
}

function agentCard(config) {
  return {
    protocolVersion: PROTOCOL_VERSION,
    name: config.agentName,
    description: `${config.agentName}：用户自己的云端 agent，经中转接收任务。结果异步返回：发送后先是 working，用 tasks/get 查询；` +
      'input-required 时用同一 taskId 发送回答；取消只停止等待，远端无法取消。',
    url: `${config.publicUrl}/a2a`,
    preferredTransport: 'JSONRPC',
    version: '1.0.0',
    capabilities: { streaming: false, pushNotifications: false },
    defaultInputModes: ['text/plain'],
    defaultOutputModes: ['text/plain', 'application/json'],
    skills: [{ id: 'delegate', name: '委托个人事务', description: config.agentSkills, tags: ['personal', 'delegation'] }],
    securitySchemes: { bearer: { type: 'http', scheme: 'bearer' } },
    security: [{ bearer: [] }],
  };
}

/** Text parts of an A2A message, joined; null when the message is missing or has no text. */
function messageText(message) {
  if (!message || typeof message !== 'object' || !Array.isArray(message.parts)) return null;
  const text = message.parts.filter(part => part && part.kind === 'text' && typeof part.text === 'string')
    .map(part => part.text).join('\n').trim();
  return text || null;
}

module.exports = { rpcResult, rpcError, wireTask, agentCard, messageText, PROTOCOL_VERSION };
