ALTER TABLE agent_accounts
  ADD COLUMN canary_account BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_accounts
  ADD COLUMN canary_label VARCHAR(128);

ALTER TABLE agent_accounts
  ADD COLUMN canary_last_seen_at TIMESTAMP;

CREATE INDEX idx_agent_accounts_canary_last_seen
  ON agent_accounts (canary_account, canary_last_seen_at);
