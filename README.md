# Starchart

Two pieces live in this repo:

- **`android/`** — a native Android app (Kotlin), released as APKs on GitHub
  releases so it can be installed via [Obtainium](https://github.com/ImranR98/Obtainium).
- **The root webapp** — a minimal Express app with Google OAuth login, deployed
  to [Fly.io](https://fly.io) via GitHub Actions (the future backend component).

## Android app

A placeholder star chart: a few hardcoded goals you can award stars to, plus
the plumbing that actually matters right now — install/launch, notification
permission, an immediate test notification, a "remind me in a minute" scheduled
notification (WorkManager), and a daily reminder toggle.

Since then it has grown a tilt-ball screen overlay, ~1/minute location
tracking into a local SQLite DB, a native day-by-day explorer map (MapLibre on
OpenFreeMap tiles, no API key), server sync, and a "Permissions & reliability"
panel that shows every permission the app wants with tap-to-grant rows.
Tracking auto-resumes when the app is opened and, with all-the-time location,
after reboots and updates.

The app is three bottom tabs: **Star chart** (goals), **Map** (explorer) and
**Settings** (map style, permissions, tracking, sync, notification tests,
overlay). Each tab is a Fragment hosted by `MainActivity`.

### Installing via Obtainium

1. In Obtainium: **Add App**, and use this repo's URL:
   `https://github.com/jerome3o/starchart`
2. Obtainium picks the latest GitHub release and installs the attached APK.
   New releases show up in Obtainium as updates.

### Cutting a release

Push a tag like `v0.1.0` (or create a GitHub release with such a tag):

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The `Android Release` workflow builds the APK, names the app version after the
tag, and attaches `starchart-v0.2.0.apk` to a GitHub release. The `Android CI`
workflow also builds the APK on every push touching `android/` as a smoke test.

### Signing

The APK is signed with the keystore committed at
`android/keystore/starchart-release.jks` (passwords in
`android/app/build.gradle.kts`). This is deliberate: Obtainium requires every
release to be signed with the same key, and this key's only job is to let your
phone accept updates for a personal sideloaded app — it guards no data or
accounts. Don't reuse it for anything that matters (e.g. a Play Store app).

### Local build

```sh
cd android && ./gradlew assembleRelease
# APK at android/app/build/outputs/apk/release/app-release.apk
```

Requires an Android SDK (set `sdk.dir` in `android/local.properties`).

## Server sync

The app can upload its location history to the webapp's API:

- **Pairing:** "Link to server" in the app opens `/pair/start` in the browser —
  Google login (allow-list enforced) → the server mints a random device token
  (stores only its SHA-256 hash) → hands it back via a `starchart://pair` deep
  link. The Google OAuth secrets never touch the phone; the phone token is kept
  in a prefs file excluded from Android backups.
- **Sync:** a WorkManager job uploads unsynced fixes hourly (plus "Sync now")
  in idempotent batches of 500 to `POST /api/fixes` (bearer auth, per-IP
  failure rate limiting). Nothing is ever deleted, on the phone or the server.
- **Storage:** SQLite via better-sqlite3 on a Fly volume (`starchart_data`,
  created automatically by the deploy workflow) at `/data`.
- **Management:** the webapp home page lists linked devices with fix counts
  and revoke buttons. Revoking only invalidates the token — fixes are kept.

## MCP server (for Claude.ai)

The webapp exposes the location data over MCP at `https://starchart.fly.dev/mcp`
(Streamable HTTP, stateless). Access is via OAuth 2.1, handled by the webapp
itself:

- **Discovery:** `/.well-known/oauth-authorization-server` and
  `/.well-known/oauth-protected-resource`; unauthenticated `/mcp` calls get a
  401 with a `resource_metadata` challenge, so clients find the flow on their own.
- **Registration:** dynamic (`/oauth/register`), public clients only, https
  redirect URIs only.
- **Authorization:** `/oauth/authorize` sits behind the Google login +
  allow-list and shows a consent page (CSRF-protected). PKCE (S256) is
  mandatory; codes are single-use and expire in 10 minutes.
- **Tokens:** access tokens live 1 hour, refresh tokens 90 days and rotate on
  every use; only SHA-256 hashes are stored. Token-endpoint and bearer
  failures are rate-limited per IP.
- **Revocation:** the home page lists connected apps with revoke buttons.

Tools: `get_latest_location`, `list_devices`, `list_days_with_data`,
`get_day_summary`, `get_location_history` — all scoped to the signed-in user.

**Connect Claude.ai:** Settings → Connectors → Add custom connector → URL
`https://starchart.fly.dev/mcp` → Connect. You'll be sent through Google
login and a consent page; after that Claude can call the tools.

## Webapp

A minimal Express webapp with Google OAuth login, deployed to [Fly.io](https://fly.io)
via GitHub Actions. Login is gated to an allow-list of Google accounts.

## ✅ What's done / ⬜ What you need to do

Already set up for you:
- ✅ Fly app `starchart` created (org `personal`); `fly` CLI authed locally.
- ✅ GitHub Actions secrets set: `FLY_API_TOKEN`, `SESSION_SECRET`, `ALLOWED_EMAILS`.
- ✅ Deploy workflow, Dockerfile, `fly.toml`, and the app itself.

**You need to do this before the first deploy will work:**

1. ⬜ **Create the Google OAuth client** (2 min) —
   <https://console.cloud.google.com/apis/credentials> →
   **Create Credentials → OAuth client ID → Web application**.
   Add this **Authorized redirect URI**:
   ```
   https://starchart.fly.dev/auth/google/callback
   ```
2. ⬜ **Add the two remaining secrets** from that client:
   ```sh
   gh secret set GOOGLE_OAUTH_CLIENT_ID     --repo jerome3o/starchart   # paste Client ID
   gh secret set GOOGLE_OAUTH_CLIENT_SECRET --repo jerome3o/starchart   # paste Client secret
   ```
3. ⬜ **Trigger a deploy** — push any commit to `main`, or run
   `gh workflow run "Deploy to Fly.io" --repo jerome3o/starchart`.

Then visit <https://starchart.fly.dev> and sign in. Only emails in `ALLOWED_EMAILS`
(currently `jeromeswannack@gmail.com`) can log in.

## How it works

- `server.js` — Express app: `/login` → Google OAuth → allow-list check → `/`.
- **All secrets live in GitHub Actions secrets.** On every push to `main`, the
  workflow (`.github/workflows/fly-deploy.yml`) stages those secrets onto Fly as
  runtime secrets and deploys. GitHub Actions is the single source of truth.

## Secrets

| Name | Where | What |
|------|-------|------|
| `FLY_API_TOKEN` | GH Actions | Fly deploy token (app-scoped) |
| `GOOGLE_OAUTH_CLIENT_ID` | GH Actions | Google OAuth client ID |
| `GOOGLE_OAUTH_CLIENT_SECRET` | GH Actions | Google OAuth client secret |
| `SESSION_SECRET` | GH Actions | Cookie-signing secret |
| `ALLOWED_EMAILS` | GH Actions | Comma-separated login allow-list |

Set/rotate a secret:

```sh
gh secret set GOOGLE_OAUTH_CLIENT_SECRET   # paste value when prompted
```

## Google OAuth client setup (one-time, manual)

1. Go to <https://console.cloud.google.com/apis/credentials>.
2. **Create Credentials → OAuth client ID → Web application**.
3. **Authorized redirect URI:** `https://starchart.fly.dev/auth/google/callback`
4. Copy the Client ID and Client secret into the GH secrets above.

## Local development

```sh
cp .env.example .env   # fill in values
npm install
npm start              # http://localhost:8080
```

For local OAuth, add `http://localhost:8080/auth/google/callback` as an
authorized redirect URI too, and set `BASE_URL=http://localhost:8080` in `.env`.
