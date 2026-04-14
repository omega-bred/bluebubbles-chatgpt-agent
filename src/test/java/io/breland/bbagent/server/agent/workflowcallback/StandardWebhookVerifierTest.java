package io.breland.bbagent.server.agent.workflowcallback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class StandardWebhookVerifierTest {
  private final StandardWebhookVerifier verifier = new StandardWebhookVerifier();
  private final String secret =
      "whsec_" + Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes());
  private final Instant now = Instant.ofEpochSecond(1_700_000_000L);

  @Test
  void acceptsValidSignature() {
    byte[] payload = payload();
    String timestamp = String.valueOf(now.getEpochSecond());
    String signature = verifier.sign(secret, "msg_valid", timestamp, payload);

    assertTrue(
        verifier
            .verify(secret, "msg_valid", timestamp, signature, payload, now, Duration.ofMinutes(5))
            .valid());
  }

  @Test
  void rejectsTamperedBody() {
    byte[] payload = payload();
    String timestamp = String.valueOf(now.getEpochSecond());
    String signature = verifier.sign(secret, "msg_valid", timestamp, payload);

    assertFalse(
        verifier
            .verify(
                secret,
                "msg_valid",
                timestamp,
                signature,
                "{\"ok\":false}".getBytes(StandardCharsets.UTF_8),
                now,
                Duration.ofMinutes(5))
            .valid());
  }

  @Test
  void rejectsStaleTimestamp() {
    byte[] payload = payload();
    String timestamp = String.valueOf(now.minus(Duration.ofMinutes(10)).getEpochSecond());
    String signature = verifier.sign(secret, "msg_valid", timestamp, payload);

    assertFalse(
        verifier
            .verify(secret, "msg_valid", timestamp, signature, payload, now, Duration.ofMinutes(5))
            .valid());
  }

  @Test
  void rejectsWrongSecret() {
    byte[] payload = payload();
    String timestamp = String.valueOf(now.getEpochSecond());
    String signature = verifier.sign(secret, "msg_valid", timestamp, payload);
    String wrongSecret =
        "whsec_"
            + Base64.getEncoder().encodeToString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes());

    assertFalse(
        verifier
            .verify(
                wrongSecret, "msg_valid", timestamp, signature, payload, now, Duration.ofMinutes(5))
            .valid());
  }

  @Test
  void rejectsMissingHeaders() {
    assertFalse(
        verifier.verify(secret, null, null, null, payload(), now, Duration.ofMinutes(5)).valid());
  }

  private byte[] payload() {
    return "{\"type\":\"agent.async_task.completed\",\"timestamp\":\"2024-01-01T00:00:00Z\",\"data\":{\"status\":\"completed\"}}"
        .getBytes(StandardCharsets.UTF_8);
  }
}
