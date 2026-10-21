'use strict';

// Users, the cars they connected, and pairing codes. A car registers itself and gets a car token plus a short pairing
// code it shows on screen; whoever holds the code connects that car to their own Grok Bot routine on the /connect page
// and gets an MCP token for the Grok connector. Tokens and codes are kept only as hashes. A routine's webhook key is
// kept as is because the relay has to send it; the file is 0600, like relay.env.
//
// Accounts (owner request 2026-10-12): a name and password that hold one connection. A car signed in to an account
// joins that account's connection, or hands its own connection to an account that has none, so a second car never
// needs the MCP token. A connection held by an account outlives its last car. Passwords are scrypt hashes; sign-in
// sessions are kept as hashes like every other token.
const fs = require('node:fs');
const path = require('node:path');
const { createHash, randomBytes, randomInt, randomUUID, scrypt, timingSafeEqual } = require('node:crypto');

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
/** The server owner's own Grok from relay.env; cars can join it, but it has no user record here. */
const OWNER = 'owner';
const CODE_CHARS = 8;
const MAX_LABEL_CHARS = 40;
const NAME_PATTERN = /^[\p{L}\p{N}_.-]{2,32}$/u;
const MIN_PASSWORD_CHARS = 8;
const MAX_PASSWORD_CHARS = 128;
const SCRYPT = { N: 16384, r: 8, p: 1 };
const SESSION_TTL_MS = 30 * 24 * 3600_000;

/** scrypt of `password` with `salt`, as a promise of 32 bytes. */
const derive = (password, salt, params = SCRYPT) => new Promise((resolve, reject) => {
  scrypt(password, salt, 32, { ...params, maxmem: 64 * 1024 * 1024 }, (error, key) => (error ? reject(error) : resolve(key)));
});

