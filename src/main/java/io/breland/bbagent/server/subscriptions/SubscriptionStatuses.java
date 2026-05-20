package io.breland.bbagent.server.subscriptions;

import java.util.Set;

final class SubscriptionStatuses {
  static final String CHECKOUT_CREATED = "created";
  static final String CHECKOUT_COMPLETED = "completed";
  static final String CHECKOUT_EXPIRED = "expired";
  static final String EVENT_RECEIVED = "received";
  static final String EVENT_PROCESSED = "processed";
  static final String EVENT_DUPLICATE = "duplicate";
  static final String EVENT_IGNORED = "ignored";
  static final String EVENT_FAILED = "failed";
  static final String SUBSCRIPTION_PENDING = "pending";
  static final String SUBSCRIPTION_ACTIVE = "active";
  static final String SUBSCRIPTION_TRIALING = "trialing";
  static final String SUBSCRIPTION_GRACE = "grace";
  static final String SUBSCRIPTION_PAST_DUE = "past_due";
  static final String SUBSCRIPTION_CANCELED = "canceled";
  static final String SUBSCRIPTION_EXPIRED = "expired";
  static final String SUBSCRIPTION_SUSPENDED = "suspended";
  static final String SUBSCRIPTION_UNKNOWN = "unknown";

  static final Set<String> PREMIUM_ACCESS_STATUSES =
      Set.of(SUBSCRIPTION_ACTIVE, SUBSCRIPTION_TRIALING, SUBSCRIPTION_GRACE);

  private SubscriptionStatuses() {}
}
