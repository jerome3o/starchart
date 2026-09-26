'use strict';

// A minimal OAuth 2.1 authorization server for MCP clients (Claude.ai etc):
// RFC 8414 metadata, RFC 9728 protected-resource metadata, RFC 7591 dynamic
// client registration (public clients only), authorization-code grant with
// mandatory PKCE (S256), and rotating refresh tokens. The human consent step
// is the existing Google login + allow-list.

const crypto = require('crypto');
const express = require('express');
const db = require('./db');
const createLimiter = require('./ratelimit');

const SCOPES = {
  'location:read': 'Read your location history',
};
const ALL_SCOPES = Object.keys(SCOPES).join(' ');

function base64url(buffer) {
  return buffer.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function parseScope(requested) {
  if (!requested) return ALL_SCOPES;
  const parts = String(requested).split(/\s+/).filter(Boolean);
  if (parts.some((s) => !SCOPES[s])) return null;
  return parts.join(' ');
}

function createOAuthRouter({ issuer, requireAuth, page }) {
  const router = express.Router();
  const tokenLimiter = createLimiter({ max: 20, windowMs: 60_000 });
  const registerLimiter = createLimiter({ max: 10, windowMs: 60 * 60_000 });
  const resource = `${issuer}/mcp`;

  router.get('/.well-known/oauth-authorization-server', (_req, res) => {
    res.json({
      issuer,
      authorization_endpoint: `${issuer}/oauth/authorize`,
      token_endpoint: `${issuer}/oauth/token`,
      registration_endpoint: `${issuer}/oauth/register`,
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      scopes_supported: Object.keys(SCOPES),
    });
  });

  const protectedResource = (_req, res) => {
    res.json({
      resource,
      authorization_servers: [issuer],
      scopes_supported: Object.keys(SCOPES),
      bearer_methods_supported: ['header'],
    });
  };
  router.get('/.well-known/oauth-protected-resource', protectedResource);
  router.get('/.well-known/oauth-protected-resource/mcp', protectedResource);

  // --- Dynamic client registration (public clients, PKCE-only) --------------
  router.post('/oauth/register', express.json({ limit: '16kb' }), (req, res) => {
    if (registerLimiter.blocked(req.ip)) {
      return res.status(429).json({ error: 'too_many_requests' });
    }
    registerLimiter.fail(req.ip);
    const body = req.body || {};
    const uris = Array.isArray(body.redirect_uris) ? body.redirect_uris : [];
    const valid = uris.length > 0 && uris.length <= 10 && uris.every((u) => {
      try {
        const url = new URL(u);
        return url.protocol === 'https:' && !url.hash;
      } catch (_) {
        return false;
      }
    });
    if (!valid) {
      return res.status(400).json({
        error: 'invalid_redirect_uri',
        error_description: 'redirect_uris must be 1-10 https URLs',
      });
    }
    if (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') {
      return res.status(400).json({
        error: 'invalid_client_metadata',
        error_description: 'only public clients (token_endpoint_auth_method "none") are supported',
      });
    }
    const clientId = db.createOAuthClient(body.client_name || 'Unnamed MCP client', uris);
    res.status(201).json({
      client_id: clientId,
      client_name: body.client_name || 'Unnamed MCP client',
      redirect_uris: uris,
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
    });
  });

  // --- Authorization (Google login enforced by requireAuth) -----------------
  router.get('/oauth/authorize', requireAuth, (req, res) => {
    const q = req.query;
    const client = q.client_id ? db.getOAuthClient(String(q.client_id)) : null;
    const redirectUri = String(q.redirect_uri || '');
    if (!client || !client.redirect_uris.includes(redirectUri)) {
      // Never redirect to an unregistered URI: show the error instead.
      return res.status(400).send(page('Invalid request', `<h1>🚫 Invalid OAuth request</h1>
        <p>Unknown client or unregistered redirect URI.</p>`));
    }
    const fail = (error, description) => {
      const url = new URL(redirectUri);
      url.searchParams.set('error', error);
      if (description) url.searchParams.set('error_description', description);
      if (q.state) url.searchParams.set('state', String(q.state));
      return res.redirect(url.toString());
    };
    if (q.response_type !== 'code') return fail('unsupported_response_type');
    if (!q.code_challenge || q.code_challenge_method !== 'S256') {
      return fail('invalid_request', 'PKCE with S256 is required');
    }
    const scope = parseScope(q.scope);
    if (!scope) return fail('invalid_scope');
    if (q.resource && String(q.resource) !== resource) {
      return fail('invalid_target', `resource must be ${resource}`);
    }

    const csrf = crypto.randomBytes(16).toString('hex');
    req.session.oauthConsent = {
      csrf,
      clientId: client.client_id,
      redirectUri,
      codeChallenge: String(q.code_challenge),
      scope,
      state: q.state ? String(q.state) : null,
      resource: q.resource ? String(q.resource) : null,
    };
    const scopeList = scope.split(' ').map((s) => `<li>${escapeHtml(SCOPES[s])}</li>`).join('');
    res.send(page('Connect — Starchart', `
      <h1>🔗 Connect ${escapeHtml(client.client_name)}</h1>
      <p><strong>${escapeHtml(client.client_name)}</strong> wants access to your Starchart data as
      <strong>${escapeHtml(req.session.user.email)}</strong>:</p>
      <ul style="text-align:left">${scopeList}</ul>
      <form method="POST" action="/oauth/authorize/decision" style="display:inline">
        <input type="hidden" name="csrf" value="${csrf}" />
        <button class="btn" name="decision" value="approve" type="submit">Allow</button>
        <button class="btn" name="decision" value="deny" type="submit" style="background:#7a3b3b">Deny</button>
      </form>
      <p class="muted">You can revoke this later from the Starchart home page.</p>`));
  });

  router.post('/oauth/authorize/decision', requireAuth, express.urlencoded({ extended: false }), (req, res) => {
    const consent = req.session.oauthConsent;
    req.session.oauthConsent = undefined;
    if (!consent || !req.body || req.body.csrf !== consent.csrf) {
      return res.status(400).send(page('Invalid request', '<h1>🚫 Consent expired</h1><p>Please start again from the app.</p>'));
    }
    const url = new URL(consent.redirectUri);
    if (consent.state) url.searchParams.set('state', consent.state);
    if (req.body.decision !== 'approve') {
      url.searchParams.set('error', 'access_denied');
      return res.redirect(url.toString());
    }
    const code = db.createAuthCode({
      clientId: consent.clientId,
      redirectUri: consent.redirectUri,
      codeChallenge: consent.codeChallenge,
      scope: consent.scope,
      email: req.session.user.email,
      resource: consent.resource,
    });
    url.searchParams.set('code', code);
    res.redirect(url.toString());
  });

  // --- Token endpoint --------------------------------------------------------
  router.post('/oauth/token', express.urlencoded({ extended: false }), (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.set('Pragma', 'no-cache');
    if (tokenLimiter.blocked(req.ip)) {
      return res.status(429).json({ error: 'too_many_requests' });
    }
    const body = req.body || {};
    const reject = (status, error, description) => {
      tokenLimiter.fail(req.ip);
      return res.status(status).json({ error, error_description: description });
    };
    const clientId = String(body.client_id || '');
    if (!clientId || !db.getOAuthClient(clientId)) return reject(401, 'invalid_client');

    if (body.grant_type === 'authorization_code') {
      const codeRow = body.code ? db.consumeAuthCode(String(body.code)) : null;
      if (!codeRow || codeRow.client_id !== clientId) return reject(400, 'invalid_grant');
      if (String(body.redirect_uri || '') !== codeRow.redirect_uri) return reject(400, 'invalid_grant');
      const verifier = String(body.code_verifier || '');
      if (verifier.length < 43 || verifier.length > 128) return reject(400, 'invalid_grant', 'bad code_verifier');
      const challenge = base64url(crypto.createHash('sha256').update(verifier).digest());
      if (challenge !== codeRow.code_challenge) return reject(400, 'invalid_grant', 'PKCE verification failed');
      if (body.resource && String(body.resource) !== resource) return reject(400, 'invalid_target');
      const tokens = db.issueTokens({ clientId, email: codeRow.email, scope: codeRow.scope });
      return res.json({
        access_token: tokens.accessToken,
        token_type: 'Bearer',
        expires_in: tokens.expiresIn,
        refresh_token: tokens.refreshToken,
        scope: codeRow.scope,
      });
    }

    if (body.grant_type === 'refresh_token') {
      const rotated = body.refresh_token ? db.rotateRefreshToken(String(body.refresh_token), clientId) : null;
      if (!rotated) return reject(400, 'invalid_grant');
      return res.json({
        access_token: rotated.accessToken,
        token_type: 'Bearer',
        expires_in: rotated.expiresIn,
        refresh_token: rotated.refreshToken,
        scope: rotated.scope,
      });
    }

    return reject(400, 'unsupported_grant_type');
  });

  router.post('/oauth/grants/:clientId/revoke', requireAuth, (req, res) => {
    db.revokeClientGrants(req.session.user.email, String(req.params.clientId));
    res.redirect('/');
  });

  // Bearer-token guard for the MCP endpoint (RFC 6750 + RFC 9728 challenge).
  function requireBearer(requiredScope) {
    const limiter = createLimiter({ max: 30, windowMs: 60_000 });
    return (req, res, next) => {
      const challenge = () => {
        res.set(
          'WWW-Authenticate',
          `Bearer realm="starchart", resource_metadata="${issuer}/.well-known/oauth-protected-resource"`
        );
        return res.status(401).json({ error: 'invalid_token' });
      };
      if (limiter.blocked(req.ip)) return res.status(429).json({ error: 'too_many_requests' });
      const header = req.get('authorization') || '';
      const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
      const grant = token ? db.grantForAccessToken(token) : null;
      if (!grant) {
        limiter.fail(req.ip);
        return challenge();
      }
      if (requiredScope && !grant.scope.split(' ').includes(requiredScope)) {
        res.set('WWW-Authenticate', `Bearer realm="starchart", error="insufficient_scope", scope="${requiredScope}"`);
        return res.status(403).json({ error: 'insufficient_scope' });
      }
      req.grant = grant;
      next();
    };
  }

  return { router, requireBearer };
}

module.exports = { createOAuthRouter };
