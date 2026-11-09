'use strict';

const express = require('express');
const db = require('./db');
const claude = require('./claude');
const createLimiter = require('./ratelimit');
const goals = require('./goals');
const { isAllowed } = require('./config');

const router = express.Router();

// Block an IP for a minute after repeated bad tokens, so token guessing is
// hopeless even beyond the 256-bit search space.
const limiter = createLimiter({ max: 20, windowMs: 60_000 });

// Authenticate before parsing bodies, so anonymous callers can't make the
// server read megabytes of JSON. Devices of accounts no longer on the
// allow-list stop working too.
router.use((req, res, next) => {
  if (limiter.blocked(req.ip)) {
    return res.status(429).json({ error: 'too many attempts, slow down' });
  }
  const match = /^bearer\s+(\S+)$/i.exec(req.get('authorization') || '');
  const device = match ? db.deviceForToken(match[1]) : null;
  if (!device || !isAllowed(device.email)) {
    limiter.fail(req.ip);
    return res.status(401).json({ error: 'invalid or missing token' });
  }
  req.device = device;
  next();
});

router.use(express.json({ limit: '1mb' }));

// Timestamps from the phone must be plausible: one bogus far-future value
// would otherwise break date formatting everywhere it's shown.
const MIN_TIME = Date.UTC(2015, 0, 1);
const plausibleTime = (t) => Number.isFinite(t) && t >= MIN_TIME && t <= Date.now() + 86400e3;

// The phone revoking its own token when the user taps Unlink.
router.post('/device/unlink', (req, res) => {
  db.revokeDevice(req.device.email, req.device.id);
  res.json({ unlinked: true });
});

router.get('/me', (req, res) => {
  res.json({
    email: req.device.email,
    deviceLabel: req.device.label,
    fixCount: db.fixCount(req.device.id),
  });
});

router.post('/fixes', (req, res) => {
  const fixes = req.body && req.body.fixes;
  if (!Array.isArray(fixes) || fixes.length > 1000) {
    db.setDeviceError(req.device.id, 'bad request body: expected {fixes: [...]} with at most 1000 items');
    return res.status(400).json({ error: 'expected {fixes: [...]} with at most 1000 items' });
  }
  // Invalid entries are skipped rather than failing the batch, so one bad
  // reading can't block the phone's sync forever.
  const cleaned = [];
  let skipped = 0;
  for (const fix of fixes) {
    if (!fix || typeof fix !== 'object') { skipped++; continue; }
    const clientId = Number(fix.clientId);
    const time = Number(fix.time);
    const lat = Number(fix.lat);
    const lon = Number(fix.lon);
    const accuracy = Number(fix.accuracy);
    if (
      !Number.isInteger(clientId) || clientId < 0 ||
      !plausibleTime(time) ||
      !Number.isFinite(lat) || lat < -90 || lat > 90 ||
      !Number.isFinite(lon) || lon < -180 || lon > 180 ||
      !Number.isFinite(accuracy) || accuracy < 0
    ) {
      skipped++;
      continue;
    }
    cleaned.push({ clientId, time, lat, lon, accuracy });
  }
  const accepted = db.insertFixes(req.device.id, cleaned);
  db.setDeviceError(req.device.id, skipped ? `${skipped} invalid fix(es) skipped` : null);
  res.json({ accepted, total: db.fixCount(req.device.id), pendingCommands: db.pendingCommandCount(req.device.id) });
});

// Tracking diagnostics from the phone (see LocationDb events on Android).
router.post('/events', (req, res) => {
  const events = req.body && req.body.events;
  if (!Array.isArray(events) || events.length > 1000) {
    return res.status(400).json({ error: 'expected {events: [...]} with at most 1000 items' });
  }
  const cleaned = [];
  for (const e of events) {
    if (!e || typeof e !== 'object') continue;
    const clientId = Number(e.clientId);
    const time = Number(e.time);
    if (!Number.isInteger(clientId) || clientId < 0 || !plausibleTime(time) ||
        typeof e.kind !== 'string' || !/^[a-z_]{1,40}$/.test(e.kind)) {
      continue;
    }
    const detail = typeof e.detail === 'string' ? e.detail.slice(0, 2000) : null;
    cleaned.push({ clientId, time, kind: e.kind, detail });
  }
  res.json({ accepted: db.insertTrackingEvents(req.device.id, cleaned) });
});

// Commands queued by MCP clients with phone:control (see mcp.js).
router.get('/commands', (req, res) => {
  res.json({ commands: db.takePhoneCommands(req.device.id) });
});

router.post('/commands/:id/result', (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return res.status(400).json({ error: 'bad id' });
  const body = req.body || {};
  const updated = db.completePhoneCommand(req.device.id, id, body.ok !== false, body.result);
  res.json({ updated });
});

// In-app chat with Claude (all MCP tools, as this device's user). Capped per
// device per hour and per account per day, and the history size is bounded,
// so a leaked device token can't run up the Claude bill.
const CHAT_PER_HOUR = 60;
const CHAT_PER_DAY = 300;
const MAX_HISTORY_CHARS = 60_000;
const chatUse = new Map(); // key -> timestamps
function underLimit(key, windowMs, max, now) {
  const recent = (chatUse.get(key) || []).filter((t) => now - t < windowMs);
  chatUse.set(key, recent);
  return recent.length < max;
}
router.post('/chat', async (req, res) => {
  if (!claude.enabled()) return res.status(503).json({ error: 'chat is not configured (no ANTHROPIC_API_KEY on the server)' });
  const history = Array.isArray(req.body && req.body.messages) ? req.body.messages : null;
  const valid = history && history.length > 0 && history.length <= 200 && history.every((m) =>
    m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string' && m.content.length <= 20000)
    && history[history.length - 1].role === 'user';
  if (!valid) return res.status(400).json({ error: 'expected {messages: [{role, content}]} ending with a user message' });
  // Keep the most recent messages that fit the size budget.
  let budget = MAX_HISTORY_CHARS;
  const trimmed = [];
  for (let i = history.length - 1; i >= 0 && budget - history[i].content.length >= 0; i--) {
    budget -= history[i].content.length;
    trimmed.unshift(history[i]);
  }
  if (!trimmed.length) return res.status(400).json({ error: 'message too long' });
  const now = Date.now();
  const deviceKey = `d:${req.device.id}`;
  const emailKey = `e:${req.device.email.toLowerCase()}`;
  if (!underLimit(deviceKey, 3600e3, CHAT_PER_HOUR, now) || !underLimit(emailKey, 86400e3, CHAT_PER_DAY, now)) {
    return res.status(429).json({ error: 'chat limit reached; try again in a while' });
  }
  chatUse.get(deviceKey).push(now);
  chatUse.get(emailKey).push(now);
  try {
    res.json(await claude.chat(req.device.email, req.device.label, trimmed));
  } catch (e) {
    console.error('[chat] failed:', e.status || '', e.message);
    res.status(502).json({ error: 'Claude request failed; try again shortly' });
  }
});

router.get('/settings', (req, res) => {
  res.json({ nudges_enabled: db.nudgesEnabled(req.device.email), claude_enabled: claude.enabled() });
});

router.post('/settings', (req, res) => {
  const body = req.body || {};
  if (typeof body.nudges_enabled === 'boolean') db.setNudgesEnabled(req.device.email, body.nudges_enabled);
  res.json({ nudges_enabled: db.nudgesEnabled(req.device.email), claude_enabled: claude.enabled() });
});

// Goal management for the phone (same device-token auth as above).
router.use(goals.router);

module.exports = router;
