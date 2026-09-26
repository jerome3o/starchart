'use strict';

const crypto = require('crypto');
const express = require('express');
const cookieSession = require('cookie-session');
const db = require('./db');
const api = require('./api');

const app = express();
const PORT = process.env.PORT || 8080;

const {
  GOOGLE_CLIENT_ID,
  GOOGLE_CLIENT_SECRET,
  SESSION_SECRET,
  BASE_URL,
  ALLOWED_EMAILS = '',
  NODE_ENV,
} = process.env;

const isProd = NODE_ENV === 'production';
const allowedEmails = ALLOWED_EMAILS.split(',')
  .map((s) => s.trim().toLowerCase())
  .filter(Boolean);

// Fly terminates TLS at its edge proxy; trust it so req.protocol is https.
app.set('trust proxy', 1);

app.use(
  cookieSession({
    name: 'sess',
    keys: [SESSION_SECRET || 'dev-insecure-secret-change-me'],
    maxAge: 7 * 24 * 60 * 60 * 1000, // 7 days
    httpOnly: true,
    sameSite: 'lax',
    secure: isProd,
  })
);

function baseUrl(req) {
  return (BASE_URL || `${req.protocol}://${req.get('host')}`).replace(/\/$/, '');
}

function redirectUri(req) {
  return `${baseUrl(req)}/auth/google/callback`;
}

function oauthConfigured() {
  return Boolean(GOOGLE_CLIENT_ID && GOOGLE_CLIENT_SECRET);
}

function requireAuth(req, res, next) {
  if (req.session && req.session.user) return next();
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
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         margin: 0; min-height: 100vh; display: grid; place-items: center;
         background: #0b1020; color: #e6e9f0; }
  .card { background: #151b2e; padding: 2.5rem 2.75rem; border-radius: 16px;
          box-shadow: 0 10px 40px rgba(0,0,0,.4); max-width: 420px; width: 90%;
          text-align: center; }
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
  if (req.session && req.session.user) return res.redirect('/');
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
    if (allowedEmails.length && !allowedEmails.includes(email)) {
      return res
        .status(403)
        .send(page('Access denied', `<h1>🚫 Access denied</h1><p>${email} is not on the allow-list.</p><a class="btn" href="/logout">Try another account</a>`));
    }

    req.session.user = {
      email: profile.email,
      name: profile.name,
      picture: profile.picture,
    };
    const returnTo = req.session.returnTo;
    req.session.returnTo = undefined;
    res.redirect(returnTo && returnTo.startsWith('/') ? returnTo : '/');
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

// Opened by the Android app in a browser tab. After Google login, mints a
// device token and hands it back to the app via the starchart:// deep link.
// The token only ever travels inside this device (custom-scheme intents are
// resolved locally), and the server keeps just its hash.
app.get('/pair/start', requireAuth, (req, res) => {
  const label = String(req.query.label || 'Android device').slice(0, 64);
  const token = db.createDevice(req.session.user.email, label);
  const deepLink = `starchart://pair?token=${token}`;
  res.send(
    page(
      'Link device — Starchart',
      `<h1>📱 Almost there</h1>
       <p>Linking <strong>${label.replace(/[<>&]/g, '')}</strong> to
       <strong>${req.session.user.email}</strong>.</p>
       <a class="btn" href="${deepLink}">Open Starchart to finish</a>
       <p class="muted">If nothing happens, open the Starchart app manually and try again.</p>
       <script>location.href = ${JSON.stringify(deepLink)};</script>`
    )
  );
});

app.post('/devices/:id/revoke', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
  db.revokeDevice(req.session.user.email, Number(req.params.id));
  res.redirect('/');
});

app.use('/api', api);

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
  res.send(
    page(
      'Starchart',
      `${u.picture ? `<img class="avatar" src="${u.picture}" alt="" referrerpolicy="no-referrer" />` : ''}
       <h1>⭐ Hello, ${u.name || u.email}</h1>
       <p>You're signed in as <strong>${u.email}</strong>.</p>
       <ul style="list-style:none;padding:0">${deviceRows}</ul>
       <form method="POST" action="/logout"><button class="btn" type="submit">Sign out</button></form>
       <p class="muted">Deployed on Fly.io · authed with Google</p>`
    )
  );
});

app.listen(PORT, () => {
  console.log(`starchart listening on :${PORT} (env=${NODE_ENV || 'development'})`);
});
