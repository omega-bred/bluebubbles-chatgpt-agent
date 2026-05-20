ALTER TABLE agent_accounts
  ADD COLUMN premium_entitlement_source VARCHAR(64) NOT NULL DEFAULT 'none';

ALTER TABLE agent_accounts
  ADD COLUMN premium_subscription_expires_at TIMESTAMP;

ALTER TABLE agent_accounts
  ADD COLUMN premium_entitlement_synced_at TIMESTAMP;

UPDATE agent_accounts
SET premium_entitlement_source = 'manual'
WHERE is_premium = TRUE;

CREATE TABLE payment_checkout_sessions (
  checkout_session_id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  plan_key VARCHAR(128) NOT NULL,
  provider_checkout_id VARCHAR(255),
  checkout_url TEXT,
  status VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP,
  provider_payload TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_payment_checkout_sessions_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE
);

CREATE INDEX idx_payment_checkout_sessions_account
  ON payment_checkout_sessions (account_id);

CREATE INDEX idx_payment_checkout_sessions_provider_checkout
  ON payment_checkout_sessions (provider, provider_checkout_id);

CREATE TABLE payment_subscriptions (
  subscription_id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  provider VARCHAR(64) NOT NULL,
  plan_key VARCHAR(128) NOT NULL,
  provider_subscription_id VARCHAR(255),
  provider_customer_id VARCHAR(255),
  provider_customer_selector VARCHAR(512),
  provider_offering_id VARCHAR(255),
  provider_plan_id VARCHAR(255),
  provider_status VARCHAR(128),
  status VARCHAR(64) NOT NULL,
  current_period_start TIMESTAMP,
  current_period_end TIMESTAMP,
  trial_end TIMESTAMP,
  grace_period_end TIMESTAMP,
  canceled_at TIMESTAMP,
  cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
  checkout_session_id VARCHAR(36),
  management_url TEXT,
  raw_payload TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  last_synced_at TIMESTAMP,
  CONSTRAINT fk_payment_subscriptions_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE CASCADE,
  CONSTRAINT fk_payment_subscriptions_checkout
    FOREIGN KEY (checkout_session_id)
    REFERENCES payment_checkout_sessions (checkout_session_id)
    ON DELETE SET NULL
);

CREATE INDEX idx_payment_subscriptions_account
  ON payment_subscriptions (account_id);

CREATE INDEX idx_payment_subscriptions_provider_subscription
  ON payment_subscriptions (provider, provider_subscription_id);

CREATE INDEX idx_payment_subscriptions_provider_customer
  ON payment_subscriptions (provider, provider_customer_selector);

CREATE INDEX idx_payment_subscriptions_status
  ON payment_subscriptions (status);

CREATE TABLE payment_provider_events (
  event_id VARCHAR(36) PRIMARY KEY,
  provider VARCHAR(64) NOT NULL,
  provider_event_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  account_id VARCHAR(36),
  checkout_session_id VARCHAR(36),
  subscription_id VARCHAR(36),
  provider_subscription_id VARCHAR(255),
  status VARCHAR(64) NOT NULL,
  raw_payload TEXT NOT NULL,
  error_message TEXT,
  received_at TIMESTAMP NOT NULL,
  processed_at TIMESTAMP,
  CONSTRAINT fk_payment_provider_events_account
    FOREIGN KEY (account_id)
    REFERENCES agent_accounts (account_id)
    ON DELETE SET NULL,
  CONSTRAINT fk_payment_provider_events_checkout
    FOREIGN KEY (checkout_session_id)
    REFERENCES payment_checkout_sessions (checkout_session_id)
    ON DELETE SET NULL,
  CONSTRAINT fk_payment_provider_events_subscription
    FOREIGN KEY (subscription_id)
    REFERENCES payment_subscriptions (subscription_id)
    ON DELETE SET NULL,
  CONSTRAINT uq_payment_provider_events_provider_event
    UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payment_provider_events_account
  ON payment_provider_events (account_id);

CREATE INDEX idx_payment_provider_events_subscription
  ON payment_provider_events (subscription_id);
