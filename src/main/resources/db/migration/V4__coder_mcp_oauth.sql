CREATE TABLE coder_oauth_clients (
  issuer VARCHAR(512) PRIMARY KEY,
  client_id TEXT NOT NULL,
  client_secret TEXT,
  redirect_uri TEXT NOT NULL,
  token_endpoint_auth_method VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE coder_oauth_credentials (
  account_base VARCHAR(512) PRIMARY KEY,
  access_token TEXT,
  refresh_token TEXT,
  token_type VARCHAR(64),
  scopes TEXT,
  expires_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE coder_oauth_pending_authorizations (
  pending_id VARCHAR(128) PRIMARY KEY,
  account_base VARCHAR(512) NOT NULL,
  chat_guid VARCHAR(255),
  message_guid VARCHAR(255),
  code_verifier TEXT NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_coder_oauth_pending_account
    ON coder_oauth_pending_authorizations (account_base);

CREATE INDEX idx_coder_oauth_pending_expires
    ON coder_oauth_pending_authorizations (expires_at);
