package io.breland.bbagent.server.agent.transport.twiliorcs;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class TwilioRcsAddress {
  private static final String RCS_PREFIX = "rcs:";

  private TwilioRcsAddress() {}

  public static String normalizeEndpoint(String endpoint) {
    String value = StringUtils.trimToNull(endpoint);
    if (value == null) {
      return null;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith(RCS_PREFIX)) {
      return value.substring(RCS_PREFIX.length()).trim();
    }
    if (lower.startsWith("tel:")) {
      return value.substring("tel:".length()).trim();
    }
    if (lower.startsWith("sms:")) {
      return value.substring("sms:".length()).trim();
    }
    return value;
  }

  public static String toRcsRecipient(String endpoint) {
    String value = StringUtils.trimToNull(endpoint);
    if (value == null) {
      return null;
    }
    if (value.toLowerCase(Locale.ROOT).startsWith(RCS_PREFIX)) {
      return value;
    }
    String normalized = normalizeEndpoint(value);
    return normalized == null ? null : RCS_PREFIX + normalized;
  }

  public static String toRcsSender(String sender) {
    String value = StringUtils.trimToNull(sender);
    if (value == null) {
      return null;
    }
    return value.toLowerCase(Locale.ROOT).startsWith(RCS_PREFIX) ? value : RCS_PREFIX + value;
  }
}
