CREATE TABLE agent_conversations (
  conversation_id VARCHAR(36) PRIMARY KEY,
  transport VARCHAR(32) NOT NULL,
  external_conversation_id VARCHAR(512) NOT NULL,
  is_group BOOLEAN NOT NULL,
  display_name VARCHAR(255),
  memory_enabled_at TIMESTAMP WITH TIME ZONE,
  memory_enabled_by_account_id VARCHAR(36),
  last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (transport, external_conversation_id)
);

CREATE TABLE agent_conversation_memberships (
  membership_id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  ended_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE agent_conversation_messages (
  message_guid VARCHAR(255) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  sender_account_id VARCHAR(36),
  message_text TEXT,
  content_hash VARCHAR(64) NOT NULL,
  source_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  from_agent BOOLEAN NOT NULL,
  system_message BOOLEAN NOT NULL,
  removed BOOLEAN NOT NULL DEFAULT FALSE,
  first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE conversation_memory_work (
  conversation_id VARCHAR(36) PRIMARY KEY,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE conversation_memory_checkpoints (
  conversation_id VARCHAR(36) PRIMARY KEY,
  last_processed_at TIMESTAMP WITH TIME ZONE,
  last_processed_message_guid VARCHAR(255),
  last_corpus_hash VARCHAR(64),
  last_reconciled_at TIMESTAMP WITH TIME ZONE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE conversation_memory_artifacts (
  artifact_id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  artifact_text TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  sensitivity VARCHAR(32) NOT NULL,
  confidence DOUBLE PRECISION NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE,
  superseded_by_artifact_id VARCHAR(36),
  content_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (conversation_id, content_hash, occurred_at)
);

CREATE TABLE conversation_memory_evidence (
  artifact_id VARCHAR(36) NOT NULL,
  message_guid VARCHAR(255) NOT NULL,
  PRIMARY KEY (artifact_id, message_guid)
);

CREATE TABLE conversation_memory_audiences (
  artifact_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (artifact_id, account_id)
);

CREATE TABLE conversation_memory_projections (
  artifact_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  mem0_memory_id VARCHAR(255),
  projection_hash VARCHAR(64) NOT NULL,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (artifact_id, account_id)
);

CREATE TABLE conversation_summary_segments (
  segment_id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  window_start TIMESTAMP WITH TIME ZONE NOT NULL,
  window_end TIMESTAMP WITH TIME ZONE NOT NULL,
  summary_text TEXT NOT NULL,
  item_payload TEXT NOT NULL,
  corpus_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (conversation_id, corpus_hash)
);

CREATE TABLE conversation_summary_audiences (
  summary_type VARCHAR(16) NOT NULL,
  summary_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (summary_type, summary_id, account_id)
);

CREATE TABLE canonical_memory_records (
  memory_record_id VARCHAR(36) PRIMARY KEY,
  scope_type VARCHAR(16) NOT NULL,
  scope_id VARCHAR(36) NOT NULL,
  mem0_memory_id VARCHAR(255) NOT NULL UNIQUE,
  content_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agent_conversation_messages_conversation_time
  ON agent_conversation_messages (conversation_id, source_timestamp, message_guid);
CREATE INDEX idx_agent_conversation_memberships_active
  ON agent_conversation_memberships (conversation_id, started_at, ended_at, account_id);
CREATE INDEX idx_conversation_memory_work_due
  ON conversation_memory_work (available_at, claimed_until);
CREATE INDEX idx_conversation_memory_artifacts_active
  ON conversation_memory_artifacts (conversation_id, status, occurred_at);
CREATE INDEX idx_conversation_memory_audiences_account
  ON conversation_memory_audiences (account_id, artifact_id);
CREATE INDEX idx_conversation_summary_audiences_account
  ON conversation_summary_audiences (account_id, summary_type, summary_id);
CREATE INDEX idx_canonical_memory_records_scope
  ON canonical_memory_records (scope_type, scope_id);
CREATE INDEX idx_conversation_memory_projections_due
  ON conversation_memory_projections (state, available_at, claimed_until);
