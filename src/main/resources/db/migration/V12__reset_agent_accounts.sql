DROP TABLE IF EXISTS website_account_sender_links;
DROP TABLE IF EXISTS website_account_link_tokens;
DROP TABLE IF EXISTS website_accounts;
DROP TABLE IF EXISTS agent_account_identity_aliases;
DROP TABLE IF EXISTS agent_model_account_settings;
DROP TABLE IF EXISTS coder_oauth_pending_authorizations;
DROP TABLE IF EXISTS coder_oauth_credentials;
DROP TABLE IF EXISTS coder_oauth_clients;
DROP TABLE IF EXISTS coder_async_task_starts;
DROP TABLE IF EXISTS gcal_oauth_credentials;
DROP TABLE IF EXISTS global_contact;

CREATE TABLE agent_accounts (
  account_id VARCHAR(36) PRIMARY KEY,
  website_subject VARCHAR(255),
  website_email VARCHAR(512),
  website_preferred_username VARCHAR(255),
  website_display_name VARCHAR(512),
  global_contact_name VARCHAR(512),
  is_premium BOOLEAN NOT NULL DEFAULT FALSE,
  selected_model VARCHAR(128),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_agent_accounts_website_subject
  ON agent_accounts (website_subject);

CREATE INDEX idx_agent_accounts_website_email
  ON agent_accounts (website_email);

CREATE TABLE agent_account_identities (
  identity_id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  identity_type VARCHAR(64) NOT NULL,
  identifier VARCHAR(512) NOT NULL,
  normalized_identifier VARCHAR(512) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_agent_account_identities_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_agent_account_identities_identity
  ON agent_account_identities (identity_type, normalized_identifier);

CREATE INDEX idx_agent_account_identities_account
  ON agent_account_identities (account_id);

CREATE TABLE website_account_link_tokens (
  token_hash VARCHAR(128) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  chat_guid VARCHAR(255),
  sender VARCHAR(255),
  service VARCHAR(64),
  is_group BOOLEAN NOT NULL,
  source_message_guid VARCHAR(255),
  expires_at TIMESTAMP NOT NULL,
  redeemed_at TIMESTAMP,
  redeemed_account_id VARCHAR(36),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_website_link_token_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE,
  CONSTRAINT fk_website_link_token_redeemed_account
    FOREIGN KEY (redeemed_account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE SET NULL
);

CREATE INDEX idx_website_account_link_tokens_expires
  ON website_account_link_tokens (expires_at);

CREATE INDEX idx_website_account_link_tokens_account
  ON website_account_link_tokens (account_id);

CREATE TABLE gcal_oauth_credentials (
  id VARCHAR(512) PRIMARY KEY,
  store_id VARCHAR(128) NOT NULL,
  account_key VARCHAR(512) NOT NULL,
  agent_account_id VARCHAR(36),
  google_account_id VARCHAR(255),
  access_token TEXT,
  refresh_token TEXT,
  expiration_time_ms BIGINT,
  CONSTRAINT fk_gcal_oauth_credentials_account
    FOREIGN KEY (agent_account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_gcal_oauth_credentials_store
  ON gcal_oauth_credentials (store_id);

CREATE INDEX idx_gcal_oauth_credentials_account_key
  ON gcal_oauth_credentials (account_key);

CREATE INDEX idx_gcal_oauth_credentials_agent_account
  ON gcal_oauth_credentials (agent_account_id);

CREATE TABLE coder_oauth_credentials (
  account_id VARCHAR(36) PRIMARY KEY,
  access_token TEXT,
  refresh_token TEXT,
  token_type VARCHAR(64),
  scopes TEXT,
  expires_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_coder_oauth_credentials_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE TABLE coder_oauth_pending_authorizations (
  pending_id VARCHAR(128) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  chat_guid VARCHAR(255),
  message_guid VARCHAR(255),
  code_verifier TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_coder_oauth_pending_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_coder_oauth_pending_account
  ON coder_oauth_pending_authorizations (account_id);

CREATE INDEX idx_coder_oauth_pending_expires
  ON coder_oauth_pending_authorizations (expires_at);

CREATE TABLE coder_async_task_starts (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  chat_guid VARCHAR(255) NOT NULL,
  message_guid VARCHAR(255),
  task_hash VARCHAR(128) NOT NULL,
  task TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  response_json TEXT,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_coder_async_task_starts_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_coder_async_task_starts_account_task
  ON coder_async_task_starts (account_id, task_hash);
