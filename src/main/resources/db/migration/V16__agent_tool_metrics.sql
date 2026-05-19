CREATE TABLE agent_tool_metrics (
  id VARCHAR(36) PRIMARY KEY,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  transport VARCHAR(64) NOT NULL,
  message_guid VARCHAR(255),
  chat_guid_hash VARCHAR(128),
  user_key_hash VARCHAR(128) NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  tool_category VARCHAR(64) NOT NULL,
  success BOOLEAN NOT NULL,
  failure_type VARCHAR(64),
  duration_ms BIGINT NOT NULL,
  model_key VARCHAR(128) NOT NULL,
  model_label VARCHAR(255) NOT NULL,
  responses_model VARCHAR(255) NOT NULL,
  plan VARCHAR(64) NOT NULL,
  is_premium BOOLEAN NOT NULL,
  account_type VARCHAR(64) NOT NULL,
  workflow_mode VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_tool_metrics_occurred
    ON agent_tool_metrics (occurred_at);

CREATE INDEX idx_agent_tool_metrics_tool_occurred
    ON agent_tool_metrics (tool_name, occurred_at);

CREATE INDEX idx_agent_tool_metrics_user_occurred
    ON agent_tool_metrics (user_key_hash, occurred_at);

CREATE INDEX idx_agent_tool_metrics_success_occurred
    ON agent_tool_metrics (success, occurred_at);

CREATE INDEX idx_agent_tool_metrics_account_type_occurred
    ON agent_tool_metrics (account_type, occurred_at);
