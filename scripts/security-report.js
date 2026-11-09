'use strict';

// Read-only security summary of the live database: who has access (OAuth
// apps, API tokens, devices, displays), what they did, and anything that
// doesn't belong to the allow-listed owner. Run on the Fly machine by the
// security-audit workflow, whose output is encrypted before it reaches the
// (public) Actions log. Prints no secrets: token hashes and keys are omitted.

const path = require('path');
const Database = require('better-sqlite3');

const dir = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const db = new Database(path.join(dir, 'starchart.db'), { readonly: true, fileMustExist: true });
const allowed = (process.env.ALLOWED_EMAILS || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);

const iso = (ms) => (ms ? new Date(ms).toISOString().replace('.000Z', 'Z') : '-');
const out = [];
const say = (s = '') => out.push(s);
const table = (title, rows) => {
  say(`== ${title} (${rows.length})`);
  for (const r of rows) say('  ' + Object.entries(r).map(([k, v]) => `${k}=${/_at$|^time$|^first|^last/.test(k) && typeof v === 'number' ? iso(v) : v}`).join(' | '));
};
const has = (t) => Boolean(db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").get(t));

say(`generated ${iso(Date.now())}; allow-list has ${allowed.length} email(s)`);

// Every email that appears anywhere: anything not on the allow-list means
// someone else got in at some point.
const emailCols = [['devices', 'email'], ['oauth_grants', 'email'], ['oauth_codes', 'email'], ['api_tokens', 'email'],
  ['goals', 'email'], ['displays', 'email'], ['user_settings', 'email'], ['nudges', 'email']];
const seen = new Map();
for (const [t, c] of emailCols) {
  if (!has(t)) continue;
  for (const r of db.prepare(`SELECT lower(${c}) AS e, COUNT(*) AS n FROM ${t} WHERE ${c} IS NOT NULL GROUP BY 1`).all()) {
    seen.set(r.e, (seen.get(r.e) || []).concat(`${t}:${r.n}`));
  }
}
say('== emails present in the database');
for (const [e, where] of seen) say(`  ${allowed.includes(e) ? 'OWNER  ' : 'UNKNOWN'} ${e.replace(/^(.).*(@.*)$/, '$1***$2')} -> ${where.join(', ')}`);

table('OAuth clients registered (dynamic registration is open to anyone)', db.prepare(
  `SELECT c.client_name AS name, c.redirect_uris AS redirects, c.created_at,
     (SELECT COUNT(*) FROM oauth_grants g WHERE g.client_id = c.client_id) AS grants
   FROM oauth_clients c ORDER BY c.created_at`).all());
table('OAuth grants (connected apps)', db.prepare(
  `SELECT c.client_name AS app, g.scope, g.created_at, g.last_used_at, g.revoked,
     CASE WHEN g.refresh_expires_at > strftime('%s','now')*1000 THEN 'live' ELSE 'expired' END AS refresh
   FROM oauth_grants g LEFT JOIN oauth_clients c ON c.client_id = g.client_id ORDER BY g.created_at`).all());
if (has('oauth_codes')) table('OAuth codes issued', db.prepare(
  `SELECT client_id AS client, scope, used, expires_at FROM oauth_codes ORDER BY expires_at DESC LIMIT 30`).all());
if (has('api_tokens')) table('Personal API tokens', db.prepare(
  `SELECT id, name, scope, created_at, last_used_at, revoked FROM api_tokens ORDER BY created_at`).all());
table('Devices (phones)', db.prepare(
  `SELECT d.id, d.label, d.created_at, d.last_seen_at,
     CASE WHEN d.token_hash LIKE 'revoked:%' THEN 'revoked' ELSE 'active' END AS token,
     (SELECT COUNT(*) FROM fixes f WHERE f.device_id = d.id) AS fixes,
     (SELECT MIN(time) FROM fixes f WHERE f.device_id = d.id) AS first_fix,
     (SELECT MAX(time) FROM fixes f WHERE f.device_id = d.id) AS last_fix
   FROM devices d ORDER BY d.id`).all());
table('E-ink displays', db.prepare(
  `SELECT id, friendly_id, label, status, CASE WHEN email IS NULL THEN 'unclaimed' ELSE 'owned' END AS owner,
     substr(mac, -5) AS mac_tail, fw_version AS fw, created_at, last_seen_at,
     CASE WHEN pending_key IS NOT NULL THEN 'yes' ELSE 'no' END AS key_waiting
   FROM displays ORDER BY created_at`).all());
if (has('phone_commands')) {
  table('Phone commands by requester', db.prepare(
    `SELECT requested_by, command, status, COUNT(*) AS n, MIN(created_at) AS first_at, MAX(created_at) AS last_at
     FROM phone_commands GROUP BY 1, 2, 3 ORDER BY last_at DESC`).all());
}
if (has('tracking_events')) table('Phone-side events of note', db.prepare(
  `SELECT kind, COUNT(*) AS n, MAX(time) AS last_at FROM tracking_events
   WHERE kind IN ('start_failed', 'process_died', 'phone_command', 'watchdog_restart') GROUP BY kind`).all());
table('Row counts', db.prepare(
  `SELECT name AS tbl FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`).all()
  .map((r) => ({ tbl: r.tbl, rows: db.prepare(`SELECT COUNT(*) AS n FROM "${r.tbl}"`).get().n })));

process.stdout.write(out.join('\n') + '\n');
