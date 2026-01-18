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

public class CreateEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "create_event";

  @Schema(description = "Create a calendar event.")
  public record CreateEventRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID to create the event in.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Event summary/title.", requiredMode = Schema.RequiredMode.REQUIRED)
          String summary,
      @Schema(description = "Event description.") String description,
      @Schema(description = "Event location.") String location,
      @Schema(description = "Event start time.", requiredMode = Schema.RequiredMode.REQUIRED)
          String start,
      @Schema(description = "Event end time.", requiredMode = Schema.RequiredMode.REQUIRED)
          String end,
      @Schema(description = "Timezone to interpret date/time strings.") String timezone,
      @Schema(description = "Attendee email addresses.") List<String> attendees) {}

  public CreateEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create a calendar event.",
        jsonSchema(CreateEventRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          CreateEventRequest request =
              context.getMapper().convertValue(args, CreateEventRequest.class);
          String accountKey = resolveAccountKey(context, request.accountKey());
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(request.calendarId());
          ZoneId zone = resolveZone(request.timezone());
          String startText = request.start();
          String endText = request.end();
          if (startText == null || startText.isBlank()) {
            return "missing start";
          }
          if (endText == null || endText.isBlank()) {
            return "missing end";
          }
          DateTime start = gcalClient.parseDateTime(startText, zone);
          DateTime end = gcalClient.parseDateTime(endText, zone);
          if (start == null || end == null) {
            return "invalid time";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event event = new Event();
            String summary = request.summary();
            if (summary == null || summary.isBlank()) {
              return "missing summary";
            }
            event.setSummary(summary);
            String description = request.description();
            if (description != null && !description.isBlank()) {
              event.setDescription(description);
            }
            String location = request.location();
            if (location != null && !location.isBlank()) {
              event.setLocation(location);
            }
            EventDateTime startTime = new EventDateTime().setDateTime(start);
            EventDateTime endTime = new EventDateTime().setDateTime(end);
            String timezone = request.timezone();
            if (timezone != null && !timezone.isBlank()) {
              startTime.setTimeZone(timezone);
              endTime.setTimeZone(timezone);
            }
            event.setStart(startTime);
            event.setEnd(endTime);

            List<String> attendeeEmails = request.attendees();
            if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
              List<EventAttendee> attendees = new ArrayList<>();
              for (String email : attendeeEmails) {
                if (email != null && !email.isBlank()) {
                  attendees.add(new EventAttendee().setEmail(email));
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
