'use strict';

const crypto = require('crypto');
const express = require('express');
const cookieSession = require('cookie-session');
const db = require('./db');
const api = require('./api');
const { createOAuthRouter, SCOPES } = require('./oauth');
const { handleMcpRequest } = require('./mcp');
const { createDisplayRouter } = require('./displays');

const app = express();
const PORT = process.env.PORT || 8080;

const {
  GOOGLE_CLIENT_ID,
  GOOGLE_CLIENT_SECRET,
  SESSION_SECRET,
  BASE_URL,
  NODE_ENV,
} = process.env;

const isProd = NODE_ENV === 'production';
const { allowedEmails, isAllowed } = require('./config');

// Fail closed: without a real session secret anyone who has read this public
// code could forge a login cookie, so production refuses to start.
if (isProd && (!SESSION_SECRET || SESSION_SECRET.length < 16)) {
  console.error('SESSION_SECRET is missing or too short; refusing to start.');
  process.exit(1);
}
const sessionSecret = SESSION_SECRET || 'dev-insecure-secret-change-me';
if (!allowedEmails.length) {
  console.error('ALLOWED_EMAILS is empty: every sign-in will be refused.');
}

// Fly terminates TLS at its edge proxy; trust it so req.protocol is https.
app.set('trust proxy', 1);
app.disable('x-powered-by');

app.use(
  cookieSession({
    name: 'sess',
    keys: [sessionSecret],
    maxAge: 7 * 24 * 60 * 60 * 1000, // 7 days
    httpOnly: true,
    sameSite: 'lax',
    secure: isProd,
  })
);

