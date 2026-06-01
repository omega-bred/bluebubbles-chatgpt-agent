package io.breland.bbagent.server.subscriptions;

import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.apple.itunes.storekit.verification.VerificationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class SignedDataAppleStoreKitVerification implements AppleStoreKitVerification {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final SubscriptionProperties properties;
  private final ObjectMapper objectMapper;

  public SignedDataAppleStoreKitVerification(
      SubscriptionProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public VerifiedTransaction verifyTransaction(
      String signedTransactionInfo, @Nullable String signedRenewalInfo) {
    if (StringUtils.isBlank(signedTransactionInfo)) {
      throw new IllegalArgumentException("Missing signed StoreKit transaction");
    }
    SubscriptionProperties.ProviderSettings settings = settings();
    if (!rootCertificates(settings).isEmpty()) {
      try {
        SignedDataVerifier verifier = verifier(settings);
        JWSTransactionDecodedPayload transaction =
            verifier.verifyAndDecodeTransaction(signedTransactionInfo);
        if (StringUtils.isNotBlank(signedRenewalInfo)) {
          verifier.verifyAndDecodeRenewalInfo(signedRenewalInfo);
        }
        return verified(transaction, signedTransactionInfo, signedRenewalInfo);
      } catch (VerificationException e) {
        throw new IllegalArgumentException("StoreKit transaction verification failed", e);
      }
    }
    if (!settings.isTestModeOnly()) {
      throw new IllegalStateException("Apple StoreKit verification is not configured");
    }
    return decodeUnsignedTransactionForLocalTesting(signedTransactionInfo, signedRenewalInfo);
  }

  @Override
  public VerifiedNotification verifyNotification(String signedPayload) {
    if (StringUtils.isBlank(signedPayload)) {
      throw new WebhookVerificationException("Missing Apple signedPayload");
    }
    SubscriptionProperties.ProviderSettings settings = settings();
    if (!rootCertificates(settings).isEmpty()) {
      try {
        ResponseBodyV2DecodedPayload notification =
            verifier(settings).verifyAndDecodeNotification(signedPayload);
        return verified(notification, signedPayload);
      } catch (VerificationException e) {
        throw new WebhookVerificationException("Apple notification verification failed", e);
      }
    }
    if (!settings.isTestModeOnly()) {
      throw new WebhookVerificationException("Apple StoreKit verification is not configured");
    }
    return decodeUnsignedNotificationForLocalTesting(signedPayload);
  }

  private SignedDataVerifier verifier(SubscriptionProperties.ProviderSettings settings) {
    String bundleId = StringUtils.trimToNull(settings.getBundleId());
    if (bundleId == null) {
      throw new IllegalStateException("Apple StoreKit bundle id is not configured");
    }
    return new SignedDataVerifier(
        rootCertificates(settings),
        bundleId,
        settings.getAppAppleId(),
        environment(settings.getEnvironment()),
        settings.isOnlineChecksEnabled());
  }

  private Environment environment(String value) {
    String normalized = StringUtils.defaultIfBlank(value, "XCODE").trim().toUpperCase();
    return Environment.valueOf(normalized);
  }

  private Set<InputStream> rootCertificates(SubscriptionProperties.ProviderSettings settings) {
    Set<InputStream> certificates = new LinkedHashSet<>();
    String pem = StringUtils.trimToEmpty(settings.getRootCertificatesPem());
    if (pem.isEmpty()) {
      return certificates;
    }
    for (String part : pem.split("-----END CERTIFICATE-----")) {
      String cert = StringUtils.trimToNull(part);
      if (cert != null) {
        String complete = cert + "\n-----END CERTIFICATE-----\n";
        certificates.add(new ByteArrayInputStream(complete.getBytes(StandardCharsets.UTF_8)));
      }
    }
    return certificates;
  }

  private VerifiedTransaction verified(
      JWSTransactionDecodedPayload transaction,
      String signedTransactionInfo,
      @Nullable String signedRenewalInfo) {
    return new VerifiedTransaction(
        transaction.getTransactionId(),
        transaction.getOriginalTransactionId(),
        transaction.getProductId(),
        transaction.getAppAccountToken(),
        transaction.getEnvironment() == null ? null : transaction.getEnvironment().getValue(),
        instant(transaction.getPurchaseDate()),
        instant(transaction.getExpiresDate()),
        instant(transaction.getRevocationDate()),
        transaction.getRawType(),
        rawPayload(signedTransactionInfo, signedRenewalInfo, transaction));
  }

  private VerifiedNotification verified(
      ResponseBodyV2DecodedPayload notification, String signedPayload) {
    String signedTransactionInfo =
        notification.getData() == null ? null : notification.getData().getSignedTransactionInfo();
    VerifiedTransaction transaction =
        StringUtils.isBlank(signedTransactionInfo)
            ? null
            : verifyTransaction(signedTransactionInfo, null);
    return new VerifiedNotification(
        notification.getNotificationUUID(),
        notification.getNotificationType() == null
            ? null
            : notification.getNotificationType().getValue(),
        notification.getSubtype() == null ? null : notification.getSubtype().getValue(),
        transaction,
        rawPayload(signedPayload, null, notification));
  }

  private VerifiedTransaction decodeUnsignedTransactionForLocalTesting(
      String signedTransactionInfo, @Nullable String signedRenewalInfo) {
    Map<String, Object> payload = decodeJwtPayload(signedTransactionInfo);
    return new VerifiedTransaction(
        string(payload.get("transactionId")),
        string(payload.get("originalTransactionId")),
        string(payload.get("productId")),
        uuid(payload.get("appAccountToken")),
        string(payload.get("environment")),
        instant(number(payload.get("purchaseDate"))),
        instant(number(payload.get("expiresDate"))),
        instant(number(payload.get("revocationDate"))),
        string(payload.get("type")),
        rawPayload(signedTransactionInfo, signedRenewalInfo, payload));
  }

  @SuppressWarnings("unchecked")
  private VerifiedNotification decodeUnsignedNotificationForLocalTesting(String signedPayload) {
    Map<String, Object> payload = decodeJwtPayload(signedPayload);
    Map<String, Object> data =
        payload.get("data") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    String signedTransactionInfo = string(data.get("signedTransactionInfo"));
    VerifiedTransaction transaction =
        StringUtils.isBlank(signedTransactionInfo)
            ? null
            : decodeUnsignedTransactionForLocalTesting(signedTransactionInfo, null);
    return new VerifiedNotification(
        string(payload.get("notificationUUID")),
        string(payload.get("notificationType")),
        string(payload.get("subtype")),
        transaction,
        rawPayload(signedPayload, null, payload));
  }

  private Map<String, Object> decodeJwtPayload(String signedPayload) {
    String[] parts = signedPayload.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Invalid StoreKit signed payload");
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      return objectMapper.readValue(decoded, MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid StoreKit signed payload", e);
    }
  }

  private String rawPayload(
      String signedTransactionInfo, @Nullable String signedRenewalInfo, Object decoded) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "signed_transaction_info",
              signedTransactionInfo,
              "signed_renewal_info",
              StringUtils.trimToEmpty(signedRenewalInfo),
              "decoded",
              decoded));
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private SubscriptionProperties.ProviderSettings settings() {
    return properties.providerSettings("apple");
  }

  private Instant instant(@Nullable Long milliseconds) {
    return milliseconds == null ? null : Instant.ofEpochMilli(milliseconds);
  }

  private Long number(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private String string(Object value) {
    return value == null ? null : StringUtils.trimToNull(String.valueOf(value));
  }

  private UUID uuid(Object value) {
    String raw = string(value);
    return raw == null ? null : UUID.fromString(raw);
  }
}
