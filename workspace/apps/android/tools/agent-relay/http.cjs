'use strict';

// Small HTTP helpers shared by the relay routes and the /connect routes.
const { createHash, timingSafeEqual } = require('node:crypto');

const HOUR_MS = 3600_000;

class BodyTooLarge extends Error {}

/** The body's bytes; throws [BodyTooLarge] past `limit`. */
async function readRaw(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new BodyTooLarge();
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readBody(req, limit) {
  return (await readRaw(req, limit)).toString('utf8');
}

/** The body as JSON, or undefined when it is not JSON; an oversized body still throws [BodyTooLarge]. */
async function readJson(req, limit) {
  try {
    return JSON.parse(await readBody(req, limit));
  } catch (error) {
    if (error instanceof BodyTooLarge) throw error;
    return undefined;
  }
}

function send(res, status, body) {
  const json = typeof body !== 'string';
  res.writeHead(status, { 'Content-Type': json ? 'application/json' : 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(json ? JSON.stringify(body) : body);
}

function bearer(req) {
  const match = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
  return match ? match[1] : null;
}

const digest = value => createHash('sha256').update(value).digest();

/** The index of `presented` among `tokens`, compared as digests in constant time; -1 when none matches. */
function matchToken(tokens, presented) {
  if (!presented) return -1;
  const offered = digest(presented);
  return tokens.findIndex(token => timingSafeEqual(digest(token), offered));
}

/** The bearer token's index among `tokens`; -1 when none matches. */
const tokenIndex = (req, tokens) => matchToken(tokens, bearer(req));

/** The caller's address: the edge proxy in front of the relay sets X-Real-IP; the relay only listens to that proxy. */
function clientIp(req) {
  const forwarded = req.headers['x-real-ip'];
  return typeof forwarded === 'string' && forwarded ? forwarded : req.socket.remoteAddress || 'unknown';
}

class HourlyLimit {
  constructor(limit, now) {
    Object.assign(this, { limit, now, hits: new Map() });
  }

  take(key) {
    const since = this.now() - HOUR_MS;
    const recent = (this.hits.get(key) || []).filter(at => at > since);
    const allowed = recent.length < this.limit;
    if (allowed) recent.push(this.now());
    this.hits.set(key, recent);
    return allowed;
  }
}

const isObject = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);

module.exports = { BodyTooLarge, readRaw, readBody, readJson, send, bearer, matchToken, tokenIndex, clientIp, HourlyLimit, isObject };
