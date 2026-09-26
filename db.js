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

module.exports = {
  createDevice,
  deviceForToken,
  listDevices,
  revokeDevice,
  setDeviceError,
  insertFixes,
  fixCount,
};
