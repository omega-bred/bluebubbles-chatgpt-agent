package io.breland.bbagent.server.agent.workflowcallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class StandardWebhookVerifier {
  private static final String SECRET_PREFIX = "whsec_";
  private static final String SIGNATURE_PREFIX = "v1,";

  public VerificationResult verify(
      String signingSecret,
      String webhookId,
      String webhookTimestamp,
      String webhookSignature,
      byte[] payload,
      Instant now,
      Duration timestampTolerance) {
    if (StringUtils.isAnyBlank(signingSecret, webhookId, webhookTimestamp, webhookSignature)) {
      return VerificationResult.invalid("missing Standard Webhooks headers");
    }
    if (webhookId.contains(".") || webhookTimestamp.contains(".")) {
      return VerificationResult.invalid("invalid webhook metadata");
    }
    long timestampSeconds;
    try {
      timestampSeconds = Long.parseLong(webhookTimestamp);
    } catch (NumberFormatException e) {
      return VerificationResult.invalid("invalid webhook timestamp");
    }
    Instant timestamp = Instant.ofEpochSecond(timestampSeconds);
    Duration age = Duration.between(timestamp, now).abs();
    if (timestampTolerance != null && age.compareTo(timestampTolerance) > 0) {
      return VerificationResult.invalid("stale webhook timestamp");
    }
    byte[] secret;
    try {
      secret = decodeSecret(signingSecret);
    } catch (IllegalArgumentException e) {
      return VerificationResult.invalid("invalid signing secret");
    }
    byte[] expected;
    try {
      expected = signatureBytes(secret, webhookId, webhookTimestamp, payload);
    } catch (Exception e) {
      return VerificationResult.invalid("failed to verify signature");
    }
    for (String candidate : webhookSignature.trim().split("\\s+")) {
      if (!candidate.startsWith(SIGNATURE_PREFIX)) {
        continue;
      }
      try {
        byte[] actual = Base64.getDecoder().decode(candidate.substring(SIGNATURE_PREFIX.length()));
        if (MessageDigest.isEqual(expected, actual)) {
          return VerificationResult.success();
        }
      } catch (IllegalArgumentException ignored) {
        // Try the next signature in the rotation list.
      }
    }
    return VerificationResult.invalid("invalid webhook signature");
  }

  public String sign(
      String signingSecret, String webhookId, String webhookTimestamp, byte[] payload) {
    byte[] secret = decodeSecret(signingSecret);
    try {
      return SIGNATURE_PREFIX
          + Base64.getEncoder()
              .encodeToString(signatureBytes(secret, webhookId, webhookTimestamp, payload));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to sign webhook payload", e);
    }
  }

  private byte[] decodeSecret(String signingSecret) {
    String encoded =
        signingSecret.startsWith(SECRET_PREFIX)
            ? signingSecret.substring(SECRET_PREFIX.length())
            : signingSecret;
    return Base64.getDecoder().decode(encoded);
  }

  private byte[] signatureBytes(
      byte[] secret, String webhookId, String webhookTimestamp, byte[] payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    mac.update((webhookId + "." + webhookTimestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(payload == null ? new byte[0] : payload);
    return mac.doFinal();
  }

  public record VerificationResult(boolean valid, String error) {
    static VerificationResult success() {
      return new VerificationResult(true, null);
    }

    static VerificationResult invalid(String error) {
      return new VerificationResult(false, error);
    }
  }
}
