// Personal health tracker Worker.
// Receives batched samples from a Wear OS watch app and serves them to a dashboard.
//
// Local dev:
//   npx wrangler dev  ->  http://localhost:8787  and  http://<lan-ip>:8787
// Prod:
//   Set WATCH_TOKEN as a real secret and swap the D1 database_id in wrangler.toml.

import { json, authOk, CORS_HEADERS } from "./utils";
import { handleFoodLogs, handleGoals, handleAIFoodParse } from "./handlers/diet";

export interface Env {
  DB: D1Database;
  WATCH_TOKEN: string;
  GEMINI_API_KEY: string;
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
        "INSERT OR IGNORE INTO samples (device_id, metric, ts, value, unit, meta) VALUES (?, ?, ?, ?, ?, ?)"
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
        "INSERT OR IGNORE INTO workouts (device_id, kind, start_ts, end_ts, distance_m, kcal, avg_hr, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
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
        "INSERT OR IGNORE INTO sleep_sessions (device_id, start_ts, end_ts, stages, meta) VALUES (?, ?, ?, ?, ?)"
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

  const distance = await env.DB.prepare(
    "SELECT COALESCE(MAX(value), 0) as distance FROM samples WHERE metric IN ('distance_daily','distance') AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const floors = await env.DB.prepare(
    "SELECT COALESCE(MAX(value), 0) as floors FROM samples WHERE metric IN ('floors_daily','floors') AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const elevation = await env.DB.prepare(
    "SELECT COALESCE(MAX(value), 0) as elevation FROM samples WHERE metric IN ('elevation_gain_daily','elevation') AND ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  const workouts = await env.DB.prepare(
    "SELECT COUNT(*) as n FROM workouts WHERE start_ts BETWEEN ? AND ?"
  )
    .bind(from, to)
    .first();

  return json({
    date: day.toISOString().split("T")[0],
    heart_rate: {
      avg_hr: hr?.avg_hr ?? null,
      min_hr: hr?.min_hr ?? null,
      max_hr: hr?.max_hr ?? null,
      n: hr?.n ?? 0,
    },
    steps: steps?.steps ?? 0,
    calories: kcal?.kcal ?? 0,
    distance: distance?.distance ?? 0,
    floors: floors?.floors ?? 0,
    elevation: elevation?.elevation ?? 0,
    workouts: workouts?.n ?? 0,
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

    // Dietician & Nutrition endpoints
    if (url.pathname === "/v1/food-logs") {
      return handleFoodLogs(req, url, env);
    }
    if (url.pathname === "/v1/goals") {
      return handleGoals(req, url, env);
    }
    if (url.pathname === "/v1/ai/parse-food" && req.method === "POST") {
      return handleAIFoodParse(req, env);
    }

    return json({ error: "not_found", path: url.pathname }, 404);
  },
};
