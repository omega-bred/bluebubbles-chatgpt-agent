CREATE TABLE coder_async_task_starts (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  account_base VARCHAR(512) NOT NULL,
  chat_guid VARCHAR(255) NOT NULL,
  message_guid VARCHAR(255),
  task_hash VARCHAR(128) NOT NULL,
  task TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  response_json TEXT,
  error_message TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_coder_async_task_starts_message
    ON coder_async_task_starts (chat_guid, message_guid);

CREATE INDEX idx_coder_async_task_starts_account_task
    ON coder_async_task_starts (account_base, task_hash);
