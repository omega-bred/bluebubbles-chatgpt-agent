CREATE TABLE app_clip_sessions (
  token_hash VARCHAR(64) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  source_link_token_hash VARCHAR(64),
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  last_used_at TIMESTAMP,
  revoked_at TIMESTAMP,
  CONSTRAINT fk_app_clip_sessions_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE,
  CONSTRAINT fk_app_clip_sessions_link_token
    FOREIGN KEY (source_link_token_hash)
    REFERENCES website_account_link_tokens (token_hash)
    ON DELETE SET NULL
);

CREATE INDEX idx_app_clip_sessions_account
  ON app_clip_sessions (account_id);

CREATE INDEX idx_app_clip_sessions_expires
  ON app_clip_sessions (expires_at);
