ALTER TABLE agent_accounts
  ADD COLUMN model_verbosity VARCHAR(32) NOT NULL DEFAULT 'medium';
