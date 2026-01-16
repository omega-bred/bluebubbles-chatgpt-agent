package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RespondToEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "respond_to_event";

  public RespondToEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Respond to a calendar event for a specific attendee.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "account_key", Map.of("type", "string"),
                    "calendar_id", Map.of("type", "string"),
                    "event_id", Map.of("type", "string"),
                    "attendee_email", Map.of("type", "string"),
                    "response_status",
                        Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("accepted", "declined", "tentative", "needsAction"))),
                "required",
                List.of("event_id", "attendee_email", "response_status"))),
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
          String eventId = getRequired(args, "event_id");
          String attendeeEmail = getRequired(args, "attendee_email");
          String responseStatus = getRequired(args, "response_status");
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event event = client.events().get(calendarId, eventId).execute();
            List<EventAttendee> attendees = event.getAttendees();
            if (attendees == null) {
              attendees = new ArrayList<>();
            }
            boolean updated = false;
            for (EventAttendee attendee : attendees) {
              if (attendeeEmail.equalsIgnoreCase(attendee.getEmail())) {
                attendee.setResponseStatus(responseStatus);
                updated = true;
                break;
              }
            }
            if (!updated) {
              attendees.add(
                  new EventAttendee().setEmail(attendeeEmail).setResponseStatus(responseStatus));
            }
            event.setAttendees(attendees);
            Event patched = client.events().patch(calendarId, eventId, event).execute();
            return gcalClient.mapper().writeValueAsString(patched);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
