# Codebase Reference


## backend/.dev.vars

```env
WATCH_TOKEN=dev-token-change-me-before-deploy

``n

## backend/.gitignore

```gitignore
node_modules/
.wrangler/
.dev.vars

``n

## backend/package.json

```json
{
  "name": "backend",
  "version": "1.0.0",
  "description": "",
  "main": "index.js",
  "scripts": {
    "dev": "wrangler dev",
    "dev:lan": "wrangler dev --ip 0.0.0.0 --port 8787",
    "db:init": "wrangler d1 execute health --local --file=schema.sql",
    "db:seed": "wrangler d1 execute health --local --file=seed.sql",
    "db:query": "wrangler d1 execute health --local --command",
    "deploy": "wrangler deploy",
    "deploy:db": "wrangler d1 execute health --remote --file=schema.sql"
  },
  "keywords": [],
  "author": "",
  "license": "ISC",
  "type": "commonjs",
  "devDependencies": {
    "@cloudflare/workers-types": "^4.20260702.1",
    "typescript": "^6.0.3",
    "wrangler": "^4.106.0"
  }
}

``n

## backend/schema.sql

```sql
-- Health tracker schema for Cloudflare D1 (SQLite)
-- Idempotent: safe to re-run.

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  model TEXT,
  owner TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  metric TEXT NOT NULL,
  ts INTEGER NOT NULL,
  value REAL NOT NULL,
  unit TEXT,
  meta TEXT
);

CREATE INDEX IF NOT EXISTS idx_samples_metric_ts ON samples(metric, ts DESC);
CREATE INDEX IF NOT EXISTS idx_samples_device_ts ON samples(device_id, ts DESC);

CREATE TABLE IF NOT EXISTS workouts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER,
  distance_m REAL,
  kcal REAL,
  avg_hr REAL,
  meta TEXT
);

CREATE INDEX IF NOT EXISTS idx_workouts_start ON workouts(start_ts DESC);

CREATE TABLE IF NOT EXISTS sleep_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER,
  stages TEXT,
  meta TEXT
);

CREATE INDEX IF NOT EXISTS idx_sleep_start ON sleep_sessions(start_ts DESC);

-- Seed a default device row so ingest works from the get-go
INSERT OR IGNORE INTO devices (id, model, owner, created_at)
VALUES ('galaxy-watch-4', 'Samsung Galaxy Watch 4', 'me', strftime('%s','now') * 1000);

``n

## backend/seed.sql

```sql
-- Fake sample data so the dashboard has something to render before the watch is live.
-- Run: npx wrangler d1 execute health --local --file=seed.sql

-- Clear existing seed rows (safe - won't touch real watch data since we insert distinct ts)
-- Insert 24 hours of fake heart_rate samples every 5 minutes, valued between 60-95 bpm.
WITH RECURSIVE
  minutes(i) AS (
    SELECT 0
    UNION ALL SELECT i + 5 FROM minutes WHERE i < 1435
  )
INSERT INTO samples (device_id, metric, ts, value, unit)
SELECT
  'galaxy-watch-4',
  'heart_rate',
  (strftime('%s','now') - (86400 - i * 60)) * 1000,
  60 + (abs(random()) % 36),
  'bpm'
FROM minutes;

-- One synthetic steps_daily datapoint
INSERT INTO samples (device_id, metric, ts, value, unit)
VALUES ('galaxy-watch-4', 'steps_daily', strftime('%s','now') * 1000, 7842, 'count');

-- One synthetic calories_daily
INSERT INTO samples (device_id, metric, ts, value, unit)
VALUES ('galaxy-watch-4', 'calories_daily', strftime('%s','now') * 1000, 2143, 'kcal');

-- A fake workout from earlier today
INSERT INTO workouts (device_id, kind, start_ts, end_ts, distance_m, kcal, avg_hr)
VALUES (
  'galaxy-watch-4',
  'RUNNING',
  (strftime('%s','now') - 6 * 3600) * 1000,
  (strftime('%s','now') - 6 * 3600 + 1800) * 1000,
  4200,
  312,
  148
);

``n

## backend/src/index.ts

```ts
// Personal health tracker Worker.
// Receives batched samples from a Wear OS watch app and serves them to a dashboard.
//
// Local dev:
//   npx wrangler dev  ->  http://localhost:8787  and  http://<lan-ip>:8787
// Prod:
//   Set WATCH_TOKEN as a real secret and swap the D1 database_id in wrangler.toml.

export interface Env {
  DB: D1Database;
  WATCH_TOKEN: string;
}

interface SampleIn {
  metric: string;
  ts: number;
  value: number;
  unit?: string;
  meta?: unknown;
}

interface WorkoutIn {
  kind: string;
  start_ts: number;
  end_ts?: number;
  distance_m?: number;
  kcal?: number;
  avg_hr?: number;
  meta?: unknown;
}

interface SleepIn {
  start_ts: number;
  end_ts?: number;
  stages?: unknown;
  meta?: unknown;
}

interface IngestBody {
  device_id: string;
  samples?: SampleIn[];
  workouts?: WorkoutIn[];
  sleep?: SleepIn[];
}

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Access-Control-Max-Age": "86400",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

