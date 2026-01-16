package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.ZoneId;
import java.util.Optional;

public class GcalToolSupport {
  protected final GcalClient gcalClient;

  public GcalToolSupport(GcalClient gcalClient) {
    this.gcalClient = gcalClient;
  }

  protected String resolveAccountKey(ToolContext context) {
    return AgentTool.resolveUserIdOrGroupChatId(context.message());
  }

  protected String resolveCalendarId(com.fasterxml.jackson.databind.JsonNode args) {
    String calendarId = getOptionalText(args, "calendar_id");
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
