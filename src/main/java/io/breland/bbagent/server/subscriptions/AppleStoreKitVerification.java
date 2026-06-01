package io.breland.bbagent.server.subscriptions;

import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

public interface AppleStoreKitVerification {
  VerifiedTransaction verifyTransaction(
      String signedTransactionInfo, @Nullable String signedRenewalInfo);

  VerifiedNotification verifyNotification(String signedPayload);

  record VerifiedTransaction(
      String transactionId,
      String originalTransactionId,
      String productId,
      @Nullable UUID appAccountToken,
      @Nullable String environment,
      @Nullable Instant purchaseDate,
      @Nullable Instant expiresDate,
      @Nullable Instant revocationDate,
      @Nullable String type,
      String rawPayload) {}

  record VerifiedNotification(
      String notificationUuid,
      String notificationType,
      @Nullable String subtype,
      @Nullable VerifiedTransaction transaction,
      String rawPayload) {}
}