function timingEq(a: string, b: string): boolean {
  if (!a || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

function authOk(req: Request, env: Env): boolean {
  const raw = req.headers.get("authorization") ?? "";
  const token = raw.replace(/^Bearer\s+/i, "").trim();
  return timingEq(token, env.WATCH_TOKEN);
}

async function handleIngest(req: Request, env: Env): Promise<Response> {
  if (!authOk(req, env)) return json({ error: "unauthorized" }, 401);

  let body: IngestBody;
  try {
    body = (await req.json()) as IngestBody;
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!body.device_id) return json({ error: "device_id_required" }, 400);

  const stmts: D1PreparedStatement[] = [];

  for (const s of body.samples ?? []) {
    if (!s.metric || typeof s.ts !== "number" || typeof s.value !== "number") continue;
    stmts.push(
      env.DB.prepare(
        "INSERT INTO samples (device_id, metric, ts, value, unit, meta) VALUES (?, ?, ?, ?, ?, ?)"
      ).bind(
        body.device_id,
        s.metric,
        s.ts,
        s.value,
        s.unit ?? null,
        s.meta ? JSON.stringify(s.meta) : null
      )
    );
  }

  for (const w of body.workouts ?? []) {
    if (!w.kind || typeof w.start_ts !== "number") continue;
    stmts.push(
      env.DB.prepare(
        "INSERT INTO workouts (device_id, kind, start_ts, end_ts, distance_m, kcal, avg_hr, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
      ).bind(
        body.device_id,
        w.kind,
        w.start_ts,
        w.end_ts ?? null,
        w.distance_m ?? null,
        w.kcal ?? null,
        w.avg_hr ?? null,
        w.meta ? JSON.stringify(w.meta) : null
      )
    );
  }

  for (const s of body.sleep ?? []) {
    if (typeof s.start_ts !== "number") continue;
    stmts.push(
      env.DB.prepare(
        "INSERT INTO sleep_sessions (device_id, start_ts, end_ts, stages, meta) VALUES (?, ?, ?, ?, ?)"
      ).bind(
        body.device_id,
        s.start_ts,
        s.end_ts ?? null,
        s.stages ? JSON.stringify(s.stages) : null,
        s.meta ? JSON.stringify(s.meta) : null
      )
    );
  }

  if (stmts.length > 0) {
    await env.DB.batch(stmts);
  }

  return json({
    ok: true,
    inserted: stmts.length,
    samples: body.samples?.length ?? 0,
    workouts: body.workouts?.length ?? 0,
    sleep: body.sleep?.length ?? 0,
  });
}

async function handleSamples(url: URL, env: Env): Promise<Response> {
  const metric = url.searchParams.get("metric") ?? "heart_rate";
  const now = Date.now();
  const from = Number(url.searchParams.get("from") ?? now - 86_400_000);
  const to = Number(url.searchParams.get("to") ?? now);
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 5000), 20_000);

  const rows = await env.DB.prepare(
    "SELECT ts, value, unit FROM samples WHERE metric = ? AND ts BETWEEN ? AND ? ORDER BY ts ASC LIMIT ?"
  )
    .bind(metric, from, to, limit)
    .all();

  return json({ metric, from, to, count: rows.results.length, samples: rows.results });
}

async function handleWorkouts(url: URL, env: Env): Promise<Response> {
  const now = Date.now();
  const from = Number(url.searchParams.get("from") ?? now - 7 * 86_400_000);
  const to = Number(url.searchParams.get("to") ?? now);
  const rows = await env.DB.prepare(
    "SELECT id, kind, start_ts, end_ts, distance_m, kcal, avg_hr FROM workouts WHERE start_ts BETWEEN ? AND ? ORDER BY start_ts DESC"
  )
    .bind(from, to)
    .all();
  return json({ from, to, count: rows.results.length, workouts: rows.results });
}

async function handleSummary(url: URL, env: Env): Promise<Response> {
  const dateParam = url.searchParams.get("date");
  const day = dateParam ? new Date(dateParam) : new Date();
  day.setUTCHours(0, 0, 0, 0);
  const from = day.getTime();
  const to = from + 86_400_000;

  const hr = await env.DB.prepare(
    "SELECT AVG(value) as avg_hr, MIN(value) as min_hr, MAX(value) as max_hr, COUNT(*) as n FROM samples WHERE metric = 'heart_rate' AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const steps = await env.DB.prepare(
    "SELECT COALESCE(MAX(value), 0) as steps FROM samples WHERE metric IN ('steps_daily','steps') AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const kcal = await env.DB.prepare(
    "SELECT COALESCE(MAX(value), 0) as kcal FROM samples WHERE metric IN ('calories_daily','calories') AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const workoutCount = await env.DB.prepare(
    "SELECT COUNT(*) as n FROM workouts WHERE start_ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  return json({
    date: new Date(from).toISOString().slice(0, 10),
    heart_rate: hr,
    steps: steps?.steps ?? 0,
    calories: kcal?.kcal ?? 0,
    workouts: workoutCount?.n ?? 0,
  });
}

async function handleHealth(env: Env): Promise<Response> {
  try {
    const row = await env.DB.prepare("SELECT COUNT(*) as n FROM samples").first();
    return json({ ok: true, samples_stored: row?.n ?? 0 });
  } catch (e) {
    return json({ ok: false, error: String(e) }, 500);
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(req.url);

    if (url.pathname === "/") {
      return json({ service: "health-backend", ok: true });
    }

    if (url.pathname === "/health") {
      return handleHealth(env);
    }

    if (url.pathname === "/v1/ingest" && req.method === "POST") {
      return handleIngest(req, env);
    }

    if (url.pathname === "/v1/samples" && req.method === "GET") {
      return handleSamples(url, env);
    }

    if (url.pathname === "/v1/workouts" && req.method === "GET") {
      return handleWorkouts(url, env);
    }

    if (url.pathname === "/v1/summary" && req.method === "GET") {
      return handleSummary(url, env);
    }

    return json({ error: "not_found", path: url.pathname }, 404);
  },
};

``n

## backend/tsconfig.json

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "lib": ["ES2022"],
    "types": ["@cloudflare/workers-types"],
    "isolatedModules": true,
    "noEmit": true
  },
  "include": ["src/**/*.ts"]
}

``n

## backend/wrangler.toml

