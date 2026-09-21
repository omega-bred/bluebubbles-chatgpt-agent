-- Provider cutover intentionally starts empty. No existing memories are imported.
DROP TABLE conversation_memory_projections;
DROP TABLE canonical_memory_records;
UPDATE conversation_memory_artifacts SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP;
DELETE FROM conversation_memory_work;
UPDATE agent_conversations SET memory_enabled_at = CURRENT_TIMESTAMP
 WHERE memory_enabled_at IS NOT NULL;

CREATE TABLE hindsight_memory_banks (
  bank_id VARCHAR(180) PRIMARY KEY,
  account_id VARCHAR(36),
  conversation_id VARCHAR(36),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_hindsight_banks_account ON hindsight_memory_banks(account_id);
CREATE TABLE hindsight_bank_audiences (
  bank_id VARCHAR(180) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  PRIMARY KEY(bank_id, account_id)
);
CREATE INDEX idx_hindsight_audience_account ON hindsight_bank_audiences(account_id);
CREATE TABLE hindsight_memory_documents (
  document_id VARCHAR(64) PRIMARY KEY,
  bank_id VARCHAR(180) NOT NULL,
  artifact_id VARCHAR(36),
  content_text TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  operation_id VARCHAR(36) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_hindsight_documents_bank ON hindsight_memory_documents(bank_id);
CREATE INDEX idx_hindsight_documents_artifact ON hindsight_memory_documents(artifact_id);
CREATE INDEX idx_hindsight_documents_due ON hindsight_memory_documents(state, available_at);
ALTER TABLE agent_conversations ADD COLUMN roster_verified_since TIMESTAMP WITH TIME ZONE;
