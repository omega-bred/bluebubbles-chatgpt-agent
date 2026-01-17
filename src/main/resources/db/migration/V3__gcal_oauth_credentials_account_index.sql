ALTER TABLE gcal_oauth_credentials
    ADD COLUMN IF NOT EXISTS account_base VARCHAR(255);

ALTER TABLE gcal_oauth_credentials
    ADD COLUMN IF NOT EXISTS account_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_gcal_oauth_credentials_account_base
    ON gcal_oauth_credentials (account_base);

CREATE INDEX IF NOT EXISTS idx_gcal_oauth_credentials_account_id
    ON gcal_oauth_credentials (account_id);
