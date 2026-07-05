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
