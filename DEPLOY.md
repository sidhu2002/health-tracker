# Deploy runbook — Cloudflare (free tier)

Everything below is what YOU do. I've already prepared all code.

**Your production auth token (save this — needed twice):**
```
ab9914c8240fce514ac7128bdcf2052f90b164b17c615d94a4da4c6219bd38be
```
(64-char hex, generated locally. Not committed anywhere.)

---

## Phase A — Cloudflare account (5 min)

1. Open https://dash.cloudflare.com/sign-up in a browser.
2. Sign up with your email → verify.
3. It'll ask you to add a payment method. Add a card. You will not be billed at your usage.
4. On the dashboard sidebar → **Workers & Pages**. Confirm you can see the section.
5. Optional but recommended: sidebar → **Manage Account → Billing → Subscriptions**. Set a **spend cap of $0** so nothing can ever charge you.

---

## Phase B — Authorize wrangler CLI (2 min)

In PowerShell:

```powershell
cd C:\Users\user\Desktop\project\health-tracker\backend
npx wrangler login
```

This opens a browser tab. Click **Allow** — CLI now has permission to deploy to your account.

Verify:
```powershell
npx wrangler whoami
```
Should print your Cloudflare email.

---

## Phase C — Create remote D1 database (2 min)

```powershell
npx wrangler d1 create health
```

Output looks like:
```
✅ Successfully created DB 'health'
[[d1_databases]]
binding = "DB"
database_name = "health"
database_id = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
```

**Copy the `database_id` value.** Open `backend/wrangler.toml` in any text editor, replace `PASTE_D1_DATABASE_ID_HERE_AFTER_CREATE` with that UUID, save.

---

## Phase D — Apply schema to remote D1 (1 min)

```powershell
npx wrangler d1 execute health --remote --file=schema.sql
```

You should see 5 successful statements. This creates the same tables in the cloud DB.

---

## Phase E — Set the production token as a secret (1 min)

```powershell
npx wrangler secret put WATCH_TOKEN
```

When prompted, paste this exactly:
```
ab9914c8240fce514ac7128bdcf2052f90b164b17c615d94a4da4c6219bd38be
```

The secret overrides the placeholder `WATCH_TOKEN` in wrangler.toml at request time.

---

## Phase F — Deploy the Worker (1 min)

```powershell
npx wrangler deploy
```

Output ends with something like:
```
Deployed health-backend triggers (0.34 sec)
  https://health-backend.your-subdomain.workers.dev
```

**Copy that URL.** Test it:
```powershell
curl https://health-backend.your-subdomain.workers.dev/health
```
Should return `{"ok":true,"samples_stored":0}`.

Test that auth still blocks:
```powershell
curl -X POST https://health-backend.your-subdomain.workers.dev/v1/ingest -H "Authorization: Bearer wrong" -d "{}"
```
Should return `{"error":"unauthorized"}`.

---

## Phase G — Point the dashboard at the deployed Worker (1 min)

In `dashboard/`, create a new file `.env.production` (NOT `.env.production.example`):

```
VITE_API_BASE=https://health-backend.your-subdomain.workers.dev
```

Replace `your-subdomain` with your actual Cloudflare subdomain from Phase F.

---

## Phase H — Deploy the dashboard to Cloudflare Pages (3 min)

```powershell
cd ..\dashboard
npx wrangler pages deploy .svelte-kit\cloudflare --project-name health-dashboard
```

First time: it'll prompt you to create the project — say **yes**, pick a production branch name (just hit Enter for `main`).

Wait for upload (~1 min for ~500 files). Ends with:
```
✨ Deployment complete!
  https://<random>.health-dashboard.pages.dev
```

**Also copy this URL.** You get a stable alias too: `https://health-dashboard.pages.dev` (may vary based on subdomain).

Open the URL in a browser → the dashboard should load with the "Today" page. It'll show 0s because the remote D1 is empty (fresh DB). Charts will populate as your watch syncs to the new endpoint.

---

## Phase I — Point the watch at the deployed Worker (5 min, needs Android Studio)

Open Android Studio → the watch-app project.

Edit `app/src/main/java/dev/healthtracker/watch/Config.kt`:

1. Change `USE_PROD = false` → `USE_PROD = true`
2. Replace `PROD_BACKEND_URL` value with your deployed URL from Phase F (must be HTTPS).
3. Replace `PROD_WATCH_TOKEN` value with the same token from Phase E.

Save. With your Watch 4 still connected via `adb connect`, hit **Run ▶**. New APK installs on the watch pointing at the cloud.

On the watch, tap "Start collecting" → "Sync now". Check Logcat for `UploadWorker: uploaded N samples: HTTP 200`.

Then curl:
```powershell
curl https://health-backend.your-subdomain.workers.dev/v1/samples?metric=heart_rate&limit=5
```
You should see fresh samples in the cloud DB.

Refresh the deployed dashboard URL — chart populates.

---

## Phase J — Verify from ANY network (the whole point)

1. Turn off your laptop Wi-Fi entirely (or step outside home range with your watch + phone).
2. On your iPhone: open the Pages URL from Phase H in Safari.
3. Chart still loads. Watch still syncs (uses whatever internet it has).

That's the win: no more LAN-locked.

---

## Ongoing use

- **Access your dashboard from anywhere**: the Pages URL. Add to iPhone home screen (Share → Add to Home Screen).
- **Rotate the token later**: `npx wrangler secret put WATCH_TOKEN`, update Config.kt, reinstall app.
- **View logs**: Cloudflare dashboard → Workers & Pages → health-backend → Logs (real-time tailing).
- **Watch app IP changes**: no longer relevant. It talks to cloud, not your LAN.
- **Free tier headroom**: you'd need to do 100k+ ingest requests per DAY to hit any paid limit. You won't.

---

## Sharp edges

- **The dashboard URL is not authenticated.** Anyone who guesses your Pages URL can view your HR. It's obscure (a random subdomain) so casual guessing won't hit it, but treat the URL as sensitive. Don't share screenshots that include the URL bar. When you want to add auth later, tell me — Cloudflare Access with email PIN is a 3-minute setup.
- **The ingest URL is auth-gated.** Random visitors get 401. But rate-limit it via CF dashboard → Security → Rate Limiting → add rule: `POST /v1/ingest` → 60 requests/10 min per IP.
- **First cold request** to a fresh Worker can be ~50ms slower. Not noticeable.
- **Local dev still works** even after deploy. `npm run dev` in backend + dashboard, and Config.kt at `USE_PROD=false` — you can switch back to local-only testing anytime.
