CREATE TABLE message_ingress_events (
  id VARCHAR(36) PRIMARY KEY,
  idempotency_key VARCHAR(128) UNIQUE,
  status VARCHAR(32) NOT NULL,
  payload_json TEXT,
  normalized_message_json TEXT,
  error_code VARCHAR(128),
  error_message TEXT,
  attempt_count INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_message_ingress_events_status_created
    ON message_ingress_events (status, created_at);

CREATE INDEX idx_message_ingress_events_idempotency
    ON message_ingress_events (idempotency_key);
