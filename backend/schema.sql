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
CREATE UNIQUE INDEX IF NOT EXISTS idx_samples_unique ON samples(device_id, metric, ts);

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_workouts_unique ON workouts(device_id, kind, start_ts);

CREATE TABLE IF NOT EXISTS sleep_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER,
  stages TEXT,
  meta TEXT
);

CREATE INDEX IF NOT EXISTS idx_sleep_start ON sleep_sessions(start_ts DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sleep_unique ON sleep_sessions(device_id, start_ts);

-- Seed a default device row so ingest works from the get-go
INSERT OR IGNORE INTO devices (id, model, owner, created_at)
VALUES ('galaxy-watch-4', 'Samsung Galaxy Watch 4', 'me', strftime('%s','now') * 1000);