```toml
name = "health-backend"
main = "src/index.ts"
compatibility_date = "2025-06-01"

# Bind the Worker to a custom domain. Cloudflare provisions SSL automatically.
routes = [
  { pattern = "health-api.siddeshwar.com", custom_domain = true }
]

# D1 database binding.
#   Local: wrangler auto-creates a SQLite file under .wrangler/state/ — database_id is ignored.
#   Remote (production): user runs `wrangler d1 create health`, copies the printed database_id
#   into the value below, then `wrangler deploy`.
[[d1_databases]]
binding = "DB"
database_name = "health"
database_id = "b5ddf8d5-4b51-4a8e-bd1d-d30bc13839be"

# WATCH_TOKEN is set as a Cloudflare secret in production:
#   wrangler secret put WATCH_TOKEN
#
# For LOCAL dev (`wrangler dev`), wrangler reads a `.dev.vars` file (gitignored) or you can
# temporarily add [vars] WATCH_TOKEN here. We intentionally omit it from wrangler.toml
# so deploys don't overwrite the production secret.

[dev]
ip = "0.0.0.0"
port = 8787

# Observability: Cloudflare's built-in logs work by default. No config needed.
[observability]
enabled = true

``n

## dashboard/.env.production

```production
VITE_API_BASE=https://health-api.siddeshwar.com

``n

## dashboard/.env.production.example

```env
# Copy to `.env.production` and fill in your deployed Worker URL after deploy.
# Vite bakes this into the production bundle at build time.
VITE_API_BASE=https://health-backend.YOUR-SUBDOMAIN.workers.dev

``n

## dashboard/package.json

```json
{
  "name": "dashboard",
  "version": "1.0.0",
  "description": "Personal health dashboard",
  "type": "module",
  "scripts": {
    "dev": "vite dev",
    "build": "vite build",
    "preview": "vite preview",
    "check": "svelte-kit sync && svelte-check --tsconfig ./tsconfig.json"
  },
  "devDependencies": {
    "@sveltejs/adapter-cloudflare": "^7.2.9",
    "@sveltejs/kit": "^2.69.0",
    "@sveltejs/vite-plugin-svelte": "^7.1.2",
    "autoprefixer": "^10.5.2",
    "daisyui": "^4.12.24",
    "postcss": "^8.5.16",
    "svelte": "^5.56.4",
    "svelte-check": "^4.7.1",
    "tailwindcss": "^3.4.19",
    "typescript": "^6.0.3",
    "vite": "^8.1.3"
  },
  "dependencies": {
    "uplot": "^1.6.32"
  }
}

``n

## dashboard/postcss.config.js

```js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};

``n

## dashboard/src/app.css

```css
@import 'uplot/dist/uPlot.min.css';

@tailwind base;
@tailwind components;
@tailwind utilities;

:root {
  font-family: system-ui, -apple-system, 'SF Pro Text', 'Segoe UI', sans-serif;
  -webkit-tap-highlight-color: transparent;
}

.safe-top { padding-top: max(env(safe-area-inset-top), 1rem); }
.safe-bottom { padding-bottom: max(env(safe-area-inset-bottom), 1rem); }

.uplot { color: inherit; }
.u-legend { color: inherit; }

``n

## dashboard/src/app.d.ts

```ts
declare global {
  namespace App {
    // interface Error {}
    // interface Locals {}
    // interface PageData {}
    // interface PageState {}
    // interface Platform {}
  }
}

export {};

``n

## dashboard/src/app.html

```html
<!doctype html>
<html lang="en" data-theme="night">
  <head>
    <meta charset="utf-8" />
    <link rel="icon" href="%sveltekit.assets%/favicon.png" />
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    <meta name="theme-color" content="#0f172a" />
    <meta name="apple-mobile-web-app-capable" content="yes" />
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
    <meta name="apple-mobile-web-app-title" content="Health" />
    <link rel="manifest" href="%sveltekit.assets%/manifest.webmanifest" />
    <link rel="apple-touch-icon" href="%sveltekit.assets%/icon-180.png" />
    <title>Health Tracker</title>
    %sveltekit.head%
  </head>
  <body data-sveltekit-preload-data="hover" class="min-h-screen bg-base-100">
    <div style="display: contents">%sveltekit.body%</div>
  </body>
</html>

``n

## dashboard/src/lib/api.ts

```ts
// Backend URL resolution:
//   - Vite dev (port 5173): talk to wrangler dev on same host, port 8787.
//   - Production build: use VITE_API_BASE env var baked in at build time
//     (set in Cloudflare Pages env vars or a local .env.production file).
//   - Fallback: same-origin (works if API is proxied by Pages Functions in future).

const VITE_API_BASE = (import.meta as { env: { VITE_API_BASE?: string } }).env.VITE_API_BASE ?? '';

export const API_BASE: string = (() => {
  if (typeof window === 'undefined') return VITE_API_BASE;
  if (window.location.port === '5173') return `http://${window.location.hostname}:8787`;
  return VITE_API_BASE;
})();

export interface Sample {
  ts: number;
  value: number;
  unit: string | null;
}

export interface Workout {
  id: number;
  kind: string;
  start_ts: number;
  end_ts: number | null;
  distance_m: number | null;
  kcal: number | null;
  avg_hr: number | null;
}

export interface Summary {
  date: string;
  heart_rate: { avg_hr: number | null; min_hr: number | null; max_hr: number | null; n: number };
  steps: number;
  calories: number;
  workouts: number;
}

export async function fetchSamples(metric: string, from?: number, to?: number, limit = 5000) {
  const url = new URL(`${API_BASE}/v1/samples`);
  url.searchParams.set('metric', metric);
  if (from) url.searchParams.set('from', String(from));
  if (to) url.searchParams.set('to', String(to));
  url.searchParams.set('limit', String(limit));
  const res = await fetch(url);
  if (!res.ok) throw new Error(`samples fetch failed: ${res.status}`);
  return (await res.json()) as { metric: string; from: number; to: number; count: number; samples: Sample[] };
}

export async function fetchSummary(date?: string) {
  const url = new URL(`${API_BASE}/v1/summary`);
  if (date) url.searchParams.set('date', date);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`summary fetch failed: ${res.status}`);
  return (await res.json()) as Summary;
}

