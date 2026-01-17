package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CreateEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "create_event";

  public CreateEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create a calendar event.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "account_key", Map.of("type", "string"),
                    "calendar_id", Map.of("type", "string"),
                    "summary", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "location", Map.of("type", "string"),
                    "start", Map.of("type", "string"),
                    "end", Map.of("type", "string"),
                    "timezone", Map.of("type", "string"),
                    "attendees", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("summary", "start", "end"))),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          String accountKey = resolveAccountKey(context, args);
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(args);
          if (!gcalClient.canWriteCalendar(accountKey, calendarId)) {
            return "no access";
          }
          ZoneId zone = resolveZone(args);
          String startText = getRequired(args, "start");
          String endText = getRequired(args, "end");
          DateTime start = gcalClient.parseDateTime(startText, zone);
          DateTime end = gcalClient.parseDateTime(endText, zone);
          if (start == null || end == null) {
            return "invalid time";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event event = new Event();
            event.setSummary(getRequired(args, "summary"));
            String description = getOptionalText(args, "description");
            if (description != null && !description.isBlank()) {
              event.setDescription(description);
            }
            String location = getOptionalText(args, "location");
            if (location != null && !location.isBlank()) {
              event.setLocation(location);
            }
            EventDateTime startTime = new EventDateTime().setDateTime(start);
            EventDateTime endTime = new EventDateTime().setDateTime(end);
            String timezone = getOptionalText(args, "timezone");
            if (timezone != null && !timezone.isBlank()) {
              startTime.setTimeZone(timezone);
              endTime.setTimeZone(timezone);
            }
            event.setStart(startTime);
            event.setEnd(endTime);

            if (args.has("attendees") && args.get("attendees").isArray()) {
              List<EventAttendee> attendees = new ArrayList<>();
              for (var node : args.get("attendees")) {
                if (node != null && node.isTextual()) {
                  attendees.add(new EventAttendee().setEmail(node.asText()));
                }
              }
              if (!attendees.isEmpty()) {
                event.setAttendees(attendees);
              }
            }

            Event created = client.events().insert(calendarId, event).execute();
            return gcalClient.mapper().writeValueAsString(created);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