async function hashPassword(password) {
  const salt = randomBytes(16);
  const key = await derive(password, salt);
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString('base64url')}$${key.toString('base64url')}`;
}

async function passwordMatches(stored, password) {
  const [kind, N, r, p, salt, expected] = String(stored).split('$');
  if (kind !== 'scrypt' || !expected) return false;
  const key = await derive(password, Buffer.from(salt, 'base64url'), { N: Number(N), r: Number(r), p: Number(p) });
  const wanted = Buffer.from(expected, 'base64url');
  return key.length === wanted.length && timingSafeEqual(key, wanted);
}

/** Why `name` or `password` cannot make an account, or null. */
function credentialsProblem(name, password) {
  if (!NAME_PATTERN.test(name)) return 'name';
  if (password.length < MIN_PASSWORD_CHARS || password.length > MAX_PASSWORD_CHARS) return 'password';
  return null;
}

const nameKey = name => name.normalize('NFKC').toLowerCase();
/** Compared against when the name is unknown, so a wrong name takes as long as a wrong password. */
let decoyHash = null;

const hashHex = value => createHash('sha256').update(value).digest('hex');
const secret = prefix => `${prefix}_${randomBytes(32).toString('base64url')}`;

/** A typed code as stored: upper case, letters and digits only ("abcd-efgh" and "ABCDEFGH" are the same code). */
const normalizeCode = code => (typeof code === 'string' ? code.toUpperCase().replace(/[^A-Z0-9]/g, '') : '');
const formatCode = code => `${code.slice(0, 4)}-${code.slice(4)}`;

class AccountStore {
  constructor({ file = '', now = Date.now, codeTtlMs = 15 * 60_000, unpairedTtlMs = 24 * 3600_000, maxDevices = 2000, maxLogins = 2000 } = {}) {
    Object.assign(this, { file, now, codeTtlMs, unpairedTtlMs, maxDevices, maxLogins });
    this.devices = new Map();
    this.users = new Map();
    /** Accounts by id: `{id, name, nameKey, passwordHash, userId, createdAt, lastLoginAt}`; userId is the connection held. */
    this.logins = new Map();
    /** Sign-in sessions by token hash: `{hash, loginId, createdAt, expiresAt}`. */
    this.sessions = new Map();
  }

  load() {
    if (!this.file || !fs.existsSync(this.file)) return this;
    const saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    for (const device of saved.devices || []) this.devices.set(device.id, device);
    for (const user of saved.users || []) this.users.set(user.id, user);
    for (const login of saved.logins || []) this.logins.set(login.id, login);
    for (const session of saved.sessions || []) this.sessions.set(session.hash, session);
    return this;
  }

  save() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
    const temporary = `${this.file}.tmp`;
    const saved = {
      devices: [...this.devices.values()], users: [...this.users.values()],
      logins: [...this.logins.values()], sessions: [...this.sessions.values()],
    };
    fs.writeFileSync(temporary, JSON.stringify(saved), { mode: 0o600 });
    fs.renameSync(temporary, this.file);
  }

  /** A new car with its token and first pairing code; null when the relay already holds too many cars. */
  registerDevice(label) {
    this.prune();
    if (this.devices.size >= this.maxDevices) return null;
    const token = secret('car');
    const device = {
      id: `car-${randomUUID()}`, tokenHash: hashHex(token), label: cleanLabel(label), userId: null,
      createdAt: this.now(), seenAt: this.now(), connectedAt: null, codeHash: null, codeExpiresAt: null,
    };
    this.devices.set(device.id, device);
    const code = this.newCode(device);
    return { device, token, code };
  }

  /** Replaces the car's pairing code; the old one stops working. */
  newCode(device) {
    const code = Array.from({ length: CODE_CHARS }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
    Object.assign(device, { codeHash: hashHex(code), codeExpiresAt: this.now() + this.codeTtlMs });
    this.save();
    return formatCode(code);
  }

  deviceByToken(token) {
    if (!token) return null;
    const wanted = hashHex(token);
    const device = [...this.devices.values()].find(entry => entry.tokenHash === wanted) || null;
    if (device) device.seenAt = this.now();
    return device;
  }

  userByMcpToken(token) {
    if (!token) return null;
    const wanted = hashHex(token);
    return [...this.users.values()].find(user => user.mcpTokenHash === wanted) || null;
  }

  user(id) {
    return this.users.get(id) || null;
  }

  /**
   * Connects the car showing `code` to a Grok routine. Without `mcpToken` a new user is made and its MCP token is
   * returned once; with one, the car joins that user (and a given webhook replaces the user's). `toOwner` (the caller
   * matched the owner's own MCP token) joins the owner's Grok instead. A car that belonged to someone else moves; a
   * user left without cars is removed. Errors: code, mcp_token, webhook.
   */
  claim({ code, webhookUrl, webhookKey, mcpToken, toOwner = false }) {
    const wanted = hashHex(normalizeCode(code));
    const device = [...this.devices.values()].find(entry => entry.codeHash === wanted && entry.codeExpiresAt > this.now());
    if (!device) return { error: 'code' };
    let user = null;
    let issued = null;
    if (toOwner) {
      user = { id: OWNER };
    } else if (mcpToken) {
      user = this.userByMcpToken(mcpToken);
      if (!user) return { error: 'mcp_token' };
      if (webhookUrl && webhookKey) Object.assign(user, { webhookUrl, webhookKey });
    } else {
      if (!webhookUrl || !webhookKey) return { error: 'webhook' };
      issued = secret('mcp');
      user = { id: randomUUID(), mcpTokenHash: hashHex(issued), webhookUrl, webhookKey, createdAt: this.now(), verifiedAt: null };
      this.users.set(user.id, user);
    }
    const previous = device.userId;
    Object.assign(device, { userId: user.id, connectedAt: this.now(), codeHash: null, codeExpiresAt: null });
    // A car signed in to an account without a connection hands it the one it just made.
    const login = this.logins.get(device.loginId);
    if (login && !login.userId) login.userId = user.id;
    if (previous && previous !== user.id) this.dropIfOrphaned(previous);
    this.save();
    return { device, user, mcpToken: issued };
  }

  /** A new account; errors: name (shape), name_taken, password (length), full. */
  async register(name, password) {
    const clean = typeof name === 'string' ? name.trim() : '';
    const secretText = typeof password === 'string' ? password : '';
    const problem = credentialsProblem(clean, secretText);
    if (problem) return { error: problem };
    if (this.loginByName(clean)) return { error: 'name_taken' };
    if (this.logins.size >= this.maxLogins) return { error: 'full' };
    const passwordHash = await hashPassword(secretText);
    // Checked again: another registration may have taken the name while the hash was computed.
    if (this.loginByName(clean)) return { error: 'name_taken' };
    const login = { id: `acct-${randomUUID()}`, name: clean, nameKey: nameKey(clean), passwordHash, userId: null, createdAt: this.now(), lastLoginAt: this.now() };
    this.logins.set(login.id, login);
    this.save();
    return { login };
  }

  /** The account for `name` and `password`, or null; an unknown name costs the same scrypt as a wrong password. */
  async authenticate(name, password) {
    const login = typeof name === 'string' ? this.loginByName(name.trim()) : null;
    const secretText = typeof password === 'string' ? password : '';
    if (!login) {
      decoyHash = decoyHash || await hashPassword(randomBytes(12).toString('hex'));
      await passwordMatches(decoyHash, secretText);
      return null;
    }
    if (!(await passwordMatches(login.passwordHash, secretText))) return null;
    login.lastLoginAt = this.now();
    this.save();
    return login;
  }

  async changePassword(login, password, next) {
    if (!(await passwordMatches(login.passwordHash, typeof password === 'string' ? password : ''))) return { error: 'password_wrong' };
    const problem = credentialsProblem(login.name, typeof next === 'string' ? next : '');
    if (problem) return { error: problem };
    login.passwordHash = await hashPassword(next);
    for (const [hash, session] of this.sessions) if (session.loginId === login.id) this.sessions.delete(hash);
    this.save();
    return { login };
  }

  loginByName(name) {
    const wanted = nameKey(name);
    return [...this.logins.values()].find(login => login.nameKey === wanted) || null;
  }

  login(id) {
    return this.logins.get(id) || null;
  }

  /** The account holding connection `userId`, or null. */
  loginOf(userId) {
    return userId ? [...this.logins.values()].find(login => login.userId === userId) || null : null;
  }

  /** A sign-in session token for the account page; only its hash is kept. */
  newSession(login) {
    const token = secret('sess');
    this.sessions.set(hashHex(token), { hash: hashHex(token), loginId: login.id, createdAt: this.now(), expiresAt: this.now() + SESSION_TTL_MS });
    this.pruneSessions();
    this.save();
    return token;
  }

  /** The account a session token belongs to, or null once it expired or was ended. */
  sessionLogin(token) {
    if (!token) return null;
    const session = this.sessions.get(hashHex(token));
    if (!session || session.expiresAt <= this.now()) return null;
    return this.logins.get(session.loginId) || null;
  }

  endSession(token) {
    if (token && this.sessions.delete(hashHex(token))) this.save();
  }

  pruneSessions() {
    for (const [hash, session] of this.sessions) if (session.expiresAt <= this.now() || !this.logins.has(session.loginId)) this.sessions.delete(hash);
  }

  /**
   * Signs `device` in to `login`. An account with a connection takes the car into it (a connection the car leaves
   * without other cars or an account goes); an account without one takes over the car's connection, unless another
   * account already holds that. Errors: connection_taken.
   */
  signInDevice(device, login) {
    if (login.userId) {
      if (device.userId !== login.userId) {
        const previous = device.userId;
        Object.assign(device, { userId: login.userId, connectedAt: this.now(), codeHash: null, codeExpiresAt: null, loginId: login.id });
        if (previous) this.dropIfOrphaned(previous);
      }
    } else if (device.userId && this.connected(device)) {
      const holder = this.loginOf(device.userId);
      if (holder && holder.id !== login.id) return { error: 'connection_taken' };
      login.userId = device.userId;
    }
    device.loginId = login.id;
    this.save();
    return { device, login };
  }

  /** The car leaves its account; it stays connected. */
  signOutDevice(device) {
    device.loginId = null;
    this.save();
  }

  /** Puts the car showing `code` into the account's connection; errors: code, no_connection. */
  linkByCode(login, code) {
    if (!login.userId) return { error: 'no_connection' };
    const wanted = hashHex(normalizeCode(code));
    const device = [...this.devices.values()].find(entry => entry.codeHash === wanted && entry.codeExpiresAt > this.now());
    if (!device) return { error: 'code' };
    return this.signInDevice(device, login);
  }

  /** The account's car `deviceId`, or null when it is not in the account's connection. */
  deviceOf(login, deviceId) {
    const device = this.devices.get(deviceId);
    return device && login.userId && device.userId === login.userId ? device : null;
  }

  renameDevice(device, label) {
    device.label = cleanLabel(label);
    this.save();
    return device;
  }

  /** New routine webhook for the account's connection (checked by the caller); errors: no_connection, server_config. */
  setWebhook(login, webhookUrl, webhookKey) {
    if (login.userId === OWNER) return { error: 'server_config' };
    const user = this.users.get(login.userId);
    if (!user) return { error: 'no_connection' };
    Object.assign(user, { webhookUrl, webhookKey, verifiedAt: null });
    this.save();
    return { user };
  }

  /** A new MCP token for the account's connection, returned once; the old one stops working. */
  rotateMcpToken(login) {
    if (login.userId === OWNER) return { error: 'server_config' };
    const user = this.users.get(login.userId);
    if (!user) return { error: 'no_connection' };
    const token = secret('mcp');
    user.mcpTokenHash = hashHex(token);
    this.save();
    return { token };
  }

  /** The car forgets its connection: its token stops working, and a user left without cars is removed. */
  removeDevice(device) {
    this.devices.delete(device.id);
    if (device.userId) this.dropIfOrphaned(device.userId);
    this.save();
  }

  /** Disconnects the user's cars, or only `deviceId`; returns how many went. A user left without cars is removed. */
  disconnect(userId, deviceId = null) {
    const cars = this.devicesOf(userId).filter(device => !deviceId || device.id === deviceId);
    for (const device of cars) this.devices.delete(device.id);
    if (cars.length > 0) {
      this.dropIfOrphaned(userId);
      this.save();
    }
    return cars.length;
  }

  /** A report reached a task of this user: the connection works end to end. */
  markVerified(userId) {
    const user = this.users.get(userId);
    if (!user || user.verifiedAt) return;
    user.verifiedAt = this.now();
    this.save();
  }

  devicesOf(userId) {
    return [...this.devices.values()].filter(device => device.userId === userId);
  }

  /** Whether this car may send tasks: it joined the owner's Grok or a user that still exists. */
  connected(device) {
    return device.userId === OWNER || this.users.has(device.userId);
  }

  /** Removes a connection no car and no account holds any more. */
  dropIfOrphaned(userId) {
    if (this.devicesOf(userId).length === 0 && !this.loginOf(userId)) this.users.delete(userId);
  }

  /** Drops cars that never got connected within the window. */
  prune() {
    const cutoff = this.now() - this.unpairedTtlMs;
    let changed = false;
    for (const [id, device] of this.devices) {
      if (!device.userId && device.createdAt < cutoff) {
        this.devices.delete(id);
        changed = true;
      }
    }
    if (changed) this.save();
  }
}

function cleanLabel(label) {
  const text = typeof label === 'string' ? label.replace(/[\u0000-\u001f\u007f]/g, '').trim() : '';
  return text.slice(0, MAX_LABEL_CHARS) || '设备';
}

module.exports = { AccountStore, nameKey, normalizeCode, formatCode, CODE_ALPHABET, OWNER, hashPassword, passwordMatches };
