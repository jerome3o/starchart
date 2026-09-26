'use strict';

const express = require('express');
const db = require('./db');

const router = express.Router();

// Small in-memory limiter: block an IP for a minute after repeated bad tokens,
// so token guessing is hopeless even beyond the 256-bit search space.
const failures = new Map();
function tooManyFailures(ip) {
  const entry = failures.get(ip);
  return entry && entry.count >= 20 && Date.now() - entry.since < 60_000;
}
function recordFailure(ip) {
  const entry = failures.get(ip) || { count: 0, since: Date.now() };
  if (Date.now() - entry.since > 60_000) {
    entry.count = 0;
    entry.since = Date.now();
  }
  entry.count += 1;
  failures.set(ip, entry);
  if (failures.size > 10_000) failures.clear();
}

router.use(express.json({ limit: '1mb' }));

router.use((req, res, next) => {
  if (tooManyFailures(req.ip)) {
    return res.status(429).json({ error: 'too many attempts, slow down' });
  }
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  const device = token ? db.deviceForToken(token) : null;
  if (!device) {
    recordFailure(req.ip);
    return res.status(401).json({ error: 'invalid or missing token' });
  }
  req.device = device;
  next();
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
    return res.status(400).json({ error: 'expected {fixes: [...]} with at most 1000 items' });
  }
  const cleaned = [];
  for (const fix of fixes) {
    const clientId = Number(fix.clientId);
    const time = Number(fix.time);
    const lat = Number(fix.lat);
    const lon = Number(fix.lon);
    const accuracy = Number(fix.accuracy);
    if (
      !Number.isInteger(clientId) || clientId < 0 ||
      !Number.isFinite(time) || time <= 0 ||
      !Number.isFinite(lat) || lat < -90 || lat > 90 ||
      !Number.isFinite(lon) || lon < -180 || lon > 180 ||
      !Number.isFinite(accuracy) || accuracy < 0
    ) {
      return res.status(400).json({ error: 'invalid fix', fix });
    }
    cleaned.push({ clientId, time, lat, lon, accuracy });
  }
  const accepted = db.insertFixes(req.device.id, cleaned);
  res.json({ accepted, total: db.fixCount(req.device.id) });
});

module.exports = router;
