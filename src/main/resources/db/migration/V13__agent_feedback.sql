CREATE TABLE agent_feedback (
  feedback_id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  feedback_text TEXT NOT NULL,
  category VARCHAR(64) NOT NULL DEFAULT 'general',
  transport VARCHAR(64) NOT NULL,
  sender VARCHAR(512),
  chat_guid VARCHAR(255),
  message_guid VARCHAR(255),
  read_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_agent_feedback_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_agent_feedback_submitted
  ON agent_feedback (submitted_at DESC);

CREATE INDEX idx_agent_feedback_read_submitted
  ON agent_feedback (read_at, submitted_at DESC);

CREATE INDEX idx_agent_feedback_account
  ON agent_feedback (account_id);
