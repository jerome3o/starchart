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

module.exports = {
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
