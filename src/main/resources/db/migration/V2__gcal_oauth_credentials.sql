CREATE TABLE gcal_oauth_credentials (
  id VARCHAR(512) PRIMARY KEY,
  store_id VARCHAR(128) NOT NULL,
  account_key VARCHAR(255) NOT NULL,
  access_token TEXT,
  refresh_token TEXT,
  expiration_time_ms BIGINT
);

CREATE INDEX idx_gcal_oauth_credentials_store ON gcal_oauth_credentials (store_id);
CREATE INDEX idx_gcal_oauth_credentials_account ON gcal_oauth_credentials (account_key);
