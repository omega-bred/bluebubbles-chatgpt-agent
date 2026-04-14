CREATE TABLE agent_model_account_settings (
  account_base VARCHAR(512) PRIMARY KEY,
  is_premium BOOLEAN NOT NULL DEFAULT FALSE,
  selected_model VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_model_account_settings_premium
    ON agent_model_account_settings (is_premium);
