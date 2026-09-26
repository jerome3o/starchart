'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Database = require('better-sqlite3');

const dataDir = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(dataDir, { recursive: true });

const db = new Database(path.join(dataDir, 'starchart.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL UNIQUE,
  email TEXT NOT NULL,
  label TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER
);

CREATE TABLE IF NOT EXISTS fixes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  client_id INTEGER NOT NULL,
  time INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  accuracy REAL NOT NULL,
  UNIQUE (device_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_fixes_device_time ON fixes (device_id, time);
`);

db.exec(`
CREATE TABLE IF NOT EXISTS oauth_clients (
  client_id TEXT PRIMARY KEY,
  client_name TEXT NOT NULL,
  redirect_uris TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS oauth_codes (
  code_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scope TEXT NOT NULL,
  email TEXT NOT NULL,
  resource TEXT,
  expires_at INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS oauth_grants (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL,
  email TEXT NOT NULL,
  scope TEXT NOT NULL,
  access_hash TEXT NOT NULL UNIQUE,
  access_expires_at INTEGER NOT NULL,
  refresh_hash TEXT UNIQUE,
  refresh_expires_at INTEGER,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER,
  revoked INTEGER NOT NULL DEFAULT 0
);
`);

db.exec(`
CREATE TABLE IF NOT EXISTS user_settings (
  email TEXT PRIMARY KEY,
  timezone TEXT NOT NULL DEFAULT 'Europe/London'
);

CREATE TABLE IF NOT EXISTS goals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  emoji TEXT,
  target REAL NOT NULL,
  period_days INTEGER NOT NULL DEFAULT 14,
  hours_offset REAL NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS completions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER NOT NULL REFERENCES goals(id),
  time INTEGER NOT NULL,
  source TEXT NOT NULL,
  client_id TEXT,
  note TEXT,
  deleted_at INTEGER,
  UNIQUE (goal_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_completions_goal_time ON completions (goal_id, time);

CREATE TABLE IF NOT EXISTS displays (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT,
  label TEXT NOT NULL,
  mac TEXT UNIQUE,
  key_hash TEXT UNIQUE,
  friendly_id TEXT NOT NULL,
  status TEXT NOT NULL,
  refresh_rate INTEGER NOT NULL DEFAULT 900,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  battery_voltage REAL,
  fw_version TEXT,
  rssi INTEGER,
  pending_key TEXT,
  setup_requested_at INTEGER
);
`);

// Schema additions after the first release (SQLite has no ADD COLUMN IF NOT EXISTS).
const deviceColumns = db.prepare('PRAGMA table_info(devices)').all().map((c) => c.name);
if (!deviceColumns.includes('last_error')) {
  db.exec('ALTER TABLE devices ADD COLUMN last_error TEXT');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex');
}

// Returns the plaintext token exactly once; only its hash is stored.
function createDevice(email, label) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare(
    'INSERT INTO devices (token_hash, email, label, created_at) VALUES (?, ?, ?, ?)'
  ).run(hashToken(token), email.toLowerCase(), String(label).slice(0, 64), Date.now());
  return token;
}

function deviceForToken(token) {
  const device = db
    .prepare('SELECT id, email, label FROM devices WHERE token_hash = ?')
    .get(hashToken(token));
  if (device) {
    db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(Date.now(), device.id);
  }
  return device;
}

function setDeviceError(deviceId, message) {
  db.prepare('UPDATE devices SET last_error = ? WHERE id = ?').run(message, deviceId);
}

function listDevices(email) {
  return db
    .prepare(
      `SELECT d.id, d.label, d.created_at, d.last_seen_at, d.last_error,
              COUNT(f.id) AS fix_count, MAX(f.time) AS last_fix_time
       FROM devices d LEFT JOIN fixes f ON f.device_id = d.id
       WHERE d.email = ?
       GROUP BY d.id ORDER BY d.created_at`
    )
    .all(email.toLowerCase());
}

function revokeDevice(email, deviceId) {
  // Fixes are intentionally kept: deleting a device only revokes its token.
  db.prepare('UPDATE devices SET token_hash = ? WHERE id = ? AND email = ?').run(
    `revoked:${crypto.randomBytes(16).toString('hex')}`,
    deviceId,
    email.toLowerCase()
  );
}

const insertFix = db.prepare(
  `INSERT OR IGNORE INTO fixes (device_id, client_id, time, lat, lon, accuracy)
   VALUES (@deviceId, @clientId, @time, @lat, @lon, @accuracy)`
);

const insertFixes = db.transaction((deviceId, fixes) => {
  let accepted = 0;
  for (const fix of fixes) {
    const result = insertFix.run({ deviceId, ...fix });
    accepted += result.changes;
  }
  return accepted;
});

function fixCount(deviceId) {
  return db.prepare('SELECT COUNT(*) AS c FROM fixes WHERE device_id = ?').get(deviceId).c;
}

// --- Location queries for the MCP server (always scoped to one user) --------

function latestFix(email) {
  return db
    .prepare(
      `SELECT f.time, f.lat, f.lon, f.accuracy, f.device_id AS deviceId, d.label AS deviceLabel
       FROM fixes f JOIN devices d ON d.id = f.device_id
       WHERE d.email = ? ORDER BY f.time DESC LIMIT 1`
    )
    .get(email.toLowerCase());
}

function fixesBetween(email, fromMs, toMs, deviceId = null) {
  return db
    .prepare(
      `SELECT f.time, f.lat, f.lon, f.accuracy, f.device_id AS deviceId, d.label AS deviceLabel
       FROM fixes f JOIN devices d ON d.id = f.device_id
       WHERE d.email = ? AND f.time >= ? AND f.time < ?
         AND (? IS NULL OR f.device_id = ?)
       ORDER BY f.time ASC`
    )
    .all(email.toLowerCase(), fromMs, toMs, deviceId, deviceId);
}

// Calendar days with fixes, bucketed using a fixed UTC offset in minutes
// (east positive), with counts.
function daysWithFixes(email, offsetMinutes) {
  return db
    .prepare(
      `SELECT date((f.time / 1000) + ?, 'unixepoch') AS day, COUNT(*) AS count
       FROM fixes f JOIN devices d ON d.id = f.device_id
       WHERE d.email = ?
       GROUP BY day ORDER BY day ASC`
    )
    .all(offsetMinutes * 60, email.toLowerCase());
}

// --- OAuth clients, codes and grants -----------------------------------------

function createOAuthClient(clientName, redirectUris) {
  const clientId = crypto.randomBytes(16).toString('hex');
  db.prepare(
    'INSERT INTO oauth_clients (client_id, client_name, redirect_uris, created_at) VALUES (?, ?, ?, ?)'
  ).run(clientId, String(clientName).slice(0, 100), JSON.stringify(redirectUris), Date.now());
  return clientId;
}

function getOAuthClient(clientId) {
  const row = db.prepare('SELECT * FROM oauth_clients WHERE client_id = ?').get(clientId);
  return row ? { ...row, redirect_uris: JSON.parse(row.redirect_uris) } : null;
}

function createAuthCode({ clientId, redirectUri, codeChallenge, scope, email, resource }) {
  const code = crypto.randomBytes(32).toString('hex');
  db.prepare(
    `INSERT INTO oauth_codes (code_hash, client_id, redirect_uri, code_challenge, scope, email, resource, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).run(hashToken(code), clientId, redirectUri, codeChallenge, scope, email.toLowerCase(), resource || null,
    Date.now() + 10 * 60 * 1000);
  return code;
}

// Single use: returns the code row once, or null if unknown/expired/used.
const consumeAuthCode = db.transaction((code) => {
  const row = db
    .prepare('SELECT * FROM oauth_codes WHERE code_hash = ? AND used = 0 AND expires_at > ?')
    .get(hashToken(code), Date.now());
  if (!row) return null;
  db.prepare('UPDATE oauth_codes SET used = 1 WHERE code_hash = ?').run(row.code_hash);
  return row;
});

const ACCESS_TTL_MS = 60 * 60 * 1000;
const REFRESH_TTL_MS = 90 * 24 * 60 * 60 * 1000;

function issueTokens({ clientId, email, scope }) {
  const accessToken = crypto.randomBytes(32).toString('hex');
  const refreshToken = crypto.randomBytes(32).toString('hex');
  const now = Date.now();
  db.prepare(
    `INSERT INTO oauth_grants (client_id, email, scope, access_hash, access_expires_at,
       refresh_hash, refresh_expires_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).run(clientId, email.toLowerCase(), scope, hashToken(accessToken), now + ACCESS_TTL_MS,
    hashToken(refreshToken), now + REFRESH_TTL_MS, now);
  return { accessToken, refreshToken, expiresIn: ACCESS_TTL_MS / 1000 };
}

function grantForAccessToken(token) {
  const grant = db
    .prepare(
      `SELECT g.id, g.client_id, g.email, g.scope, c.client_name
       FROM oauth_grants g JOIN oauth_clients c ON c.client_id = g.client_id
       WHERE g.access_hash = ? AND g.revoked = 0 AND g.access_expires_at > ?`
    )
    .get(hashToken(token), Date.now());
  if (grant) {
    db.prepare('UPDATE oauth_grants SET last_used_at = ? WHERE id = ?').run(Date.now(), grant.id);
  }
  return grant;
}

// Rotates: the old grant is revoked and a fresh access/refresh pair issued.
const rotateRefreshToken = db.transaction((refreshToken, clientId) => {
  const grant = db
    .prepare(
      `SELECT * FROM oauth_grants WHERE refresh_hash = ? AND client_id = ? AND revoked = 0
       AND refresh_expires_at > ?`
    )
    .get(hashToken(refreshToken), clientId, Date.now());
  if (!grant) return null;
  db.prepare('UPDATE oauth_grants SET revoked = 1 WHERE id = ?').run(grant.id);
  return { ...issueTokens({ clientId, email: grant.email, scope: grant.scope }), scope: grant.scope };
});

function listGrants(email) {
  return db
    .prepare(
      `SELECT g.client_id, c.client_name, MIN(g.created_at) AS first_connected,
              MAX(g.last_used_at) AS last_used
       FROM oauth_grants g JOIN oauth_clients c ON c.client_id = g.client_id
       WHERE g.email = ? AND g.revoked = 0 AND g.refresh_expires_at > ?
       GROUP BY g.client_id ORDER BY first_connected`
    )
    .all(email.toLowerCase(), Date.now());
}

function revokeClientGrants(email, clientId) {
  db.prepare('UPDATE oauth_grants SET revoked = 1 WHERE email = ? AND client_id = ?')
    .run(email.toLowerCase(), clientId);
}

function purgeExpiredOAuth() {
  db.prepare('DELETE FROM oauth_codes WHERE used = 1 OR expires_at < ?').run(Date.now());
  db.prepare('DELETE FROM oauth_grants WHERE refresh_expires_at < ?').run(Date.now());
}

// --- User settings -----------------------------------------------------------

function getTimezone(email) {
  const row = db.prepare('SELECT timezone FROM user_settings WHERE email = ?').get(email.toLowerCase());
  return row ? row.timezone : 'Europe/London';
}

function setTimezone(email, timezone) {
  db.prepare(
    `INSERT INTO user_settings (email, timezone) VALUES (?, ?)
     ON CONFLICT(email) DO UPDATE SET timezone = excluded.timezone`
  ).run(email.toLowerCase(), timezone);
}

// --- Goals & completions -----------------------------------------------------

function listGoals(email, { includeArchived = false } = {}) {
  return db
    .prepare(
      `SELECT * FROM goals WHERE email = ? ${includeArchived ? '' : 'AND archived = 0'}
       ORDER BY sort_order ASC, id ASC`
    )
    .all(email.toLowerCase());
}

function getGoal(email, id) {
  return db.prepare('SELECT * FROM goals WHERE email = ? AND id = ?').get(email.toLowerCase(), id) || null;
}

function createGoal(email, { name, emoji, target, periodDays, hoursOffset }) {
  const maxOrder = db
    .prepare('SELECT COALESCE(MAX(sort_order), -1) AS m FROM goals WHERE email = ?')
    .get(email.toLowerCase()).m;
  const info = db
    .prepare(
      `INSERT INTO goals (email, name, emoji, target, period_days, hours_offset, sort_order, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run(email.toLowerCase(), name, emoji || null, target, periodDays, hoursOffset, maxOrder + 1, Date.now());
  return getGoal(email, info.lastInsertRowid);
}

function updateGoal(email, id, fields) {
  const allowed = { name: 'name', emoji: 'emoji', target: 'target', period_days: 'period_days',
    hours_offset: 'hours_offset', sort_order: 'sort_order', archived: 'archived' };
  const sets = [];
  const values = [];
  for (const [key, column] of Object.entries(allowed)) {
    if (fields[key] !== undefined) {
      sets.push(`${column} = ?`);
      values.push(fields[key]);
    }
  }
  if (sets.length) {
    db.prepare(`UPDATE goals SET ${sets.join(', ')} WHERE email = ? AND id = ?`)
      .run(...values, email.toLowerCase(), id);
  }
  return getGoal(email, id);
}

// Active completion count per goal within [fromMs, toMs).
function completionCounts(goalIds, fromMs, toMs) {
  if (!goalIds.length) return new Map();
  const rows = db
    .prepare(
      `SELECT goal_id, COUNT(*) AS c FROM completions
       WHERE goal_id IN (${goalIds.map(() => '?').join(',')})
         AND deleted_at IS NULL AND time >= ? AND time < ?
       GROUP BY goal_id`
    )
    .all(...goalIds, fromMs, toMs);
  return new Map(rows.map((r) => [r.goal_id, r.c]));
}

function listCompletions(goalId, fromMs, toMs) {
  return db
    .prepare(
      `SELECT id, time, source, note FROM completions
       WHERE goal_id = ? AND deleted_at IS NULL AND time >= ? AND time < ? ORDER BY time ASC`
    )
    .all(goalId, fromMs, toMs);
}

// Returns {id, created}; a repeated client_id is a no-op (created: false).
function addCompletion(goalId, { time, source, clientId, note }) {
  const info = db
    .prepare(
      `INSERT OR IGNORE INTO completions (goal_id, time, source, client_id, note) VALUES (?, ?, ?, ?, ?)`
    )
    .run(goalId, time, source, clientId || null, note || null);
  return { id: info.lastInsertRowid, created: info.changes === 1 };
}

// Soft-deletes the most recent active completion in [fromMs, toMs); returns it or null.
const undoLatestCompletion = db.transaction((goalId, fromMs, toMs) => {
  const row = db
    .prepare(
      `SELECT id, time FROM completions WHERE goal_id = ? AND deleted_at IS NULL
         AND time >= ? AND time < ? ORDER BY time DESC, id DESC LIMIT 1`
    )
    .get(goalId, fromMs, toMs);
  if (!row) return null;
  db.prepare('UPDATE completions SET deleted_at = ? WHERE id = ?').run(Date.now(), row.id);
  return row;
});

// --- E-ink displays ----------------------------------------------------------

function friendlyId() {
  return crypto.randomBytes(3).toString('hex').toUpperCase();
}

function getDisplayByMac(mac) {
  return db.prepare('SELECT * FROM displays WHERE mac = ?').get(mac) || null;
}

function getDisplayByKey(key) {
  const display = db
    .prepare("SELECT * FROM displays WHERE key_hash = ? AND status = 'active'")
    .get(hashToken(key));
  return display || null;
}

function getDisplayById(id) {
  return db.prepare('SELECT * FROM displays WHERE id = ?').get(id) || null;
}

// A device we've never seen asked to be set up; it waits for approval on the web page.
function createPendingDisplay(mac, fwVersion) {
  db.prepare(
    `INSERT OR IGNORE INTO displays (label, mac, friendly_id, status, created_at, fw_version)
     VALUES (?, ?, ?, 'pending', ?, ?)`
  ).run(`TRMNL ${mac.slice(-5)}`, mac, friendlyId(), Date.now(), fwVersion || null);
  return getDisplayByMac(mac);
}

// An already-known device called /api/setup again (factory reset, new
// firmware). Its existing key keeps working; a human must approve before a
// new key is handed out, so a spoofed MAC can't hijack or disable a display.
function requestSetup(mac) {
  db.prepare('UPDATE displays SET setup_requested_at = ? WHERE mac = ?').run(Date.now(), mac);
}

// Approving a device with a MAC parks a fresh key in pending_key for the
// device's next /api/setup call to claim; TRMNL devices can't be given a key
// any other way. Manual keys (no MAC) are returned once, right here.
function approveDisplay(email, { id, label }) {
  const key = crypto.randomBytes(24).toString('hex');
  if (id) {
    const info = db
      .prepare(
        `UPDATE displays SET email = ?, pending_key = ?, status = 'active', setup_requested_at = NULL,
           label = COALESCE(?, label)
         WHERE id = ? AND mac IS NOT NULL AND status IN ('pending', 'active')
           AND (email IS NULL OR email = ?)`
      )
      .run(email.toLowerCase(), key, label || null, id, email.toLowerCase());
    if (info.changes === 0) return null;
    return { key: null, display: db.prepare('SELECT * FROM displays WHERE id = ?').get(id) };
  }
  const info = db
    .prepare(
      `INSERT INTO displays (email, label, friendly_id, key_hash, status, created_at)
       VALUES (?, ?, ?, ?, 'active', ?)`
    )
    .run(email.toLowerCase(), label || 'Display', friendlyId(), hashToken(key), Date.now());
  return { key, display: db.prepare('SELECT * FROM displays WHERE id = ?').get(info.lastInsertRowid) };
}

// The device's /api/setup call collects its approved key (once).
const claimPendingKey = db.transaction((mac) => {
  const row = db.prepare("SELECT id, pending_key FROM displays WHERE mac = ? AND status = 'active'").get(mac);
  if (!row || !row.pending_key) return null;
  db.prepare('UPDATE displays SET key_hash = ?, pending_key = NULL WHERE id = ?')
    .run(hashToken(row.pending_key), row.id);
  return row.pending_key;
});

function listDisplays(email) {
  return db
    .prepare(
      `SELECT * FROM displays WHERE email = ? OR status = 'pending' ORDER BY status DESC, created_at ASC`
    )
    .all(email.toLowerCase());
}

function revokeDisplay(email, id) {
  db.prepare(
    `UPDATE displays SET status = 'revoked', key_hash = ? WHERE id = ? AND (email = ? OR status = 'pending')`
  ).run(`revoked:${crypto.randomBytes(8).toString('hex')}`, id, email.toLowerCase());
}

function updateDisplaySettings(email, id, { label, refreshRate }) {
  db.prepare(
    `UPDATE displays SET label = COALESCE(?, label), refresh_rate = COALESCE(?, refresh_rate)
     WHERE email = ? AND id = ?`
  ).run(label || null, refreshRate || null, email.toLowerCase(), id);
}

function touchDisplay(id, { batteryVoltage, fwVersion, rssi }) {
  db.prepare(
    `UPDATE displays SET last_seen_at = ?, battery_voltage = COALESCE(?, battery_voltage),
       fw_version = COALESCE(?, fw_version), rssi = COALESCE(?, rssi) WHERE id = ?`
  ).run(Date.now(), batteryVoltage ?? null, fwVersion || null, rssi ?? null, id);
}

module.exports = {
  getTimezone,
  setTimezone,
  listGoals,
  getGoal,
  createGoal,
  updateGoal,
  completionCounts,
  listCompletions,
  addCompletion,
  undoLatestCompletion,
  getDisplayByMac,
  getDisplayByKey,
  getDisplayById,
  createPendingDisplay,
  requestSetup,
  approveDisplay,
  claimPendingKey,
  listDisplays,
  revokeDisplay,
  updateDisplaySettings,
  touchDisplay,
  createDevice,
  deviceForToken,
  listDevices,
  revokeDevice,
  setDeviceError,
  insertFixes,
  fixCount,
  latestFix,
  fixesBetween,
  daysWithFixes,
  createOAuthClient,
  getOAuthClient,
  createAuthCode,
  consumeAuthCode,
  issueTokens,
  grantForAccessToken,
  rotateRefreshToken,
  listGrants,
  revokeClientGrants,
  purgeExpiredOAuth,
};
