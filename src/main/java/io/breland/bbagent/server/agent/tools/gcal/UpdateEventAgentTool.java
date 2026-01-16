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

public class UpdateEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "update_event";

  public UpdateEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update a calendar event. Do not use this tool to rename or update conversation / chat information.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "calendar_id", Map.of("type", "string"),
                    "event_id", Map.of("type", "string"),
                    "summary", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "location", Map.of("type", "string"),
                    "start", Map.of("type", "string"),
                    "end", Map.of("type", "string"),
                    "timezone", Map.of("type", "string"),
                    "attendees", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("event_id"))),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          String accountKey = resolveAccountKey(context);
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(args);
          String eventId = getRequired(args, "event_id");
          ZoneId zone = resolveZone(args);
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event patch = new Event();
            String summary = getOptionalText(args, "summary");
            if (summary != null) {
              patch.setSummary(summary);
            }
            String description = getOptionalText(args, "description");
            if (description != null) {
              patch.setDescription(description);
            }
            String location = getOptionalText(args, "location");
            if (location != null) {
              patch.setLocation(location);
            }
            String startText = getOptionalText(args, "start");
            String endText = getOptionalText(args, "end");
            String timezone = getOptionalText(args, "timezone");
            if (startText != null && !startText.isBlank()) {
              DateTime start = gcalClient.parseDateTime(startText, zone);
              if (start != null) {
                EventDateTime startTime = new EventDateTime().setDateTime(start);
                if (timezone != null && !timezone.isBlank()) {
                  startTime.setTimeZone(timezone);
                }
                patch.setStart(startTime);
              }
            }
            if (endText != null && !endText.isBlank()) {
              DateTime end = gcalClient.parseDateTime(endText, zone);
              if (end != null) {
                EventDateTime endTime = new EventDateTime().setDateTime(end);
                if (timezone != null && !timezone.isBlank()) {
                  endTime.setTimeZone(timezone);
                }
                patch.setEnd(endTime);
              }
            }
            if (args.has("attendees") && args.get("attendees").isArray()) {
              List<EventAttendee> attendees = new ArrayList<>();
              for (var node : args.get("attendees")) {
                if (node != null && node.isTextual()) {
                  attendees.add(new EventAttendee().setEmail(node.asText()));
                }
              }
              patch.setAttendees(attendees);
            }
            Event updated = client.events().patch(calendarId, eventId, patch).execute();
            return gcalClient.mapper().writeValueAsString(updated);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