export async function fetchWorkouts(from?: number, to?: number) {
  const url = new URL(`${API_BASE}/v1/workouts`);
  if (from) url.searchParams.set('from', String(from));
  if (to) url.searchParams.set('to', String(to));
  const res = await fetch(url);
  if (!res.ok) throw new Error(`workouts fetch failed: ${res.status}`);
  return (await res.json()) as { from: number; to: number; count: number; workouts: Workout[] };
}

``n

## dashboard/src/routes/+layout.svelte

```html
<script lang="ts">
  import '../app.css';
  import { page } from '$app/stores';
</script>

<div class="min-h-screen flex flex-col safe-top safe-bottom">
  <header class="navbar bg-base-200 px-4 shadow">
    <div class="flex-1">
      <a href="/" class="text-lg font-semibold">Health</a>
    </div>
    <div class="flex-none">
      <ul class="menu menu-horizontal px-1 gap-2">
        <li><a href="/" class:font-bold={$page.url.pathname === '/'}>Today</a></li>
        <li><a href="/hr" class:font-bold={$page.url.pathname === '/hr'}>HR</a></li>
        <li><a href="/workouts" class:font-bold={$page.url.pathname === '/workouts'}>Workouts</a></li>
      </ul>
    </div>
  </header>

  <main class="flex-1 p-4 max-w-3xl w-full mx-auto">
    <slot />
  </main>

  <footer class="text-center text-xs opacity-60 py-2">
    <span>personal health tracker</span>
  </footer>
</div>

``n

## dashboard/src/routes/+page.svelte

```html
<script lang="ts">
  import { onMount } from 'svelte';
  import { fetchSummary, type Summary } from '$lib/api';

  let summary: Summary | null = null;
  let error: string | null = null;
  let loading = true;

  async function load() {
    loading = true;
    error = null;
    try {
      summary = await fetchSummary();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  onMount(load);
</script>

<div class="space-y-4">
  <div class="flex items-center justify-between">
    <h1 class="text-2xl font-bold">Today</h1>
    <button class="btn btn-sm" on:click={load} disabled={loading}>
      {loading ? '…' : 'Refresh'}
    </button>
  </div>

  {#if error}
    <div class="alert alert-error">
      <span>Error: {error}</span>
    </div>
  {:else if summary}
    <div class="stats stats-vertical sm:stats-horizontal shadow bg-base-200 w-full">
      <div class="stat">
        <div class="stat-title">Avg HR</div>
        <div class="stat-value text-primary">{summary.heart_rate.avg_hr?.toFixed(0) ?? '—'}</div>
        <div class="stat-desc">
          {summary.heart_rate.min_hr ?? '—'} – {summary.heart_rate.max_hr ?? '—'} bpm
        </div>
      </div>
      <div class="stat">
        <div class="stat-title">Steps</div>
        <div class="stat-value">{summary.steps.toLocaleString()}</div>
        <div class="stat-desc">{summary.date}</div>
      </div>
      <div class="stat">
        <div class="stat-title">Calories</div>
        <div class="stat-value">{summary.calories.toLocaleString()}</div>
        <div class="stat-desc">kcal</div>
      </div>
      <div class="stat">
        <div class="stat-title">Workouts</div>
        <div class="stat-value">{summary.workouts}</div>
        <div class="stat-desc">today</div>
      </div>
    </div>

    <div class="card bg-base-200">
      <div class="card-body">
        <div class="card-title">Samples collected</div>
        <p>{summary.heart_rate.n} HR readings today.</p>
        <div class="card-actions">
          <a class="btn btn-primary btn-sm" href="/hr">See heart rate chart</a>
        </div>
      </div>
    </div>
  {:else}
    <div class="skeleton h-40 w-full"></div>
  {/if}
</div>

``n

## dashboard/src/routes/hr/+page.svelte

```html
<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import uPlot from 'uplot';
  import { fetchSamples, type Sample } from '$lib/api';

  let chartEl: HTMLDivElement;
  let chart: uPlot | null = null;
  let error: string | null = null;
  let loading = true;
  let samples: Sample[] = [];

  async function load() {
    loading = true;
    error = null;
    try {
      const now = Date.now();
      const from = now - 24 * 3600 * 1000;
      const res = await fetchSamples('heart_rate', from, now, 5000);
      samples = res.samples;
      renderChart();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  function renderChart() {
    if (!chartEl) return;
    if (chart) {
      chart.destroy();
      chart = null;
    }
    const xs = samples.map((s) => Math.floor(s.ts / 1000));
    const ys = samples.map((s) => s.value);
    const data: uPlot.AlignedData = [xs, ys];
    const opts: uPlot.Options = {
      width: chartEl.clientWidth,
      height: 320,
      scales: { x: { time: true } },
      series: [
        {},
        {
          label: 'HR (bpm)',
          stroke: '#f43f5e',
          width: 2,
          points: { show: false },
        },
      ],
      axes: [
        { stroke: '#94a3b8' },
        { stroke: '#94a3b8', label: 'bpm' },
      ],
    };
    chart = new uPlot(opts, data, chartEl);
  }

  function onResize() {
    if (chart && chartEl) chart.setSize({ width: chartEl.clientWidth, height: 320 });
  }

  onMount(() => {
    load();
    window.addEventListener('resize', onResize);
  });

  onDestroy(() => {
    if (chart) chart.destroy();
    if (typeof window !== 'undefined') window.removeEventListener('resize', onResize);
  });
</script>

<div class="space-y-4">
  <div class="flex items-center justify-between">
    <h1 class="text-2xl font-bold">Heart Rate — last 24h</h1>
    <button class="btn btn-sm" on:click={load} disabled={loading}>
      {loading ? '…' : 'Refresh'}
    </button>
  </div>

  {#if error}
    <div class="alert alert-error"><span>{error}</span></div>
  {/if}

  <div class="card bg-base-200">
    <div class="card-body">
      <div bind:this={chartEl} class="w-full"></div>
      <div class="text-xs opacity-60 mt-2">{samples.length} samples</div>
    </div>
  </div>
</div>

``n

## dashboard/src/routes/workouts/+page.svelte

```html
<script lang="ts">
  import { onMount } from 'svelte';
  import { fetchWorkouts, type Workout } from '$lib/api';

  let workouts: Workout[] = [];
  let error: string | null = null;
  let loading = true;

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await fetchWorkouts();
      workouts = res.workouts;
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  function durationMin(w: Workout): string {
    if (!w.end_ts) return '—';
    return Math.round((w.end_ts - w.start_ts) / 60000) + ' min';
  }

  function when(ts: number): string {
    return new Date(ts).toLocaleString();
  }

  onMount(load);
</script>

<div class="space-y-4">
  <div class="flex items-center justify-between">
    <h1 class="text-2xl font-bold">Workouts — last 7 days</h1>
    <button class="btn btn-sm" on:click={load} disabled={loading}>
      {loading ? '…' : 'Refresh'}
    </button>
  </div>

  {#if error}
    <div class="alert alert-error"><span>{error}</span></div>
  {/if}

  {#if workouts.length === 0 && !loading}
    <div class="card bg-base-200"><div class="card-body text-center opacity-70">No workouts yet.</div></div>
  {/if}

  <div class="space-y-3">
    {#each workouts as w}
      <div class="card bg-base-200">
        <div class="card-body">
          <div class="flex items-center justify-between">
            <div class="card-title">{w.kind}</div>
            <div class="badge badge-primary">{durationMin(w)}</div>
          </div>
          <div class="text-sm opacity-70">{when(w.start_ts)}</div>
          <div class="stats stats-horizontal bg-base-300 mt-2">
            <div class="stat py-2 px-3">
              <div class="stat-title text-xs">Distance</div>
              <div class="stat-value text-base">{w.distance_m ? (w.distance_m / 1000).toFixed(2) + ' km' : '—'}</div>
            </div>
            <div class="stat py-2 px-3">
              <div class="stat-title text-xs">Kcal</div>
              <div class="stat-value text-base">{w.kcal?.toFixed(0) ?? '—'}</div>
            </div>
            <div class="stat py-2 px-3">
              <div class="stat-title text-xs">Avg HR</div>
              <div class="stat-value text-base">{w.avg_hr?.toFixed(0) ?? '—'}</div>
            </div>
          </div>
        </div>
      </div>
    {/each}
  </div>
</div>

``n

## dashboard/static/manifest.webmanifest

```webmanifest
{
  "name": "Health Tracker",
  "short_name": "Health",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "orientation": "portrait",
  "background_color": "#0f172a",
  "theme_color": "#0f172a",
  "icons": [
    { "src": "/icon-180.png", "sizes": "180x180", "type": "image/png" },
    { "src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable" }
  ]
}

``n

## dashboard/svelte.config.js

```js
import adapter from '@sveltejs/adapter-cloudflare';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter(),
  },
};

export default config;

``n

## dashboard/tailwind.config.js

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{html,js,svelte,ts}'],
  theme: { extend: {} },
  plugins: [require('daisyui')],
  daisyui: {
    themes: ['night', 'winter'],
    darkTheme: 'night',
  },
};

``n

## dashboard/tsconfig.json

```json
{
  "extends": "./.svelte-kit/tsconfig.json",
  "compilerOptions": {
    "allowJs": true,
    "checkJs": true,
    "esModuleInterop": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "sourceMap": true,
    "strict": true,
    "moduleResolution": "bundler"
  }
}

``n

## dashboard/vite.config.ts

```ts
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
  },
});

