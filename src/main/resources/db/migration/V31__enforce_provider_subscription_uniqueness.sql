CREATE TABLE payment_subscription_duplicate_rows AS
SELECT subscription_id AS duplicate_subscription_id, canonical_subscription_id
FROM (
  SELECT
    subscription_id,
    FIRST_VALUE(subscription_id) OVER (
      PARTITION BY provider, provider_subscription_id
      ORDER BY updated_at DESC, subscription_id DESC
    ) AS canonical_subscription_id,
    ROW_NUMBER() OVER (
      PARTITION BY provider, provider_subscription_id
      ORDER BY updated_at DESC, subscription_id DESC
    ) AS duplicate_rank
  FROM payment_subscriptions
  WHERE provider_subscription_id IS NOT NULL
) ranked_subscriptions
WHERE duplicate_rank > 1;

UPDATE payment_provider_events
SET subscription_id = (
  SELECT canonical_subscription_id
  FROM payment_subscription_duplicate_rows
  WHERE duplicate_subscription_id = payment_provider_events.subscription_id
)
WHERE subscription_id IN (
  SELECT duplicate_subscription_id
  FROM payment_subscription_duplicate_rows
);

DELETE FROM payment_subscriptions
WHERE subscription_id IN (
  SELECT duplicate_subscription_id
  FROM payment_subscription_duplicate_rows
);

DROP TABLE payment_subscription_duplicate_rows;

DROP INDEX idx_payment_subscriptions_provider_subscription;

CREATE UNIQUE INDEX uq_payment_subscriptions_provider_subscription
  ON payment_subscriptions (provider, provider_subscription_id);
