package io.breland.bbagent.server.agent.tools.gcal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import io.breland.bbagent.server.agent.AgentAccountIdentity;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

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

  protected String withCalendar(
      ToolContext context, String requestedAccountKey, CalendarOperation operation) {
    return withConfigured(
        () -> {
          String accountKey = resolveAccountKey(context, requestedAccountKey);
          if (!StringUtils.hasText(accountKey)) {
            return "no account";
          }
          return operation.apply(gcalClient.getCalendarService(accountKey), accountKey);
        });
  }

  protected String withConfigured(GcalOperation operation) {
    if (!gcalClient.isConfigured()) {
      return "not configured";
    }
    try {
      return operation.apply();
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }

  @FunctionalInterface
  protected interface CalendarOperation {
    String apply(Calendar client, String accountKey) throws Exception;
  }

  @FunctionalInterface
  protected interface GcalOperation {
    String apply() throws Exception;
  }

  protected String resolveAccountBase(ToolContext context) {
    if (context == null || context.message() == null) {
      return null;
    }
    AgentAccountIdentity identity = AgentAccountIdentity.from(context.message());
    return identity.hasAccountBase() ? identity.gcalAccountBase() : null;
  }

  protected String resolveCalendarId(String calendarId) {
    return StringUtils.hasText(calendarId) ? calendarId : "primary";
  }

  protected ZoneId resolveZone(com.fasterxml.jackson.databind.JsonNode args) {
    String timezone = getOptionalText(args, "timezone");
    if (!StringUtils.hasText(timezone)) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(timezone);
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  protected ZoneId resolveZone(String timezone) {
    if (!StringUtils.hasText(timezone)) {
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

  protected String toJson(Object value) {
    try {
      return gcalClient.mapper().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize Google Calendar tool response", e);
    }
  }

  protected List<Map<String, Object>> eventSummaries(Events events) {
    if (events == null || events.getItems() == null) {
      return List.of();
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Event event : events.getItems()) {
      results.add(eventSummary(event));
    }
    return results;
  }

  protected Map<String, Object> eventSummary(Event event) {
    Map<String, Object> entry = new LinkedHashMap<>();
    if (event == null) {
      return entry;
    }
    entry.put("id", event.getId());
    entry.put("summary", event.getSummary());
    entry.put("start", event.getStart());
    entry.put("end", event.getEnd());
    entry.put("status", event.getStatus());
    entry.put("htmlLink", event.getHtmlLink());
    return entry;
  }

  protected EventDateTime eventDateTime(DateTime dateTime, String timezone) {
    EventDateTime eventDateTime = new EventDateTime().setDateTime(dateTime);
    if (StringUtils.hasText(timezone)) {
      eventDateTime.setTimeZone(timezone);
    }
    return eventDateTime;
  }

  protected List<EventAttendee> attendeesFromEmails(List<String> attendeeEmails) {
    if (attendeeEmails == null || attendeeEmails.isEmpty()) {
      return List.of();
    }
    return attendeeEmails.stream()
        .filter(StringUtils::hasText)
        .map(email -> new EventAttendee().setEmail(email))
        .toList();
  }

  protected List<FreeBusyRequestItem> freeBusyItemsFromCalendarIds(List<String> calendarIds) {
    if (calendarIds == null || calendarIds.isEmpty()) {
      return List.of();
    }
    return calendarIds.stream()
        .filter(StringUtils::hasText)
        .map(calendarId -> new FreeBusyRequestItem().setId(calendarId))
        .toList();
  }
}
