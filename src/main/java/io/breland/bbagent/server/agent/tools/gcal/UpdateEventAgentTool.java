package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
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
          UpdateEventRequest request =
              context.getMapper().convertValue(args, UpdateEventRequest.class);
          return withCalendar(
              context,
              request.accountKey(),
              (client, accountKey) -> {
                String calendarId = resolveCalendarId(request.calendarId());
                String eventId = request.eventId();
                if (isBlank(eventId)) {
                  return "missing event_id";
                }
                ZoneId zone = resolveZone(request.timezone());
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
                if (!isBlank(startText)) {
                  DateTime start = gcalClient.parseDateTime(startText, zone);
                  if (start != null) {
                    patch.setStart(eventDateTime(start, request.timezone()));
                  }
                }
                if (!isBlank(endText)) {
                  DateTime end = gcalClient.parseDateTime(endText, zone);
                  if (end != null) {
                    patch.setEnd(eventDateTime(end, request.timezone()));
                  }
                }
                List<String> attendeeEmails = request.attendees();
                if (attendeeEmails != null) {
                  patch.setAttendees(attendeesFromEmails(attendeeEmails));
                }
                Event updated = client.events().patch(calendarId, eventId, patch).execute();
                return toJson(updated);
              });
        });
  }
}
