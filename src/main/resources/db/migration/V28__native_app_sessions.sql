CREATE TABLE native_app_sessions (
  token_hash VARCHAR(64) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  app_account_token VARCHAR(36) NOT NULL UNIQUE,
  start_token_hash VARCHAR(64) NOT NULL UNIQUE,
  start_token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_at TIMESTAMP WITH TIME ZONE,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_used_at TIMESTAMP WITH TIME ZONE,
  revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_native_app_sessions_account_id
  ON native_app_sessions (account_id);

CREATE INDEX idx_native_app_sessions_start_token_hash
  ON native_app_sessions (start_token_hash);
