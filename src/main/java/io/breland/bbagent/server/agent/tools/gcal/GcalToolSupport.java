package io.breland.bbagent.server.agent.tools.gcal;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.ZoneId;
import java.util.Optional;

public class GcalToolSupport {
  protected final GcalClient gcalClient;

  public GcalToolSupport(GcalClient gcalClient) {
    this.gcalClient = gcalClient;
  }

  public static String getOptionalText(JsonNode args, String field) {
    JsonNode value = args.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText();
  }

  protected String resolveAccountKey(
      ToolContext context, com.fasterxml.jackson.databind.JsonNode args) {
    String accountId = getOptionalText(args, "account_key");
    String accountBase = resolveAccountBase(context);
    return gcalClient.scopeAccountKey(accountBase, accountId);
  }

  protected String resolveAccountKey(ToolContext context, String accountId) {
    String accountBase = resolveAccountBase(context);
    return gcalClient.scopeAccountKey(accountBase, accountId);
  }

  protected String resolveAccountBase(JsonNode context) {
    if (context == null || context.message() == null) {
      return null;
    }
    String chatGuid = context.message().chatGuid();
    String sender = context.message().sender();
    if (chatGuid != null && !chatGuid.isBlank() && sender != null && !sender.isBlank()) {
      return chatGuid + "|" + sender;
    }
    if (sender != null && !sender.isBlank()) {
      return sender;
    }
    if (chatGuid != null && !chatGuid.isBlank()) {
      return chatGuid;
    }
    return null;
  }

  protected ZoneId resolveCalendarId(com.fasterxml.jackson.databind.JsonNode args) {
    String calendarId = getOptionalText(args, "calendar_id");
    if (calendarId != null && !calendarId.isBlank()) {
      return calendarId;
    }
    return "primary";
  }

  protected String resolveCalendarId(String calendarId) {
    if (calendarId != null && !calendarId.isBlank()) {
      return calendarId;
    }
    return "primary";
  }

  protected ZoneId resolveZone(com.fasterxml.jackson.databind.JsonNode args) {
    String timezone = getOptionalText(args, "timezone");
    if (timezone == null || timezone.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(timezone);
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  protected ZoneId resolveZone(String timezone) {
    if (timezone == null || timezone.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(timezone);
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  protected Optional<Integer> getOptionalInt(
      com.fasterxml.jackson.databind.JsonNode args, String field) {
    com.fasterxml.jackson.databind.JsonNode node = args.get(field);
    if (node == null || node.isNull() || !node.isNumber()) {
      return Optional.empty();
    }
    return Optional.of(node.asInt());
  }

  protected Optional<Boolean> getOptionalBoolean(
      com.fasterxml.jackson.databind.JsonNode args, String field) {
    com.fasterxml.jackson.databind.JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (node.isBoolean()) {
      return Optional.of(node.asBoolean());
    }
    if (node.isTextual()) {
      return Optional.of(Boolean.parseBoolean(node.asText()));
    }
    return Optional.empty();
  }
}
