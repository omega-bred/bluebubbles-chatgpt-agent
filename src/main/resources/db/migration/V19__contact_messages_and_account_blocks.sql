ALTER TABLE agent_accounts
  ADD COLUMN processing_blocked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_accounts
  ADD COLUMN processing_blocked_reason TEXT;

ALTER TABLE agent_accounts
  ADD COLUMN processing_blocked_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE agent_accounts
  ADD COLUMN processing_blocked_by VARCHAR(255);

CREATE INDEX idx_agent_accounts_processing_blocked
  ON agent_accounts (processing_blocked, processing_blocked_at DESC);

CREATE TABLE website_contact_messages (
  message_id VARCHAR(36) PRIMARY KEY,
  submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  name VARCHAR(200) NOT NULL,
  email VARCHAR(320) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  message TEXT NOT NULL,
  status VARCHAR(64) NOT NULL,
  remote_address VARCHAR(255),
  user_agent VARCHAR(512),
  cap_verified BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_website_contact_messages_submitted
  ON website_contact_messages (submitted_at DESC);

CREATE INDEX idx_website_contact_messages_status_submitted
  ON website_contact_messages (status, submitted_at DESC);
