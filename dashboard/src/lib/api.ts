// Backend URL resolution:
//   - Vite dev (port 5173): talk to wrangler dev on same host, port 8787.
//   - Production build: use VITE_API_BASE env var baked in at build time
//     (set in Cloudflare Pages env vars or a local .env.production file).
//   - Fallback: same-origin (works if API is proxied by Pages Functions in future).

const VITE_API_BASE = (import.meta as { env: { VITE_API_BASE?: string } }).env.VITE_API_BASE || 'https://health-api.siddeshwar.com';

export const API_BASE: string = (() => {
  if (typeof window === 'undefined') return VITE_API_BASE;
  if (window.location.port === '5173') return `http://${window.location.hostname}:8787`;
  return VITE_API_BASE;
})();

function getAuthHeaders(extra: Record<string, string> = {}) {
  const headers: Record<string, string> = { ...extra };
  if (typeof localStorage !== 'undefined') {
    const token = localStorage.getItem('WATCH_TOKEN');
    if (token) headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

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

// --- DIETITIAN APIs ---

export interface FoodLog {
  id: number;
  name: string;
  logged_at: number;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  micros?: Record<string, number>;
  source: string;
  meta?: any;
}

export interface DailyGoals {
  target_date: string;
  target_calories: number | null;
  target_protein_g: number | null;
  target_carbs_g: number | null;
  target_fat_g: number | null;
}

export async function fetchFoodLogs(date?: string) {
  const url = new URL(`${API_BASE}/v1/food-logs`);
  if (date) url.searchParams.set('date', date);
  // GET requests are not auth-gated, but we can pass headers safely
  const res = await fetch(url, { headers: getAuthHeaders() });
  if (!res.ok) throw new Error(`food logs fetch failed: ${res.status}`);
  return (await res.json()) as { logs: FoodLog[] };
}

export async function createFoodLog(log: Partial<FoodLog>) {
  const res = await fetch(`${API_BASE}/v1/food-logs`, {
    method: 'POST',
    headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(log)
  });
  if (!res.ok) throw new Error(`failed to create food log: ${res.status}`);
  return await res.json();
}

export async function fetchGoals(date?: string) {
  const url = new URL(`${API_BASE}/v1/goals`);
  if (date) url.searchParams.set('date', date);
  const res = await fetch(url, { headers: getAuthHeaders() });
  if (!res.ok) throw new Error(`goals fetch failed: ${res.status}`);
  return (await res.json()) as { goals: DailyGoals | null };
}

export async function updateGoals(goals: Partial<DailyGoals>) {
  const res = await fetch(`${API_BASE}/v1/goals`, {
    method: 'POST',
    headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(goals)
  });
  if (!res.ok) throw new Error(`failed to update goals: ${res.status}`);
  return await res.json();
}

export async function parseFoodAI(text?: string, image_base64?: string) {
  const res = await fetch(`${API_BASE}/v1/ai/parse-food`, {
    method: 'POST',
    headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ text, image_base64 })
  });
  if (!res.ok) throw new Error(`AI parse failed: ${res.status}`);
  return await res.json();
}
