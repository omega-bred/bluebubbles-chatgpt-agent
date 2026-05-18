CREATE TABLE app_rate_limit_usage (
  id VARCHAR(36) PRIMARY KEY,
  limit_key VARCHAR(128) NOT NULL,
  scope_type VARCHAR(64) NOT NULL,
  scope_key VARCHAR(255) NOT NULL,
  window_start TIMESTAMP WITH TIME ZONE NOT NULL,
  window_end TIMESTAMP WITH TIME ZONE NOT NULL,
  amount BIGINT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_app_rate_limit_usage_unique_window
  ON app_rate_limit_usage (limit_key, scope_type, scope_key, window_start);

CREATE INDEX idx_app_rate_limit_usage_limit_window
  ON app_rate_limit_usage (limit_key, scope_type, window_start);

CREATE INDEX idx_app_rate_limit_usage_scope
  ON app_rate_limit_usage (scope_type, scope_key);
