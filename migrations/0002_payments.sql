PRAGMA foreign_keys = ON;

ALTER TABLE apps ADD COLUMN is_paid INTEGER NOT NULL DEFAULT 0;
ALTER TABLE apps ADD COLUMN price_pkr INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS payment_methods (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  label TEXT NOT NULL,
  account_title TEXT NOT NULL DEFAULT '',
  account_value TEXT NOT NULL DEFAULT '',
  instructions TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS purchases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id INTEGER NOT NULL,
  customer_key TEXT NOT NULL,
  customer_name TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  payment_method_id INTEGER,
  payment_method_label TEXT NOT NULL DEFAULT '',
  transaction_reference TEXT NOT NULL DEFAULT '',
  amount_pkr INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','refunded')),
  admin_note TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_at TEXT,
  FOREIGN KEY(app_id) REFERENCES apps(id) ON DELETE CASCADE,
  FOREIGN KEY(payment_method_id) REFERENCES payment_methods(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_purchases_customer_app ON purchases(customer_key, app_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_purchases_status ON purchases(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_methods_enabled ON payment_methods(enabled, sort_order, id);
