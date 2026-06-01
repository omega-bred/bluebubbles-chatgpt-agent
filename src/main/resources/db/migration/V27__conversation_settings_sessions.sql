ALTER TABLE website_account_link_tokens
  ADD COLUMN purpose VARCHAR(64) NOT NULL DEFAULT 'account_link';

ALTER TABLE app_clip_sessions
  ADD COLUMN purpose VARCHAR(64) NOT NULL DEFAULT 'account_link';

ALTER TABLE app_clip_sessions
  ADD COLUMN chat_guid VARCHAR(255);

ALTER TABLE agent_accounts
  DROP COLUMN model_verbosity;
