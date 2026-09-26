'use strict';

const express = require('express');
const db = require('./db');
const createLimiter = require('./ratelimit');
const goals = require('./goals');

const router = express.Router();

// Block an IP for a minute after repeated bad tokens, so token guessing is
// hopeless even beyond the 256-bit search space.
const limiter = createLimiter({ max: 20, windowMs: 60_000 });

router.use(express.json({ limit: '1mb' }));

router.use((req, res, next) => {
  if (limiter.blocked(req.ip)) {
    return res.status(429).json({ error: 'too many attempts, slow down' });
  }
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  const device = token ? db.deviceForToken(token) : null;
  if (!device) {
    limiter.fail(req.ip);
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
    db.setDeviceError(req.device.id, 'bad request body: expected {fixes: [...]} with at most 1000 items');
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
      db.setDeviceError(req.device.id, `invalid fix rejected: ${JSON.stringify(fix).slice(0, 200)}`);
      return res.status(400).json({ error: 'invalid fix', fix });
    }
    cleaned.push({ clientId, time, lat, lon, accuracy });
  }
  const accepted = db.insertFixes(req.device.id, cleaned);
  db.setDeviceError(req.device.id, null);
  res.json({ accepted, total: db.fixCount(req.device.id) });
});

// Goal management for the phone (same device-token auth as above).
router.use(goals.router);

module.exports = router;
