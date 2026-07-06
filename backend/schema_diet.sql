CREATE TABLE IF NOT EXISTS food_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  logged_at INTEGER NOT NULL,
  calories REAL NOT NULL,
  protein_g REAL,
  carbs_g REAL,
  fat_g REAL,
  micros TEXT,
  source TEXT,
  meta TEXT
);

CREATE INDEX IF NOT EXISTS idx_food_logs_time ON food_logs(logged_at DESC);

CREATE TABLE IF NOT EXISTS daily_goals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  target_date TEXT UNIQUE NOT NULL,
  target_calories REAL,
  target_protein_g REAL,
  target_carbs_g REAL,
  target_fat_g REAL
);