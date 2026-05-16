package io.breland.bbagent.server.agent;

import java.util.Locale;
import java.util.Optional;

public final class AgentAccountIdentifiers {
  private AgentAccountIdentifiers() {}

  public static Optional<NormalizedIdentifier> normalize(String transport, String identifier) {
    if (identifier == null) {
      return Optional.empty();
    }
    String clean = stripAddressScheme(identifier).trim().toLowerCase(Locale.ROOT);
    if (clean.isBlank()) {
      return Optional.empty();
    }
    String transportKey =
        transport == null || transport.isBlank()
            ? IncomingMessage.TRANSPORT_BLUEBUBBLES
            : transport.trim().toLowerCase(Locale.ROOT);
    if (clean.contains("@")) {
      String value = clean.replaceAll("\\s+", "");
      return Optional.of(
          new NormalizedIdentifier("email", value, aliasKey(transportKey, "email", value)));
    }
    String digits = clean.replaceAll("\\D+", "");
    if (digits.length() >= 7) {
      String value = normalizePhoneDigits(digits);
      return Optional.of(
          new NormalizedIdentifier("phone", value, aliasKey(transportKey, "phone", value)));
    }
    String value = clean.replaceAll("\\s+", "");
    return Optional.of(
        new NormalizedIdentifier("handle", value, aliasKey(transportKey, "handle", value)));
  }

  public static boolean equivalent(String left, String right) {
    Optional<NormalizedIdentifier> normalizedLeft =
        normalize(IncomingMessage.TRANSPORT_BLUEBUBBLES, left);
    Optional<NormalizedIdentifier> normalizedRight =
        normalize(IncomingMessage.TRANSPORT_BLUEBUBBLES, right);
    return normalizedLeft.isPresent()
        && normalizedRight.isPresent()
        && normalizedLeft.get().aliasKey().equals(normalizedRight.get().aliasKey());
  }

  public static String stripAddressScheme(String value) {
    if (value == null) {
      return null;
    }
    String clean = value.trim();
    String lower = clean.toLowerCase(Locale.ROOT);
    if (lower.startsWith("mailto:")) {
      return clean.substring("mailto:".length());
    }
    if (lower.startsWith("tel:")) {
      return clean.substring("tel:".length());
    }
    return clean;
  }

  private static String normalizePhoneDigits(String digits) {
    if (digits.length() == 11 && digits.startsWith("1")) {
      return digits.substring(1);
    }
    return digits;
  }

  private static String aliasKey(String transport, String type, String value) {
    return transport + ":" + type + ":" + value;
  }

  public record NormalizedIdentifier(String type, String value, String aliasKey) {}
}
