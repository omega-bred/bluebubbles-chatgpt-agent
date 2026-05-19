DROP INDEX IF EXISTS idx_agent_tool_metrics_account_type_occurred;

ALTER TABLE agent_message_metrics DROP COLUMN IF EXISTS plan;
ALTER TABLE agent_tool_metrics DROP COLUMN IF EXISTS plan;
ALTER TABLE agent_tool_metrics DROP COLUMN IF EXISTS account_type;
