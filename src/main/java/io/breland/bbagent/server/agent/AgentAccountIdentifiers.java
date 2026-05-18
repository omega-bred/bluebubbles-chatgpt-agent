package io.breland.bbagent.server.agent;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AgentAccountIdentifiers {
  public static final String IMESSAGE_EMAIL = "imessage_email";
  public static final String IMESSAGE_PHONE = "imessage_phone";
  public static final String LXMF_ADDRESS = "lxmf_address";

  private static final Pattern NON_DIGITS = Pattern.compile("\\D+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private AgentAccountIdentifiers() {}

  public static Optional<NormalizedIdentifier> normalizeMessageIdentity(
      String transport, String identifier) {
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
    if (IncomingMessage.TRANSPORT_LXMF.equalsIgnoreCase(transportKey)) {
      String value = compact(clean);
      return Optional.of(new NormalizedIdentifier(LXMF_ADDRESS, value, value));
    }
    return normalizeIMessage(clean);
  }

  public static Optional<NormalizedIdentifier> normalize(String transport, String identifier) {
    return normalizeMessageIdentity(transport, identifier);
  }

  public static Optional<NormalizedIdentifier> normalizeByType(String type, String identifier) {
    if (type == null || identifier == null) {
      return Optional.empty();
    }
    String clean = stripAddressScheme(identifier).trim().toLowerCase(Locale.ROOT);
    if (clean.isBlank()) {
      return Optional.empty();
    }
    return switch (type) {
      case IMESSAGE_EMAIL ->
          Optional.of(new NormalizedIdentifier(type, compact(clean), compact(clean)));
      case IMESSAGE_PHONE ->
          normalizePhone(clean).map(value -> new NormalizedIdentifier(type, value, value));
      case LXMF_ADDRESS ->
          Optional.of(new NormalizedIdentifier(type, compact(clean), compact(clean)));
      default -> Optional.empty();
    };
  }

  private static Optional<NormalizedIdentifier> normalizeIMessage(String clean) {
    if (clean.contains("@")) {
      String value = compact(clean);
      return Optional.of(new NormalizedIdentifier(IMESSAGE_EMAIL, value, value));
    }
    Optional<String> phone = normalizePhone(clean);
    if (phone.isPresent()) {
      return Optional.of(new NormalizedIdentifier(IMESSAGE_PHONE, phone.get(), phone.get()));
    }
    String value = compact(clean);
    return Optional.of(new NormalizedIdentifier(IMESSAGE_EMAIL, value, value));
  }

  public static boolean equivalent(String left, String right) {
    Optional<NormalizedIdentifier> normalizedLeft =
        normalizeMessageIdentity(IncomingMessage.TRANSPORT_BLUEBUBBLES, left);
    Optional<NormalizedIdentifier> normalizedRight =
        normalizeMessageIdentity(IncomingMessage.TRANSPORT_BLUEBUBBLES, right);
    return normalizedLeft.isPresent()
        && normalizedRight.isPresent()
        && normalizedLeft.get().type().equals(normalizedRight.get().type())
        && normalizedLeft.get().value().equals(normalizedRight.get().value());
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

  private static Optional<String> normalizePhone(String value) {
    String trimmed = value == null ? "" : value.trim();
    String digits = NON_DIGITS.matcher(trimmed).replaceAll("");
    if (digits.length() < 7) {
      return Optional.empty();
    }
    if (digits.length() == 10) {
      return Optional.of("+1" + digits);
    }
    if (digits.length() == 11 && digits.startsWith("1")) {
      return Optional.of("+" + digits);
    }
    if (trimmed.startsWith("+")) {
      return Optional.of("+" + digits);
    }
    return Optional.of("+" + digits);
  }

  private static String compact(String value) {
    return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll("");
  }

  public record NormalizedIdentifier(String type, String value, String displayValue) {}
}
