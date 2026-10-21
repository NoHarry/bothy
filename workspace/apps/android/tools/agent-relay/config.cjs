'use strict';

// Relay settings come from an env file on the server (never from tracked files) overridden by the process environment.
// Secrets are checked for presence only; no message here ever carries a value. Users connect their own Grok on the
// /connect page; the server owner's own Grok (RELAY_CLIENT_TOKENS + GROK_WEBHOOK_*) is optional and set up here.
const fs = require('node:fs');
const path = require('node:path');

const KEYS = [
  'RELAY_HOST', 'RELAY_PORT', 'RELAY_PUBLIC_URL', 'RELAY_CLIENT_TOKENS', 'RELAY_MCP_TOKENS', 'RELAY_AGENT_NAME', 'RELAY_AGENT_SKILLS',
  'RELAY_DATA_FILE', 'RELAY_ACCOUNTS_FILE', 'RELAY_TASK_TIMEOUT_MINUTES', 'RELAY_MAX_TASKS_PER_HOUR', 'RELAY_WEBHOOK_HOSTS',
  'RELAY_CONNECT_PER_HOUR', 'RELAY_LOGIN_PER_HOUR', 'GROK_WEBHOOK_URL', 'GROK_WEBHOOK_KEY',
];
// Grok Bot routine webhooks are served from Cursor's API hosts.
const DEFAULT_WEBHOOK_HOSTS = 'cursor.sh';
const MIN_CLIENT_TOKEN_CHARS = 24;

function parseEnvFile(text) {
  const values = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || !line.includes('=')) continue;
    const at = line.indexOf('=');
    values[line.slice(0, at).trim()] = line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
  }
  return values;
}

/** Reads `file` when it exists, then lets set environment variables of the same names win. */
function readSettings(file, env = process.env) {
  const fromFile = file && fs.existsSync(file) ? parseEnvFile(fs.readFileSync(file, 'utf8')) : {};
  const fromEnv = Object.fromEntries(KEYS.filter(key => env[key] !== undefined).map(key => [key, env[key]]));
  return { ...fromFile, ...fromEnv };
}

function httpsOrLoopback(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || (url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname));
  } catch {
    return false;
  }
}

const tokenList = value => (value || '').split(',').map(token => token.trim()).filter(Boolean);

/** The owner's own Grok is set up when any of its settings is; then all of them are required. */
const ownerConfigured = settings => Boolean(settings.RELAY_CLIENT_TOKENS || settings.GROK_WEBHOOK_URL || settings.GROK_WEBHOOK_KEY);

function problems(settings, tokens, mcpTokens) {
  const found = [];
  const required = ownerConfigured(settings) ? ['RELAY_PUBLIC_URL', 'GROK_WEBHOOK_URL'] : ['RELAY_PUBLIC_URL'];
  for (const key of required) {
    if (!settings[key]) found.push(`${key} 未设置`);
    else if (!httpsOrLoopback(settings[key])) found.push(`${key} 必须是 https 地址`);
  }
  if (ownerConfigured(settings) && !settings.GROK_WEBHOOK_KEY) found.push('GROK_WEBHOOK_KEY 未设置');
  if (ownerConfigured(settings) && tokens.length === 0) found.push('RELAY_CLIENT_TOKENS 未设置');
  if (mcpTokens.length > 0 && !ownerConfigured(settings)) found.push('RELAY_MCP_TOKENS 只用于自带的 Grok（还需要 RELAY_CLIENT_TOKENS 和 GROK_WEBHOOK_*）');
  if (tokens.some(token => token.length < MIN_CLIENT_TOKEN_CHARS)) found.push(`RELAY_CLIENT_TOKENS 每个至少 ${MIN_CLIENT_TOKEN_CHARS} 个字符`);
  if (mcpTokens.some(token => token.length < MIN_CLIENT_TOKEN_CHARS)) found.push(`RELAY_MCP_TOKENS 每个至少 ${MIN_CLIENT_TOKEN_CHARS} 个字符`);
  if (mcpTokens.some(token => tokens.includes(token))) found.push('RELAY_MCP_TOKENS 不能和 RELAY_CLIENT_TOKENS 相同');
  return found;
}

function positive(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

/** Validated relay config; throws one message naming every problem. */
function loadConfig(settings) {
  const tokens = tokenList(settings.RELAY_CLIENT_TOKENS);
  const mcpTokens = tokenList(settings.RELAY_MCP_TOKENS);
  const found = problems(settings, tokens, mcpTokens);
  if (found.length) throw new Error(`relay 配置有误：${found.join('；')}`);
  return {
    host: settings.RELAY_HOST || '127.0.0.1',
    port: positive(settings.RELAY_PORT, 18790),
    publicUrl: settings.RELAY_PUBLIC_URL.replace(/\/+$/, ''),
    clientTokens: tokens,
    mcpTokens,
    agentName: settings.RELAY_AGENT_NAME || 'Grok Bot',
    agentSkills: settings.RELAY_AGENT_SKILLS || '发邮件、查资料、设提醒、网购等需要个人账号的事务',
    dataFile: settings.RELAY_DATA_FILE || '',
    accountsFile: settings.RELAY_ACCOUNTS_FILE || '',
    taskTimeoutMs: positive(settings.RELAY_TASK_TIMEOUT_MINUTES, 15) * 60_000,
    maxTasksPerHour: positive(settings.RELAY_MAX_TASKS_PER_HOUR, 30),
    connectPerHour: positive(settings.RELAY_CONNECT_PER_HOUR, 20),
    // Per address and per account name.
    loginPerHour: positive(settings.RELAY_LOGIN_PER_HOUR, 10),
    webhookHosts: tokenList(settings.RELAY_WEBHOOK_HOSTS || DEFAULT_WEBHOOK_HOSTS).map(host => host.toLowerCase()),
    webhookUrl: settings.GROK_WEBHOOK_URL || '',
    webhookKey: settings.GROK_WEBHOOK_KEY || '',
  };
}

/** Where the relay keeps tasks and accounts for the env file at [envFile] and its [settings]. */
function dataPaths(envFile, settings) {
  const dataFile = settings.RELAY_DATA_FILE || path.join(path.dirname(envFile), 'data', 'tasks.json');
  return {
    dataFile,
    accountsFile: settings.RELAY_ACCOUNTS_FILE || path.join(path.dirname(dataFile), 'accounts.json'),
  };
}

module.exports = { parseEnvFile, readSettings, loadConfig, dataPaths, tokenList };
