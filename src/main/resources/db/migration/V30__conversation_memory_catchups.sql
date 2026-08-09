CREATE TABLE conversation_daily_digests (
  digest_id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  period_start TIMESTAMP WITH TIME ZONE NOT NULL,
  period_end TIMESTAMP WITH TIME ZONE NOT NULL,
  summary_text TEXT NOT NULL,
  item_payload TEXT NOT NULL,
  corpus_hash VARCHAR(64) NOT NULL,
  coverage_through TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (conversation_id, period_start, period_end)
);

CREATE TABLE conversation_digest_work (
  conversation_id VARCHAR(36) NOT NULL,
  period_start TIMESTAMP WITH TIME ZONE NOT NULL,
  period_end TIMESTAMP WITH TIME ZONE NOT NULL,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (conversation_id, period_start, period_end)
);

CREATE TABLE group_catchup_preferences (
  account_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  proactive_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  timezone VARCHAR(64) NOT NULL,
  quiet_start VARCHAR(5) NOT NULL,
  quiet_end VARCHAR(5) NOT NULL,
  next_delivery_at TIMESTAMP WITH TIME ZONE,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (account_id, conversation_id)
);

CREATE TABLE group_catchup_deliveries (
  delivery_id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  direct_conversation_id VARCHAR(36) NOT NULL,
  digest_hash VARCHAR(64) NOT NULL,
  coverage_through TIMESTAMP WITH TIME ZONE NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  sent_at TIMESTAMP WITH TIME ZONE,
  UNIQUE (account_id, conversation_id, digest_hash)
);

CREATE INDEX idx_conversation_digest_work_due
  ON conversation_digest_work (available_at, claimed_until);
CREATE INDEX idx_conversation_daily_digests_range
  ON conversation_daily_digests (conversation_id, period_start, period_end);
CREATE INDEX idx_group_catchup_preferences_due
  ON group_catchup_preferences (proactive_enabled, next_delivery_at, claimed_until);
CREATE INDEX idx_group_catchup_deliveries_account
  ON group_catchup_deliveries (account_id, conversation_id, created_at);
