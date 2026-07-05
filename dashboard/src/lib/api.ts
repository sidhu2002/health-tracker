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
  distance: number;
  floors: number;
  elevation: number;
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
