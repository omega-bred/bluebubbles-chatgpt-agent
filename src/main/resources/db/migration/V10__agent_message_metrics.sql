CREATE TABLE agent_message_metrics (
  id VARCHAR(36) PRIMARY KEY,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  transport VARCHAR(64) NOT NULL,
  message_guid VARCHAR(255),
  chat_guid_hash VARCHAR(128),
  user_key_hash VARCHAR(128) NOT NULL,
  model_key VARCHAR(128) NOT NULL,
  model_label VARCHAR(255) NOT NULL,
  responses_model VARCHAR(255) NOT NULL,
  plan VARCHAR(64) NOT NULL,
  is_premium BOOLEAN NOT NULL,
  workflow_mode VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_message_metrics_occurred
    ON agent_message_metrics (occurred_at);

CREATE INDEX idx_agent_message_metrics_model_occurred
    ON agent_message_metrics (model_key, occurred_at);

CREATE INDEX idx_agent_message_metrics_user_occurred
    ON agent_message_metrics (user_key_hash, occurred_at);