// Browser hardening for every response: no framing (clickjacking on the
// consent and token pages), no MIME sniffing, no referrers (OAuth codes live
// in URLs), HTTPS only.
app.use((req, res, next) => {
  res.set('X-Frame-Options', 'DENY');
  res.set('Content-Security-Policy', "frame-ancestors 'none'; base-uri 'none'; form-action 'self' https:; object-src 'none'");
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('Referrer-Policy', 'no-referrer');
  res.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  if (isProd) res.set('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  next();
});

// Cross-site request forgery backstop for the signed-in web pages: a POST that
// says it came from another site is refused. (The session cookie is also
// SameSite=Lax.) Device, MCP and OAuth-client endpoints use bearer tokens and
// are exempt.
const tokenAuthedPaths = /^\/(api\/|mcp$|oauth\/token$|oauth\/register$)/;
app.use((req, res, next) => {
  if (req.method !== 'POST' || tokenAuthedPaths.test(req.path)) return next();
  const source = req.get('origin') || req.get('referer');
  if (!source) return next();
  let origin;
  try {
    origin = new URL(source).origin;
  } catch (_) {
    return res.status(403).send('Bad origin');
  }
  if (origin !== new URL(baseUrl(req)).origin) return res.status(403).send('Cross-site request refused');
  next();
});

const escHtml = (x) => String(x ?? '').replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;' }[c]));

function baseUrl(req) {
  return (BASE_URL || `${req.protocol}://${req.get('host')}`).replace(/\/$/, '');
}

function redirectUri(req) {
  return `${baseUrl(req)}/auth/google/callback`;
}

function oauthConfigured() {
  return Boolean(GOOGLE_CLIENT_ID && GOOGLE_CLIENT_SECRET);
}

// Sessions are signed cookies, so the server enforces their age itself (a
// copied cookie would otherwise work forever) and re-checks the allow-list.
const SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
function sessionValid(req) {
  const s = req.session;
  return Boolean(s && s.user && isAllowed(s.user.email) && s.iat && Date.now() - s.iat < SESSION_MAX_AGE_MS);
}

function requireAuth(req, res, next) {
  if (sessionValid(req)) return next();
  if (req.session && req.session.user) req.session = null;
  // Remember where the user was headed so login can resume it (pairing flow).
  if (req.session) req.session.returnTo = req.originalUrl;
  return res.redirect('/login');
}

function page(title, body) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${title}</title>
<style>
  :root { color-scheme: light dark; }
  *, *::before, *::after { box-sizing: border-box; }
  html, body { overflow-x: hidden; }
  code { overflow-wrap: anywhere; word-break: break-all; }
  label.scope { display: flex; gap: .5rem; align-items: flex-start; text-align: left; margin: .35rem 0; }
  label.scope input { margin-top: .2rem; flex: none; }
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         margin: 0; min-height: 100vh; display: grid; place-items: center;
         background: #0b1020; color: #e6e9f0; }
  .card { background: #151b2e; padding: 2.5rem 2.75rem; border-radius: 16px;
          box-shadow: 0 10px 40px rgba(0,0,0,.4); max-width: 420px; width: calc(100% - 24px);
          margin: 12px auto; text-align: center; overflow-wrap: anywhere; }
  @media (max-width: 480px) { .card { padding: 1.75rem 1.1rem; } }
  h1 { margin: 0 0 .5rem; font-size: 1.6rem; }
  p { color: #9aa4bf; line-height: 1.5; }
  a.btn, button.btn { display: inline-flex; align-items: center; gap: .5rem;
          background: #4f7cff; color: white; text-decoration: none; border: 0;
          padding: .7rem 1.2rem; border-radius: 10px; font-size: 1rem;
          cursor: pointer; margin-top: 1rem; }
  a.btn:hover, button.btn:hover { background: #3f6bec; }
  img.avatar { width: 72px; height: 72px; border-radius: 50%; margin-bottom: 1rem; }
  .muted { font-size: .85rem; color: #6b748f; margin-top: 1.5rem; }
</style>
</head>
<body><div class="card">${body}</div></body>
</html>`;
}

// Health check — always 200, independent of auth/config.
app.get('/healthz', (_req, res) => res.status(200).send('ok'));

app.get('/login', (req, res) => {
  if (sessionValid(req)) return res.redirect('/');
  if (!oauthConfigured()) {
    return res
      .status(500)
      .send(
        page(
          'Setup required',
          `<h1>⚙️ Setup required</h1>
           <p>Google OAuth isn't configured yet. Set <code>GOOGLE_CLIENT_ID</code>
           and <code>GOOGLE_CLIENT_SECRET</code> as secrets.</p>`
        )
      );
  }
  res.send(
    page(
      'Sign in — Starchart',
      `<h1>⭐ Starchart</h1>
       <p>Please sign in to continue.</p>
       <a class="btn" href="/auth/google">Sign in with Google</a>`
    )
  );
});

app.get('/auth/google', (req, res) => {
  if (!oauthConfigured()) return res.redirect('/login');
  const state = crypto.randomBytes(16).toString('hex');
  req.session.oauthState = state;
  const params = new URLSearchParams({
    client_id: GOOGLE_CLIENT_ID,
    redirect_uri: redirectUri(req),
    response_type: 'code',
    scope: 'openid email profile',
    state,
    access_type: 'online',
    prompt: 'select_account',
  });
  res.redirect(`https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`);
});

app.get('/auth/google/callback', async (req, res) => {
  const { code, state } = req.query;
  if (!code || !state || !req.session || state !== req.session.oauthState) {
    return res.status(400).send('Invalid OAuth state. Please try again.');
  }
  req.session.oauthState = undefined;

  try {
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: GOOGLE_CLIENT_ID,
        client_secret: GOOGLE_CLIENT_SECRET,
        redirect_uri: redirectUri(req),
        grant_type: 'authorization_code',
      }),
    });
    if (!tokenRes.ok) throw new Error(`token exchange failed: ${tokenRes.status}`);
    const tokens = await tokenRes.json();

    const userRes = await fetch('https://openidconnect.googleapis.com/v1/userinfo', {
      headers: { Authorization: `Bearer ${tokens.access_token}` },
    });
    if (!userRes.ok) throw new Error(`userinfo failed: ${userRes.status}`);
    const profile = await userRes.json();

    const email = (profile.email || '').toLowerCase();
    if (!profile.email_verified) {
      return res.status(403).send('Your Google email is not verified.');
    }
    // An empty allow-list lets nobody in.
    if (!isAllowed(email)) {
      const safe = email.replace(/[<>&"']/g, '');
      return res
        .status(403)
        .send(page('Access denied', `<h1>🚫 Access denied</h1><p>${safe} is not on the allow-list.</p><a class="btn" href="/logout">Try another account</a>`));
    }

    req.session.iat = Date.now();
    req.session.user = {
      email: profile.email,
      name: profile.name,
      picture: profile.picture,
    };
    const returnTo = req.session.returnTo;
    req.session.returnTo = undefined;
    // Only same-site paths: "//host" and "/\\host" would leave the site.
    res.redirect(returnTo && /^\/(?![\/\\])/.test(returnTo) ? returnTo : '/');
  } catch (err) {
    console.error('OAuth callback error:', err);
    res.status(500).send('Authentication failed. Please try again.');
  }
});

app.get('/logout', (req, res) => {
  req.session = null;
  res.redirect('/login');
});
app.post('/logout', (req, res) => {
  req.session = null;
  res.redirect('/login');
});

// --- Device pairing & sync API ---------------------------------------------

// Pairing, proof-of-possession style (like PKCE): the app opens this page with
// challenge = base64url(SHA-256(verifier)) and keeps the verifier. After an
// explicit confirm (a POST, so the cross-site check applies), the browser is
// sent back to the app with a short-lived one-time code. Only the holder of the
// verifier can exchange that code for a device token at /api/pair/exchange, so
// another app that intercepts the starchart:// link gets nothing usable.
const PAIR_CODE_TTL_MS = 5 * 60 * 1000;
const pairCodes = new Map(); // code -> { email, label, challenge, expires }
const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const cleanLabel = (raw) => String(raw || 'Android device').replace(/[^\p{L}\p{N} ._'()-]/gu, '').trim().slice(0, 40) || 'Android device';
const validChallenge = (c) => typeof c === 'string' && /^[A-Za-z0-9_-]{43}$/.test(c);

app.get('/pair/start', requireAuth, (req, res) => {
  res.set('Cache-Control', 'no-store');
  const challenge = String(req.query.challenge || '');
  const label = cleanLabel(req.query.label);
  if (!validChallenge(challenge)) {
    return res.status(400).send(page('Update the app', '<h1>📱 Update Starchart</h1><p>This version of the app can\'t link. Update it in Obtainium, then tap “Link to server” again.</p>'));
  }
  res.send(
    page(
      'Link device — Starchart',
      `<h1>📱 Link this phone?</h1>
       <p>Link <strong>${label}</strong> to <strong>${escHtml(req.session.user.email)}</strong>.
       It will be able to upload your location and run your goals.</p>
       <form method="POST" action="/pair/confirm">
         <input type="hidden" name="challenge" value="${challenge}" />
         <input type="hidden" name="label" value="${label}" />
         <button class="btn" type="submit">Link phone</button>
       </form>
       <p class="muted">Only continue if you just tapped “Link to server” in the Starchart app.</p>`
    )
  );
});

app.post('/pair/confirm', requireAuth, express.urlencoded({ extended: false, limit: '4kb' }), (req, res) => {
  res.set('Cache-Control', 'no-store');
  const challenge = String((req.body || {}).challenge || '');
  if (!validChallenge(challenge)) return res.status(400).send('Bad request');
  const now = Date.now();
  for (const [k, v] of pairCodes) if (v.expires < now) pairCodes.delete(k);
  if (pairCodes.size > 50) return res.status(429).send('Too many pending links; try again in a few minutes');
  const code = crypto.randomBytes(32).toString('hex');
  pairCodes.set(code, { email: req.session.user.email, label: cleanLabel((req.body || {}).label), challenge, expires: now + PAIR_CODE_TTL_MS });
  const deepLink = `starchart://pair?code=${code}`;
  res.send(
    page(
      'Link device — Starchart',
      `<h1>📱 Almost there</h1>
       <a class="btn" href="${deepLink}">Open Starchart to finish</a>
       <p class="muted">If nothing happens, tap the button. The link works once, for 5 minutes.</p>
       <script>location.href = ${JSON.stringify(deepLink)};</script>`
    )
  );
});

// No session or bearer: the code plus the matching verifier is the credential.
const pairLimiter = require('./ratelimit')({ max: 10, windowMs: 10 * 60_000 });
app.post('/api/pair/exchange', express.json({ limit: '2kb' }), (req, res) => {
  res.set('Cache-Control', 'no-store');
  if (pairLimiter.blocked(req.ip)) return res.status(429).json({ error: 'too_many_requests' });
  const { code, verifier } = req.body || {};
  const entry = typeof code === 'string' ? pairCodes.get(code) : null;
  if (entry) pairCodes.delete(code); // single use, even on a wrong verifier
  const ok = entry && entry.expires > Date.now() && typeof verifier === 'string' && verifier.length >= 43 && verifier.length <= 128 &&
    crypto.timingSafeEqual(Buffer.from(b64url(crypto.createHash('sha256').update(verifier).digest())), Buffer.from(entry.challenge));
  if (!ok || !isAllowed(entry.email)) {
    pairLimiter.fail(req.ip);
    return res.status(400).json({ error: 'invalid or expired code' });
  }
  res.json({ token: db.createDevice(entry.email, entry.label) });
});

app.post('/devices/:id/revoke', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
  db.revokeDevice(req.session.user.email, Number(req.params.id));
  res.redirect('/');
});

const issuer = (BASE_URL || `http://localhost:${PORT}`).replace(/\/$/, '');

// The TRMNL protocol fixes the device paths under /api, so its router goes
// before the bearer-guarded phone API.
app.use(createDisplayRouter({
  issuer, secret: sessionSecret, requireAuth, page,
}));

app.use('/api', api);

// --- MCP server for Claude.ai and other OAuth-capable MCP clients -----------

const oauth = createOAuthRouter({ issuer, requireAuth, page });
app.use(oauth.router);
db.purgeExpiredOAuth();

// Any valid grant may connect; each tool is offered only if its scope was granted.
app.post('/mcp', oauth.requireBearer(null), express.json({ limit: '1mb' }), (req, res, next) => {
  handleMcpRequest(req, res).catch(next);
});
app.all('/mcp', (_req, res) => {
  res.set('Allow', 'POST');
  res.status(405).json({ error: 'method_not_allowed' });
});

// --- Personal API tokens (for MCP clients without OAuth, e.g. the Gemini API)

app.post('/tokens', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
  const body = req.body || {};
  const name = String(body.name || '').trim().slice(0, 60) || 'API token';
  const requested = [].concat(body.scope || []).map(String).filter((x) => SCOPES[x]);
  if (!requested.length) {
    return res.status(400).send(page('Token — Starchart', '<h1>Pick at least one scope</h1><a class="btn" href="/">Back</a>'));
  }
  const scope = requested.join(' ');
  const token = db.createApiToken(req.session.user.email, name, scope);
  const esc = (x) => String(x).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  res.set('Cache-Control', 'no-store');
  res.send(page('API token — Starchart', `
    <h1>🔑 ${esc(name)}</h1>
    <p>Scopes: <code>${esc(scope)}</code>. Copy it now — it won't be shown again.</p>
    <p><code style="word-break:break-all;font-size:1rem">${token}</code></p>
    <p class="muted" style="text-align:left">Use it as a bearer token against <code>${issuer}/mcp</code>:<br />
    <code>Authorization: Bearer &lt;token&gt;</code>. The repo's <code>examples/gemini_starchart.py</code>
    runs Gemini with these tools.</p>
    <a class="btn" href="/">Done</a>`));
});

