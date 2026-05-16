CREATE TABLE agent_account_identity_aliases (
  alias_key VARCHAR(768) PRIMARY KEY,
  account_base VARCHAR(512) NOT NULL,
  transport VARCHAR(64) NOT NULL,
  identifier VARCHAR(512) NOT NULL,
  identifier_type VARCHAR(32) NOT NULL,
  normalized_identifier VARCHAR(512) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agent_account_identity_aliases_account_base
    ON agent_account_identity_aliases (account_base);