``n

## dashboard/wrangler.toml

```toml
name = "health-dashboard"
pages_build_output_dir = ".svelte-kit/cloudflare"
compatibility_date = "2025-06-01"
compatibility_flags = ["nodejs_compat"]

``n

## DEPLOY.md

```md
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

``n

## README.md

```md
# Health Tracker

Personal-use pipeline: **Galaxy Watch 4 → local backend → web dashboard viewable on iPhone**.

Everything runs locally on your Windows laptop first. Cloudflare deploy is a later step.

## Layout

```
health-tracker/
├── backend/       Cloudflare Workers + D1 (runs locally via wrangler dev)
├── dashboard/     SvelteKit + Tailwind + DaisyUI + uPlot (runs via vite dev)
└── watch-app/     Wear OS 3+ Kotlin app (open in Android Studio)
```

## Currently running

- **Backend**: `http://192.168.1.16:8787` — Worker on port 8787, D1 with seeded fake data
- **Dashboard**: `http://192.168.1.16:5173` — SvelteKit dev server

Both are bound to `0.0.0.0`, so the watch, emulator, and iPhone can reach them from the same Wi-Fi.

## Endpoints (backend)

- `GET /` — service ping
- `GET /health` — DB row count
- `POST /v1/ingest` — auth-gated; body: `{device_id, samples:[{metric,ts,value,unit}], workouts:[], sleep:[]}`
- `GET /v1/samples?metric=heart_rate&from=<ms>&to=<ms>&limit=5000`
- `GET /v1/workouts?from=<ms>&to=<ms>`
- `GET /v1/summary?date=YYYY-MM-DD`

Dev auth token: `dev-token-change-me-before-deploy` (in both `backend/wrangler.toml` and `watch-app/.../Config.kt`).

``n

## watch-app/app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.healthtracker.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.healthtracker.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Compose for Wear OS
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear OS
    implementation("androidx.wear:wear:1.3.0")

    // Health Services — the whole point of this project
    implementation("androidx.health:health-services-client:1.1.0-alpha03")

    // Background scheduling
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Local buffer DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // (For Kotlin symbol processing, you may prefer KSP; using annotationProcessor keeps setup simple.)

    // Secure storage of the API token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
}

