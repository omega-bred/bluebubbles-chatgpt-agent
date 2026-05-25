package io.breland.bbagent.server.agent.tools.gcal;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GcalToolSupport {
  protected final GcalClient gcalClient;

  public GcalToolSupport(GcalClient gcalClient) {
    this.gcalClient = gcalClient;
  }

  protected String resolveAccountKey(ToolContext context, String accountId) {
    String resolvedAccountId = resolveAccountId(context);
    return gcalClient.scopeAccountKey(resolvedAccountId, accountId);
  }

  protected String withCalendar(
      ToolContext context, String requestedAccountKey, CalendarOperation operation) {
    return withConfigured(
        () -> {
          String accountKey = resolveAccountKey(context, requestedAccountKey);
          if (isBlank(accountKey)) {
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

  protected String resolveAccountId(ToolContext context) {
    if (context == null || context.message() == null) {
      return null;
    }
    return context.accountId();
  }

  protected String resolveCalendarId(String calendarId) {
    if (!isBlank(calendarId)) {
      return calendarId;
    }
    return "primary";
  }

  protected ZoneId resolveZone(String timezone) {
    if (isBlank(timezone)) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(timezone);
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  protected boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  protected String toJson(Object value) throws Exception {
    return gcalClient.mapper().writeValueAsString(value);
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
    if (!isBlank(timezone)) {
      eventDateTime.setTimeZone(timezone);
    }
    return eventDateTime;
  }

  protected List<EventAttendee> attendeesFromEmails(List<String> attendeeEmails) {
    if (attendeeEmails == null || attendeeEmails.isEmpty()) {
      return List.of();
    }
    List<EventAttendee> attendees = new ArrayList<>();
    for (String email : attendeeEmails) {
      if (!isBlank(email)) {
        attendees.add(new EventAttendee().setEmail(email));
      }
    }
    return attendees;
  }
}
