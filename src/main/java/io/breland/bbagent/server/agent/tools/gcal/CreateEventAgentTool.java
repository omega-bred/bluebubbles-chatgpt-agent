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
          CreateEventRequest request =
              context.getMapper().convertValue(args, CreateEventRequest.class);
          return withCalendar(
              context,
              request.accountKey(),
              (client, accountKey) -> {
                String calendarId = resolveCalendarId(request.calendarId());
                ZoneId zone = resolveZone(request.timezone());
                if (isBlank(request.start())) {
                  return "missing start";
                }
                if (isBlank(request.end())) {
                  return "missing end";
                }
                DateTime start = gcalClient.parseDateTime(request.start(), zone);
                DateTime end = gcalClient.parseDateTime(request.end(), zone);
                if (start == null || end == null) {
                  return "invalid time";
                }
                Event event = new Event();
                String summary = request.summary();
                if (isBlank(summary)) {
                  return "missing summary";
                }
                event.setSummary(summary);
                String description = request.description();
                if (!isBlank(description)) {
                  event.setDescription(description);
                }
                String location = request.location();
                if (!isBlank(location)) {
                  event.setLocation(location);
                }
                event.setStart(eventDateTime(start, request.timezone()));
                event.setEnd(eventDateTime(end, request.timezone()));

                var attendees = attendeesFromEmails(request.attendees());
                if (!attendees.isEmpty()) {
                  event.setAttendees(attendees);
                }

                Event created = client.events().insert(calendarId, event).execute();
                return toJson(created);
              });
        });
  }
}