app.post('/tokens/:id/revoke', requireAuth, (req, res) => {
  db.revokeApiToken(req.session.user.email, Number(req.params.id));
  res.redirect('/');
});


app.get('/', requireAuth, (req, res) => {
  const u = req.session.user;
  const devices = db.listDevices(u.email);
  const fmt = (ms) => (ms ? new Date(ms).toISOString().replace('T', ' ').slice(0, 16) + ' UTC' : 'never');
  const deviceRows = devices.length
    ? devices
        .map(
          (d) => `<li style="text-align:left;margin:.5rem 0;">
            <strong>${d.label.replace(/[<>&]/g, '')}</strong> (#${d.id}) —
            ${d.fix_count} fixes, last fix ${fmt(d.last_fix_time)}
            <form method="POST" action="/devices/${d.id}/revoke" style="display:inline">
              <button class="btn" style="padding:.15rem .6rem;font-size:.8rem;background:#7a3b3b" type="submit">Revoke</button>
            </form>
            <div class="muted" style="margin:.2rem 0 0">
              linked ${fmt(d.created_at)} · last contact ${fmt(d.last_seen_at)}
              ${d.last_error ? `<br /><span style="color:#ff8a8a">last error: ${d.last_error.replace(/[<>&]/g, '')}</span>` : ''}
            </div>
          </li>`
        )
        .join('')
    : '<li>No devices linked yet — use “Link to server” in the Android app.</li>';
  const grants = db.listGrants(u.email);
  const grantRows = grants.length
    ? grants
        .map(
          (g) => `<li style="text-align:left;margin:.5rem 0;">
            <strong>${g.client_name.replace(/[<>&]/g, '')}</strong> —
            connected ${fmt(g.first_connected)}, last used ${fmt(g.last_used)}
            ${String(g.scopes || '').includes('phone:control') ? '— <strong>can run phone commands</strong>' : ''}
            <form method="POST" action="/oauth/grants/${g.client_id}/revoke" style="display:inline">
              <button class="btn" style="padding:.15rem .6rem;font-size:.8rem;background:#7a3b3b" type="submit">Revoke</button>
            </form>
          </li>`
        )
        .join('')
    : `<li>None. MCP endpoint: <code>${issuer}/mcp</code></li>`;
  const esc = (s) => String(s ?? '').replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const smallBtn = 'padding:.15rem .6rem;font-size:.8rem;margin-top:0';
  const commands = db.listPhoneCommands(u.email, 15);
  const commandRows = commands.length
    ? commands.map((c) => `<li style="text-align:left;margin:.3rem 0;">
        <code>${esc(c.command)}</code> — ${esc(c.status)}, ${fmt(c.created_at)} by ${esc(c.requested_by || 'unknown')}
      </li>`).join('')
    : '<li>None yet. Apps granted “phone:control” can run a fixed list of diagnostic/fix commands.</li>';
  const tokens = db.listApiTokens(u.email);
  const tokenRows = tokens.length
    ? tokens.map((t) => `<li style="text-align:left;margin:.5rem 0;">
        <strong>${esc(t.name)}</strong> — <code>${esc(t.scope)}</code>, created ${fmt(t.created_at)}, last used ${fmt(t.last_used_at)}
        <form method="POST" action="/tokens/${t.id}/revoke" style="display:inline">
          <button class="btn" style="${smallBtn};background:#7a3b3b" type="submit">Revoke</button>
        </form>
      </li>`).join('')
    : '<li>None. For MCP clients that can\'t sign in with Google, like the Gemini API.</li>';
  const scopeBoxes = Object.entries(SCOPES).map(([k, label]) =>
    `<label class="scope"><input type="checkbox" name="scope" value="${k}" ${k === 'goals:write' || k === 'phone:control' ? '' : 'checked'} /> ${esc(label)}</label>`).join(' ');
  const displays = db.listDisplays(u.email);
  const displayRows = displays.length
    ? displays
        .map((d) => {
          if (d.status === 'pending') {
            const how = 'asked to be set up — only approve it if you just pressed the button on that display';
            return `<li style="text-align:left;margin:.5rem 0;">
              <strong>New device ${esc(d.friendly_id)}</strong> (${esc(d.mac)}) ${how} ${fmt(d.created_at)}
              <form method="POST" action="/displays/${d.id}/approve" style="display:inline">
                <input name="label" placeholder="Label" maxlength="60" style="padding:.15rem .4rem;border-radius:6px;border:1px solid #2c365a;background:#1f2740;color:#e6e9f0" />
                <button class="btn" style="${smallBtn};background:#2f7a4b" type="submit">Approve</button>
              </form>
              <form method="POST" action="/displays/${d.id}/revoke" style="display:inline">
                <button class="btn" style="${smallBtn};background:#7a3b3b" type="submit">Reject</button>
              </form>
            </li>`;
          }
          if (d.status !== 'active') return '';
          const battery = Number.isFinite(Number(d.battery_voltage)) && d.battery_voltage !== null ? ` · ${Number(d.battery_voltage).toFixed(2)} V` : '';
          const resetup = d.setup_requested_at
            ? `<br /><span style="color:#ffcf6b">Asked to be set up again ${fmt(d.setup_requested_at)}. Only approve if you just reset this display yourself; the new key is collectable for 10 minutes.</span>
               <form method="POST" action="/displays/${d.id}/approve" style="display:inline">
                 <button class="btn" style="${smallBtn};background:#2f7a4b" type="submit">Approve re-setup</button>
               </form>`
            : d.pending_key ? '<br /><span class="muted">Approved — waiting for the device to fetch its key.</span>' : '';
          return `<li style="text-align:left;margin:.5rem 0;">
            <strong>${esc(d.label)}</strong> (${esc(d.friendly_id)}${d.mac ? `, ${esc(d.mac)}` : ', manual key'}) —
            last seen ${fmt(d.last_seen_at)}${battery}${d.fw_version ? ` · fw ${esc(d.fw_version)}` : ''}
            <form method="POST" action="/displays/${d.id}/settings" style="display:inline">
              every <input name="refresh_rate" type="number" min="60" max="86400" value="${d.refresh_rate}" style="width:5rem;padding:.15rem .3rem;border-radius:6px;border:1px solid #2c365a;background:#1f2740;color:#e6e9f0" /> s
              <button class="btn" style="${smallBtn}" type="submit">Save</button>
            </form>
            <form method="POST" action="/displays/${d.id}/revoke" style="display:inline">
              <button class="btn" style="${smallBtn};background:#7a3b3b" type="submit">Revoke</button>
            </form>${resetup}
          </li>`;
        })
        .join('')
    : `<li>None yet. Point a TRMNL at <code>${issuer}</code> and it will appear here for approval.</li>`;
  res.send(
    page(
      'Starchart',
      `${u.picture && /^https:\/\//.test(u.picture) ? `<img class="avatar" src="${esc(u.picture)}" alt="" referrerpolicy="no-referrer" />` : ''}
       <h1>⭐ Hello, ${esc(u.name || u.email)}</h1>
       <p>You're signed in as <strong>${esc(u.email)}</strong>.</p>
       <p style="margin-bottom:0"><strong>Devices</strong></p>
       <ul style="list-style:none;padding:0">${deviceRows}</ul>
       <p style="margin-bottom:0"><strong>Connected apps (MCP)</strong></p>
       <ul style="list-style:none;padding:0">${grantRows}</ul>
       <p style="margin-bottom:0"><strong>Phone commands</strong></p>
       <ul style="list-style:none;padding:0">${commandRows}</ul>
       <p style="margin-bottom:0"><strong>API tokens</strong></p>
       <ul style="list-style:none;padding:0">${tokenRows}</ul>
       <form method="POST" action="/tokens" style="margin:.25rem 0 1rem;text-align:left">
         <input name="name" placeholder="Token name, e.g. Gemini" maxlength="60" style="padding:.3rem .5rem;border-radius:8px;border:1px solid #2c365a;background:#1f2740;color:#e6e9f0" />
         <button class="btn" style="padding:.3rem .8rem;font-size:.9rem;margin-top:0" type="submit">Create token</button>
         <div class="muted" style="margin-top:.4rem">${scopeBoxes}</div>
       </form>
       <p style="margin-bottom:0"><strong>E-ink displays</strong> · <a href="/displays/preview.png" style="color:#9aa4bf">preview dashboard</a></p>
       <ul style="list-style:none;padding:0">${displayRows}</ul>
       <form method="POST" action="/displays/create-key" style="margin:.25rem 0 1rem">
         <input name="label" placeholder="Label for a non-TRMNL display" maxlength="60" style="padding:.3rem .5rem;border-radius:8px;border:1px solid #2c365a;background:#1f2740;color:#e6e9f0" />
         <button class="btn" style="padding:.3rem .8rem;font-size:.9rem;margin-top:0" type="submit">Create key</button>
       </form>
       <form method="POST" action="/logout"><button class="btn" type="submit">Sign out</button></form>
       <p class="muted">Deployed on Fly.io · authed with Google</p>`
    )
  );
});

app.listen(PORT, () => {
  console.log(`starchart listening on :${PORT} (env=${NODE_ENV || 'development'})`);
  require('./nudges').start();
});
