# Starchart

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
   gh secret set GOOGLE_CLIENT_ID     --repo jerome3o/starchart   # paste Client ID
   gh secret set GOOGLE_CLIENT_SECRET --repo jerome3o/starchart   # paste Client secret
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
| `GOOGLE_CLIENT_ID` | GH Actions | Google OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | GH Actions | Google OAuth client secret |
| `SESSION_SECRET` | GH Actions | Cookie-signing secret |
| `ALLOWED_EMAILS` | GH Actions | Comma-separated login allow-list |

Set/rotate a secret:

```sh
gh secret set GOOGLE_CLIENT_SECRET   # paste value when prompted
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
