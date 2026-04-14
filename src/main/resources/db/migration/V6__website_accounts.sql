CREATE TABLE website_accounts (
  keycloak_subject VARCHAR(255) PRIMARY KEY,
  email VARCHAR(512),
  preferred_username VARCHAR(255),
  display_name VARCHAR(512),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE website_account_link_tokens (
  token_hash VARCHAR(128) PRIMARY KEY,
  account_base VARCHAR(512) NOT NULL,
  coder_account_base VARCHAR(512) NOT NULL,
  gcal_account_base VARCHAR(512) NOT NULL,
  chat_guid VARCHAR(255),
  sender VARCHAR(255),
  service VARCHAR(64),
  is_group BOOLEAN NOT NULL,
  source_message_guid VARCHAR(255),
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  redeemed_at TIMESTAMP WITH TIME ZONE,
  redeemed_account_subject VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_website_account_link_tokens_expires
    ON website_account_link_tokens (expires_at);

CREATE INDEX idx_website_account_link_tokens_redeemed_subject
    ON website_account_link_tokens (redeemed_account_subject);

CREATE TABLE website_account_sender_links (
  link_id VARCHAR(36) PRIMARY KEY,
  account_subject VARCHAR(255) NOT NULL,
  account_base VARCHAR(512) NOT NULL,
  coder_account_base VARCHAR(512) NOT NULL,
  gcal_account_base VARCHAR(512) NOT NULL,
  chat_guid VARCHAR(255),
  sender VARCHAR(255),
  service VARCHAR(64),
  is_group BOOLEAN NOT NULL,
  source_message_guid VARCHAR(255),
  linked_via_token_hash VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_website_sender_link_account
      FOREIGN KEY (account_subject)
      REFERENCES website_accounts (keycloak_subject)
      ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_website_account_sender_links_identity
    ON website_account_sender_links (account_subject, coder_account_base, gcal_account_base);

CREATE INDEX idx_website_account_sender_links_subject
    ON website_account_sender_links (account_subject);
