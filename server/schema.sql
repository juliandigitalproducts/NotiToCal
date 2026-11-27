-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Julian Digital Products
-- NotiToCal server schema for MySQL 8+ / MariaDB 10.5+.
-- api.php also creates the table automatically on first use.
CREATE TABLE IF NOT EXISTS notitocal_items (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uid          VARCHAR(64)  NOT NULL UNIQUE,
  kind         VARCHAR(16)  NOT NULL,
  title        VARCHAR(255) NOT NULL,
  description  TEXT         NULL,
  start_at     VARCHAR(32)  NULL,
  end_at       VARCHAR(32)  NULL,
  all_day      TINYINT(1)   NOT NULL DEFAULT 0,
  due_at       VARCHAR(32)  NULL,
  location     VARCHAR(255) NULL,
  priority     TINYINT      NOT NULL DEFAULT 0,
  confidence   DOUBLE       NOT NULL DEFAULT 0,
  engine       VARCHAR(128) NULL,
  timezone     VARCHAR(64)  NULL,
  source_type  VARCHAR(32)  NULL,
  source_app   VARCHAR(128) NULL,
  source_pkg   VARCHAR(255) NULL,
  sender       VARCHAR(255) NULL,
  message      TEXT         NULL,
  done         TINYINT(1)   NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kind_created (kind, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
