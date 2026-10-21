'use strict';

// Pictures a report attaches (`data.images: [{url, caption}]`) are fetched here, where the bot's image hosts are
// reachable, and kept next to the task; the car then downloads them through its own authenticated `/files` route
// instead of fetching arbitrary URLs. Only public https hosts, a few image types and small files are accepted.
const fs = require('node:fs');
const path = require('node:path');
const dns = require('node:dns/promises');
const net = require('node:net');

const MAX_IMAGES = 3;
const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const FETCH_TIMEOUT_MS = 10_000;
const MAX_REDIRECTS = 3;
const MAX_CAPTION_CHARS = 40;
const TYPES = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };
const REDIRECTS = new Set([301, 302, 303, 307, 308]);
const FILE_NAME = /^[1-9]\.(jpg|png|webp)$/;
// Image hosts such as Wikimedia refuse requests without a descriptive agent.
const REQUEST_HEADERS = { 'User-Agent': 'agent-relay/1.0 (personal car delegation relay)', Accept: 'image/jpeg,image/png,image/webp' };

/** Loopback, private, link-local, CGNAT (Tailscale), unique-local and unspecified addresses. */
function privateAddress(address) {
  const mapped = address.toLowerCase().startsWith('::ffff:') ? address.slice(7) : address;
  if (net.isIPv4(mapped)) {
    const [a, b] = mapped.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const lower = mapped.toLowerCase();
  return lower === '::' || lower === '::1' || /^f[cd]/.test(lower) || /^fe[89ab]/.test(lower);
}

async function publicHost(hostname, lookup) {
  const host = hostname.replace(/^\[|\]$/g, '');
  if (net.isIP(host)) return !privateAddress(host);
  const addresses = await lookup(host, { all: true });
  return addresses.length > 0 && addresses.every(entry => !privateAddress(entry.address));
}

async function readLimited(body, limit) {
  const reader = body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > limit) throw new Error('image too large');
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
  return Buffer.concat(chunks);
}

/** Follows up to three redirects, checking every hop; resolves `{ bytes, ext, type }` or throws. */
async function download(url, { fetchImpl = fetch, lookup = dns.lookup } = {}) {
  let current = url;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop += 1) {
    const parsed = new URL(current);
    if (parsed.protocol !== 'https:' || !(await publicHost(parsed.hostname, lookup))) throw new Error('blocked host');
    const response = await fetchImpl(current, { headers: REQUEST_HEADERS, redirect: 'manual', signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    if (REDIRECTS.has(response.status)) {
      const location = response.headers.get('location');
      await response.body?.cancel().catch(() => {});
      if (!location) throw new Error('redirect without location');
      current = new URL(location, current).toString();
      continue;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const type = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    if (!TYPES[type]) throw new Error('not a supported image');
    return { bytes: await readLimited(response.body, MAX_IMAGE_BYTES), ext: TYPES[type], type };
  }
  throw new Error('too many redirects');
}

/**
 * `data` with its `images` replaced by relay paths (`/files/<task>/<n>.<ext>`); pictures that cannot be fetched are
 * dropped and counted in `images_failed`. Other fields pass through untouched.
 */
async function attachImages(data, taskId, taskDir, deps) {
  if (!data || !Array.isArray(data.images)) return data;
  const wanted = data.images.slice(0, MAX_IMAGES).filter(image => image && typeof image.url === 'string');
  const fetched = await Promise.allSettled(wanted.map(image => download(image.url, deps)));
  const kept = [];
  fetched.forEach((outcome, index) => {
    if (outcome.status !== 'fulfilled') {
      deps?.log?.(`image not attached (${hostOf(wanted[index].url)}): ${outcome.reason?.message || 'failed'}`);
      return;
    }
    const name = `${index + 1}.${outcome.value.ext}`;
    fs.mkdirSync(taskDir, { recursive: true, mode: 0o700 });
    fs.writeFileSync(path.join(taskDir, name), outcome.value.bytes, { mode: 0o600 });
    const caption = typeof wanted[index].caption === 'string' ? wanted[index].caption.trim().slice(0, MAX_CAPTION_CHARS) : '';
    kept.push({ path: `/files/${taskId}/${name}`, caption, content_type: outcome.value.type });
  });
  const { images, ...rest } = data;
  const failed = wanted.length - kept.length + (images.length - wanted.length);
  return { ...rest, ...(kept.length ? { images: kept } : {}), ...(failed > 0 ? { images_failed: failed } : {}) };
}

function hostOf(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return 'invalid url';
  }
}

/** The stored picture for a `/files/<task>/<name>` request, or null for any other name. */
function storedImage(taskDir, name) {
  if (!FILE_NAME.test(name)) return null;
  const file = path.join(taskDir, name);
  if (!fs.existsSync(file)) return null;
  const ext = name.split('.').pop();
  const type = Object.keys(TYPES).find(key => TYPES[key] === ext);
  return { bytes: fs.readFileSync(file), type };
}

module.exports = { attachImages, storedImage, download, privateAddress, publicHost, MAX_IMAGES };
