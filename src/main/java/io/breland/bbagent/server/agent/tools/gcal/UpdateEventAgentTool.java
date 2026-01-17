package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class UpdateEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "update_event";

  @Schema(description = "Update a calendar event.")
  public record UpdateEventRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID containing the event.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Event ID to update.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("event_id")
          String eventId,
      @Schema(description = "Updated event summary.") String summary,
      @Schema(description = "Updated event description.") String description,
      @Schema(description = "Updated event location.") String location,
      @Schema(description = "Updated event start time.") String start,
      @Schema(description = "Updated event end time.") String end,
      @Schema(description = "Timezone to interpret date/time strings.") String timezone,
      @Schema(description = "Attendee email addresses.") List<String> attendees) {}

  public UpdateEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update a calendar event. Do not use this tool to rename or update conversation / chat information.",
        jsonSchema(UpdateEventRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          UpdateEventRequest request =
              context.getMapper().convertValue(args, UpdateEventRequest.class);
          String accountKey = resolveAccountKey(context, request.accountKey());
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(request.calendarId());
          String eventId = request.eventId();
          if (eventId == null || eventId.isBlank()) {
            return "missing event_id";
          }
          ZoneId zone = resolveZone(request.timezone());
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event patch = new Event();
            String summary = request.summary();
            if (summary != null) {
              patch.setSummary(summary);
            }
            String description = request.description();
            if (description != null) {
              patch.setDescription(description);
            }
            String location = request.location();
            if (location != null) {
              patch.setLocation(location);
            }
            String startText = request.start();
            String endText = request.end();
            String timezone = request.timezone();
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
            List<String> attendeeEmails = request.attendees();
            if (attendeeEmails != null) {
              List<EventAttendee> attendees = new ArrayList<>();
              for (String email : attendeeEmails) {
                if (email != null && !email.isBlank()) {
                  attendees.add(new EventAttendee().setEmail(email));
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
