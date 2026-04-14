CREATE TABLE agent_workflow_callbacks (
  callback_id VARCHAR(128) PRIMARY KEY,
  signing_secret TEXT NOT NULL,
  chat_guid VARCHAR(255) NOT NULL,
  source_message_guid VARCHAR(255),
  thread_originator_guid VARCHAR(255),
  service VARCHAR(64),
  sender VARCHAR(255),
  is_group BOOLEAN NOT NULL,
  purpose TEXT NOT NULL,
  resume_instructions TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  received_webhook_id VARCHAR(255),
  received_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agent_workflow_callbacks_status_expires
    ON agent_workflow_callbacks (status, expires_at);

CREATE INDEX idx_agent_workflow_callbacks_chat
    ON agent_workflow_callbacks (chat_guid);