``n

## watch-app/app/proguard-rules.pro

```text
# Debug/personal app - no obfuscation needed

``n

## watch-app/app/src/main/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />

    <!-- Health sensor permissions -->
    <uses-permission android:name="android.permission.BODY_SENSORS" />
    <uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />

    <!-- Networking -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

    <!-- Background work -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:icon="@android:drawable/sym_def_app_icon"
        android:roundIcon="@android:drawable/sym_def_app_icon"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@android:style/Theme.DeviceDefault">

        <!-- Standalone (does not require phone companion) -->
        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <uses-library
            android:name="com.google.android.wearable"
            android:required="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>

``n

## watch-app/app/src/main/java/dev/healthtracker/watch/Config.kt

```kotlin
package dev.healthtracker.watch

/**
 * Personal-use config. Adjust for your setup.
 *
 * There are two profiles:
 *   USE_PROD = false → talk to the wrangler dev server on your laptop LAN.
 *   USE_PROD = true  → talk to the deployed Cloudflare Worker over HTTPS.
 *
 * After you deploy to Cloudflare, fill in PROD_BACKEND_URL + PROD_WATCH_TOKEN,
 * flip USE_PROD to true, hit Run in Android Studio to reinstall.
 */
object Config {

    // ------- Toggle -------
    private const val USE_PROD: Boolean = true

    // ------- Dev (LAN) -------
    private const val DEV_BACKEND_URL: String = "http://192.168.201.113:8787"
    private const val DEV_WATCH_TOKEN: String = "dev-token-change-me-before-deploy"

    // ------- Prod (Cloudflare) -------
    private const val PROD_BACKEND_URL: String = "https://health-api.siddeshwar.com"
    private const val PROD_WATCH_TOKEN: String = "ab9914c8240fce514ac7128bdcf2052f90b164b17c615d94a4da4c6219bd38be"

    // ------- Resolved -------
    val BACKEND_URL: String get() = if (USE_PROD) PROD_BACKEND_URL else DEV_BACKEND_URL
    val WATCH_TOKEN: String get() = if (USE_PROD) PROD_WATCH_TOKEN else DEV_WATCH_TOKEN

    // Stable identifier for this watch.
    const val DEVICE_ID: String = "galaxy-watch-4"

    // How often the background worker attempts to drain the buffer.
    const val UPLOAD_INTERVAL_MINUTES: Long = 15
}

``n

## watch-app/app/src/main/java/dev/healthtracker/watch/HealthCollector.kt

```kotlin
package dev.healthtracker.watch

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.guava.await
import java.time.Instant

/**
 * Wires up the PassiveMonitoringClient to receive health samples in the background.
 *
 * Samples are buffered into SampleBuffer. UploadWorker drains that buffer and POSTs to
 * the backend on its own schedule.
 */
object HealthCollector {

    private const val TAG = "HealthCollector"

    private val dataTypes = setOf(
        DataType.HEART_RATE_BPM,
        DataType.STEPS_DAILY,
        DataType.CALORIES_DAILY,
        DataType.DISTANCE_DAILY,
    )

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            // Use wall-clock receive time. Health Services stamps samples using SystemClock
            // elapsed-nanos which requires an accurate "boot instant" to translate — the
            // emulator often gets that wrong. For a personal tracker, receive-time is fine
            // (samples arrive within a second of being taken anyway).
            val now = System.currentTimeMillis()
            val samples = mutableListOf<BufferedSample>()

            for (dp in dataPoints.getData(DataType.HEART_RATE_BPM)) {
                samples += BufferedSample("heart_rate", now, dp.value, "bpm")
            }
            for (dp in dataPoints.getData(DataType.STEPS_DAILY)) {
                samples += BufferedSample("steps_daily", now, dp.value.toDouble(), "count")
            }
            for (dp in dataPoints.getData(DataType.CALORIES_DAILY)) {
                samples += BufferedSample("calories_daily", now, dp.value, "kcal")
            }
            for (dp in dataPoints.getData(DataType.DISTANCE_DAILY)) {
                samples += BufferedSample("distance_daily", now, dp.value, "m")
            }

            if (samples.isEmpty()) return
            Log.d(TAG, "buffering ${samples.size} samples")
            SampleBuffer.enqueueAll(samples)
        }
    }

    /**
     * Health Services SampleDataPoint exposes its timestamp as an Instant, but the getter
     * requires a "boot instant" reference to translate from the SystemClock elapsed-nanos
     * origin. Passing Instant.now() is close enough for our sub-second-not-required use case.
     */
    private fun SampleDataPoint<Double>.timeInEpochMillis(): Long {
        return try {
            this.getTimeInstant(Instant.now()).toEpochMilli()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun <T : Number> IntervalDataPoint<T>.endMillis(): Long {
        return try {
            this.getEndInstant(Instant.now()).toEpochMilli()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }

    suspend fun registerPassive(context: Context) {
        val client: PassiveMonitoringClient = HealthServices.getClient(context).passiveMonitoringClient
        val caps = client.getCapabilitiesAsync().await()
        val supported = dataTypes.filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()
        Log.i(TAG, "registering passive listener for: $supported")
        if (supported.isEmpty()) {
            Log.w(TAG, "no supported passive data types on this device")
            return
        }
        val config = PassiveListenerConfig.builder()
            .setDataTypes(supported)
            .build()
        client.setPassiveListenerCallback(config, callback)
    }
}

``n

## watch-app/app/src/main/java/dev/healthtracker/watch/MainActivity.kt

```kotlin
package dev.healthtracker.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val requestBodySensors =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "BODY_SENSORS granted" else "BODY_SENSORS denied"
        }

    private val requestBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "Background granted" else "Background denied"
        }

    private val requestActivity =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "ACTIVITY granted" else "ACTIVITY denied"
        }

    private var statusUpdate: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule the uploader once. WorkManager de-dupes across launches.
        UploadWorker.schedule(applicationContext)

        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("ready") }
                var lastUpload by remember { mutableStateOf("—") }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("HealthTracker", color = Color.White)
                        Text(status, color = Color.LightGray)
                        Text("Last upload: $lastUpload", color = Color.LightGray)

                        Button(onClick = {
                            requestBodySensors.launch(Manifest.permission.BODY_SENSORS)
                            status = "asking BODY_SENSORS"
                        }) { Text("1. Sensors") }

                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestBackground.launch("android.permission.BODY_SENSORS_BACKGROUND")
                                status = "asking BODY_SENSORS_BACKGROUND"
                            } else {
                                status = "background not needed on this OS"
                            }
                        }) { Text("2. Background") }

                        Button(onClick = {
                            requestActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            status = "asking ACTIVITY_RECOGNITION"
                        }) { Text("3. Activity") }

                        Button(onClick = {
                            lifecycleScope.launch {
                                status = "starting passive listener"
                                try {
                                    HealthCollector.registerPassive(applicationContext)
                                    status = "listener ON"
                                } catch (e: Exception) {
                                    status = "err: ${e.message}"
                                }
                            }
                        }) { Text("Start collecting") }

                        Button(onClick = {
                            lifecycleScope.launch {
                                status = "uploading now…"
                                val ok = UploadWorker.runNow(applicationContext)
                                status = if (ok) "upload OK" else "upload FAIL (see logcat)"
                                lastUpload = System.currentTimeMillis().toString()
                            }
                        }) { Text("Sync now") }
                    }
                }
            }
        }
    }
}

