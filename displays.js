'use strict';

// E-ink display endpoints. Device side implements the TRMNL BYOS protocol
// (/api/setup, /api/display, /api/log). Keys are only ever handed out right
// after a human approves the device on the web page: unknown devices show up
// as "pending", and a known device asking to be set up again needs
// re-approval before it gets a new key (its old key keeps working meanwhile).

const crypto = require('crypto');
const express = require('express');
const db = require('./db');
const render = require('./render');
const createLimiter = require('./ratelimit');

const IMAGE_TOKEN_TTL_MS = 5 * 60 * 1000;

function base64url(buf) {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Log lines mention only the tail of the MAC; Actions log output is public.
function macTail(mac) {
  return mac ? mac.slice(-5) : '?';
}

function normalizeMac(raw) {
  const mac = String(raw || '').trim().toUpperCase().replace(/[^0-9A-F]/g, '');
  return mac.length === 12 ? mac.match(/.{2}/g).join(':') : null;
}

function createDisplayRouter({ issuer, secret, requireAuth, page }) {
  const router = express.Router();
  const limiter = createLimiter({ max: 30, windowMs: 60_000 });

  // Short-lived signed token so the image URL (fetched without headers by
  // TRMNL firmware) can't be guessed or reused later.
  function imageToken(displayId) {
    const exp = Date.now() + IMAGE_TOKEN_TTL_MS;
    const payload = `${displayId}.${exp}`;
    const sig = base64url(crypto.createHmac('sha256', secret).update(payload).digest());
    return `${payload}.${sig}`;
  }
  function verifyImageToken(token) {
    const [id, exp, sig] = String(token).split('.');
    if (!id || !exp || !sig) return null;
    const expected = base64url(crypto.createHmac('sha256', secret).update(`${id}.${exp}`).digest());
    if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
    if (Number(exp) < Date.now()) return null;
    return Number(id);
  }

  // --- TRMNL device protocol -------------------------------------------------

  // TRMNL firmware calls GET /api/setup; POST is kept for other BYOS clients.
  const setupHandler = (req, res) => {
    if (limiter.blocked(req.ip)) return res.status(429).json({ status: 429, message: 'slow down' });
    const mac = normalizeMac(req.get('ID'));
    if (!mac) {
      limiter.fail(req.ip);
      return res.status(400).json({ status: 400, message: 'missing ID header' });
    }
    let display = db.getDisplayByMac(mac);
    if (!display) display = db.createPendingDisplay(mac, req.get('FW-Version'));
    if (display.status === 'revoked') {
      limiter.fail(req.ip);
      return res.status(403).json({ status: 403, message: 'display revoked' });
    }
    const key = db.claimPendingKey(mac);
    console.log(`[display] setup ${display.friendly_id} (…${macTail(mac)}) fw=${req.get('FW-Version') || '?'} status=${display.status} -> ${key ? 'key issued' : 'pending approval'}`);
    if (key) {
      return res.json({
        status: 200,
        api_key: key,
        friendly_id: display.friendly_id,
        // The firmware's setup-image download only accepts an exact-size 1-bit BMP.
        image_url: `${issuer}/display-image/${imageToken(display.id)}.bmp`,
        message: 'Welcome to Starchart',
      });
    }
    if (display.status === 'active') db.requestSetup(mac);
    limiter.fail(req.ip);
    res.status(202).json({
      status: 202,
      message: `Approve this display (${display.friendly_id}) at ${issuer} then retry setup`,
    });
  };
  router.get('/api/setup', setupHandler);
  router.post('/api/setup', setupHandler);

  router.get('/api/display', (req, res) => {
    if (limiter.blocked(req.ip)) return res.status(429).json({ status: 429, message: 'slow down' });
    const key = req.get('Access-Token');
    const display = key ? db.getDisplayByKey(String(key)) : null;
    if (!display) {
      limiter.fail(req.ip);
      // A device with a key from a previous server: surface it for approval,
      // which will adopt the key it presented.
      const mac = normalizeMac(req.get('ID'));
      console.log(`[display] display unknown key (…${macTail(mac)}) fw=${req.get('FW-Version') || '?'} key=${key ? 'present' : 'missing'}`);
      if (mac && key && String(key).length >= 8) {
        const pending = db.recordPresentedKey(mac, db.hashToken(String(key)), req.get('FW-Version'));
        return res.status(401).json({
          status: 401,
          message: `Approve display ${pending.friendly_id} at ${issuer} then refresh`,
        });
      }
      return res.status(401).json({ status: 401, message: 'invalid access token' });
    }
    db.touchDisplay(display.id, {
      batteryVoltage: req.get('Battery-Voltage') ? Number(req.get('Battery-Voltage')) : null,
      fwVersion: req.get('FW-Version'),
      rssi: req.get('RSSI') ? Number(req.get('RSSI')) : null,
    });
    console.log(`[display] display ${display.friendly_id} ok battery=${req.get('Battery-Voltage') || '?'} rssi=${req.get('RSSI') || '?'}`);
    res.json({
      status: 0,
      image_url: `${issuer}/display-image/${imageToken(display.id)}.png`,
      filename: `starchart-${Math.floor(Date.now() / 60000)}`,
      refresh_rate: display.refresh_rate,
      update_firmware: false,
      firmware_url: null,
      reset_firmware: false,
      special_function: 'sleep',
      image_url_timeout: 30,
    });
  });

  router.post('/api/log', express.json({ limit: '16kb' }), (req, res) => {
    const mac = normalizeMac(req.get('ID'));
    const display = mac ? db.getDisplayByMac(mac) : null;
    if (display && display.status === 'active') {
      const body = req.body || {};
      db.touchDisplay(display.id, {
        batteryVoltage: body.battery_voltage ?? null,
        fwVersion: body.firmware_version,
        rssi: body.rssi ?? null,
      });
    }
    res.json({ status: 'success', message: 'Log data received' });
  });

  router.get('/display-image/:token.:ext(png|bmp)', (req, res) => {
    const id = verifyImageToken(req.params.token);
    const row = id === null ? null : db.getDisplayById(id);
    if (!row || row.status !== 'active' || !row.email) return res.status(404).end();
    const bmp = req.params.ext === 'bmp';
    res.set('Content-Type', bmp ? 'image/bmp' : 'image/png');
    res.set('Cache-Control', 'no-store');
    console.log(`[display] image ${row.friendly_id} (${req.params.ext})`);
    res.send(bmp ? render.renderDashboardBmp(row.email) : render.renderDashboardPng(row.email));
  });

  // --- Web page management (session-authed) ----------------------------------

  router.get('/displays/preview.png', requireAuth, (req, res) => {
    res.set('Content-Type', 'image/png');
    res.set('Cache-Control', 'no-store');
    res.send(render.renderDashboardPng(req.session.user.email));
  });

  router.post('/displays/:id/approve', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
    const label = req.body && req.body.label ? String(req.body.label).slice(0, 60) : null;
    db.approveDisplay(req.session.user.email, { id: Number(req.params.id), label });
    res.redirect('/');
  });

  router.post('/displays/:id/revoke', requireAuth, (req, res) => {
    db.revokeDisplay(req.session.user.email, Number(req.params.id));
    res.redirect('/');
  });

  router.post('/displays/:id/settings', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
    const refresh = Number(req.body && req.body.refresh_rate);
    db.updateDisplaySettings(req.session.user.email, Number(req.params.id), {
      label: req.body && req.body.label ? String(req.body.label).slice(0, 60) : null,
      refreshRate: Number.isInteger(refresh) && refresh >= 60 && refresh <= 86400 ? refresh : null,
    });
    res.redirect('/');
  });

  // Manual key for non-TRMNL displays: shown exactly once.
  router.post('/displays/create-key', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
    const label = req.body && req.body.label ? String(req.body.label).slice(0, 60) : 'Display';
    const { key } = db.approveDisplay(req.session.user.email, { label });
    res.send(page('Display key — Starchart', `
      <h1>🔑 Display key</h1>
      <p>For <strong>${label.replace(/[<>&]/g, '')}</strong>. Copy it now — it won't be shown again.</p>
      <p><code style="word-break:break-all;font-size:1.05rem">${key}</code></p>
      <p class="muted">Send it as the <code>Access-Token</code> header to <code>GET ${issuer}/api/display</code>,
      then fetch the returned <code>image_url</code>.</p>
      <a class="btn" href="/">Done</a>`));
  });

  return router;
}

module.exports = { createDisplayRouter, normalizeMac };
