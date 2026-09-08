package io.breland.bbagent.server.agent;

import java.util.regex.Pattern;

public final class AssistantMessageText {
  private static final Pattern LEADING_MESSAGE_GUID =
      Pattern.compile(
          "\\A\\s*(?:\\[messageGuid=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
              + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\]\\s*)+");

  private AssistantMessageText() {}

  /** Remove an echoed transport prefix, preserving message IDs quoted within ordinary text. */
  public static String stripLeadingMessageGuid(String text) {
    return text == null ? null : LEADING_MESSAGE_GUID.matcher(text).replaceFirst("");
  }
}