``n

## watch-app/app/src/main/java/dev/healthtracker/watch/SampleBuffer.kt

```kotlin
package dev.healthtracker.watch

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lightweight in-memory FIFO buffer for pending samples.
 *
 * Room DB would be more durable across process death, but for a personal-use MVP the
 * uploader runs frequently enough that in-memory + WorkManager retries are acceptable.
 * Upgrade path: swap this out for a Room DAO with the same enqueueAll / drainAll surface.
 */
data class BufferedSample(
    val metric: String,
    val ts: Long,
    val value: Double,
    val unit: String,
)

object SampleBuffer {
    private val queue = ConcurrentLinkedQueue<BufferedSample>()

    fun enqueue(s: BufferedSample) { queue.add(s) }
    fun enqueueAll(list: List<BufferedSample>) { queue.addAll(list) }
    fun size(): Int = queue.size

    /** Removes and returns all currently queued samples. */
    fun drainAll(): List<BufferedSample> {
        val out = mutableListOf<BufferedSample>()
        while (true) {
            val s = queue.poll() ?: break
            out.add(s)
        }
        return out
    }
}

``n

## watch-app/app/src/main/java/dev/healthtracker/watch/UploadWorker.kt

```kotlin
package dev.healthtracker.watch

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batch = SampleBuffer.drainAll()
        if (batch.isEmpty()) {
            Log.d(TAG, "no samples to upload")
            return@withContext Result.success()
        }

        val body = JSONObject().apply {
            put("device_id", Config.DEVICE_ID)
            put("samples", JSONArray().apply {
                batch.forEach { s ->
                    put(JSONObject().apply {
                        put("metric", s.metric)
                        put("ts", s.ts)
                        put("value", s.value)
                        put("unit", s.unit)
                    })
                }
            })
        }

        val req = Request.Builder()
            .url("${Config.BACKEND_URL}/v1/ingest")
            .addHeader("Authorization", "Bearer ${Config.WATCH_TOKEN}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "uploaded ${batch.size} samples: HTTP ${resp.code}")
                    Result.success()
                } else {
                    Log.w(TAG, "upload rejected: HTTP ${resp.code}")
                    // Re-queue for next attempt so we don't lose data.
                    SampleBuffer.enqueueAll(batch)
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            SampleBuffer.enqueueAll(batch)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val NAME = "health-upload"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<UploadWorker>(
                Config.UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * Fires a one-shot upload immediately. Called from the "Sync now" UI button.
         * Returns true if WorkManager accepted the request (not upload success).
         */
        suspend fun runNow(context: Context): Boolean {
            return try {
                val req = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueue(req)
                true
            } catch (e: Exception) {
                Log.e(TAG, "runNow failed", e)
                false
            }
        }
    }
}

``n

## watch-app/app/src/main/res/values/strings.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">HealthTracker</string>
</resources>

``n

## watch-app/app/src/main/res/xml/network_security_config.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Allow cleartext (HTTP) traffic to the developer laptop's LAN IP while testing locally.
  For production over HTTPS, this file is irrelevant. Edit the domains list if your LAN IP changes.
-->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.201.113</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>

``n

## watch-app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

``n

## watch-app/gradle.properties

```ini
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true

``n

## watch-app/gradle/wrapper/gradle-wrapper.properties

```ini
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

``n

## watch-app/gradlew

```
#!/bin/sh

#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   Gradle start up script for POSIX generated by Gradle.
#
#   Important for running:
#
#   (1) You need a POSIX-compliant shell to run this script. If your /bin/sh is
#       noncompliant, but you have some other compliant shell such as ksh or
#       bash, then to run this script, type that shell name before the whole
#       command line, like:
#
#           ksh Gradle
#
#       Busybox and similar reduced shells will NOT work, because this script
#       requires all of these POSIX shell features:
#         * functions;
#         * expansions «$var», «${var}», «${var:-default}», «${var+SET}»,
#           «${var#prefix}», «${var%suffix}», and «$( cmd )»;
#         * compound commands having a testable exit status, especially «case»;
#         * various built-in commands including «command», «set», and «ulimit».
#
#   Important for patching:
#
#   (2) This script targets any POSIX shell, so it avoids extensions provided
#       by Bash, Ksh, etc; in particular arrays are avoided.
#
#       The "traditional" practice of packing multiple parameters into a
#       space-separated string is a well documented source of bugs and security
#       problems, so this is (mostly) avoided, by progressively accumulating
#       options in "$@", and eventually passing that to Java.
#
#       Where the inherited environment variables (DEFAULT_JVM_OPTS, JAVA_OPTS,
#       and GRADLE_OPTS) rely on word-splitting, this is performed explicitly;
#       see the in-line comments for details.
#
#       There are tweaks for specific operating systems such as AIX, CygWin,
#       Darwin, MinGW, and NonStop.
#
#   (3) This script is generated from the Groovy template
#       https://github.com/gradle/gradle/blob/HEAD/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/unixStartScript.txt
#       within the Gradle project.
#
#       You can find Gradle at https://github.com/gradle/gradle/.
#
##############################################################################

# Attempt to set APP_HOME

# Resolve links: $0 may be a link
app_path=$0

# Need this for daisy-chained symlinks.
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# This is normally unused
# shellcheck disable=SC2034
APP_BASE_NAME=${0##*/}
# Discard cd standard output in case $CDPATH is set (https://github.com/gradle/gradle/issues/25036)
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac



# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# Increase the maximum file descriptors if we can.
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # In POSIX sh, ulimit -H is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # In POSIX sh, ulimit -n is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# Collect all arguments for the java command, stacking in reverse order:
#   * args from the command line
#   * the main class name
#   * -classpath
#   * -D...appname settings
#   * --module-path (only if needed)
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and GRADLE_OPTS environment variables.

# For Cygwin or MSYS, switch paths to Windows format before running java
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # don't mess with options #(
              /?*)  t=${arg#/} t=/${t%%/*}              # looks like a POSIX filepath
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # Roll the args list around exactly as many times as the number of
        # args, so each arg winds up back in the position where it started, but
        # possibly modified.
        #
        # NB: a `for` loop captures its iteration list before it begins, so
        # changing the positional parameters here affects neither the number of
        # iterations, nor the values presented in `arg`.
        shift                   # remove old arg
        set -- "$@" "$arg"      # push replacement arg
    done
fi


# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Collect all arguments for the java command:
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and optsEnvironmentVar are not allowed to contain shell fragments,
#     and any embedded shellness will be escaped.
#   * For example: A user cannot expect ${Hostname} to be expanded, as it is an environment variable and will be
#     treated as '${Hostname}' itself on the command line.

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        "$@"

# Stop when "xargs" is not available.
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# Use "xargs" to parse quoted args.
#
# With -n1 it outputs one arg per line, with the quotes and backslashes removed.
#
# In Bash we could simply go:
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# but POSIX shell has neither arrays nor command substitution, so instead we
# post-process each arg (as a line of input to sed) to backslash-escape any
# character that might be a shell metacharacter, then use eval to reverse
# that process (while maintaining the separation between arguments), and wrap
# the whole thing up as a single "set" statement.
#
# This will of course break if any of these variables contains a newline or
# an unmatched quote.
#

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"

``n

## watch-app/gradlew.bat

```bat
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line



@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega

``n

## watch-app/local.properties

```ini
## This file must *NOT* be checked into Version Control Systems,
# as it contains information specific to your local configuration.
#
# Location of the SDK. This is only used by Gradle.
# For customization when using a Version Control System, please read the
# header note.
#Thu Jul 02 22:06:51 IST 2026
sdk.dir=C\:\\Users\\user\\AppData\\Local\\Android\\Sdk

``n

## watch-app/README.md

```md
# HealthTracker Wear OS App

Custom Wear OS 3+ app for Samsung Galaxy Watch 4 that collects health data via
`androidx.health.services` and POSTs batched samples to the local dev backend at
`http://192.168.1.16:8787`.

## Open in Android Studio

1. Launch Android Studio.
2. **File → Open** → select this `watch-app` folder → OK.
3. When prompted, let Gradle sync and download the SDK components / build tools.
   First sync will take 5–15 minutes depending on connection.
4. Install a **Wear OS 4** SDK image + emulator via **Tools → SDK Manager → SDK Platforms**
   (pick "Android 14 (API 34)" → "Wear OS Small Round" system image).
5. Create a Wear OS AVD via **Tools → Device Manager → Create Device → Wear OS**.

## Run on the emulator

1. Boot the Wear OS AVD.
2. Press the green Run button.
3. Tap "1. Sensors", "2. Background", "3. Activity" one at a time to grant permissions.
4. Tap "Start collecting".
5. Open the Health Services sensor panel (heart icon on the emulator toolbar) to inject
   synthetic HR/steps.
6. Tap "Sync now" to force upload; check the backend logs and `curl http://localhost:8787/v1/samples`.

## Sideload to real Galaxy Watch 4

Assuming your laptop and watch are on the same Wi-Fi (or laptop hotspot):

```
# On watch: Settings > About watch > Software info > tap Software version x7
# Then: Settings > Developer options > ADB debugging + Debug over Wi-Fi
# Note pairing IP:PORT and the 6-digit code

adb pair 192.168.x.x:PAIRPORT
# enter 6-digit code
adb connect 192.168.x.x:CONNPORT
adb devices     # confirm watch shows

./gradlew installDebug   # or hit Run in Android Studio
```

## Configuration

Edit `app/src/main/java/dev/healthtracker/watch/Config.kt`:
- `BACKEND_URL` — your laptop LAN IP + `:8787` for dev, HTTPS URL after deploy.
- `WATCH_TOKEN` — must match the backend's `WATCH_TOKEN` var/secret.
- `DEVICE_ID` — arbitrary string identifying this watch.

## Cleartext HTTP

`res/xml/network_security_config.xml` allows plain HTTP to `192.168.1.16`, `10.0.2.2`
(emulator loopback to host), and `localhost` only. All other domains require HTTPS.
Update the domains list if your LAN IP changes.

``n

## watch-app/settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HealthTrackerWatch"
include(":app")

``n
