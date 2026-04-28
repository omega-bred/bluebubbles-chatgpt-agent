package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

public class RespondToEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "respond_to_event";

  @Schema(description = "Respond to a calendar event for a specific attendee.")
  public record RespondToEventRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID containing the event.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Event ID to respond to.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("event_id")
          String eventId,
      @Schema(description = "Attendee email address.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("attendee_email")
          String attendeeEmail,
      @Schema(
              description = "Attendee response status.",
              allowableValues = {"accepted", "declined", "tentative", "needsAction"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("response_status")
          ResponseStatus responseStatus) {}

  public enum ResponseStatus {
    @JsonProperty("accepted")
    ACCEPTED,
    @JsonProperty("declined")
    DECLINED,
    @JsonProperty("tentative")
    TENTATIVE,
    @JsonProperty("needsAction")
    NEEDS_ACTION
  }

  public RespondToEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Respond to a calendar event for a specific attendee.",
        jsonSchema(RespondToEventRequest.class),
        false,
        (context, args) -> {
          RespondToEventRequest request =
              context.getMapper().convertValue(args, RespondToEventRequest.class);
          return withCalendar(
              context,
              request.accountKey(),
              (client, accountKey) -> {
                String calendarId = resolveCalendarId(request.calendarId());
                String eventId = request.eventId();
                String attendeeEmail = request.attendeeEmail();
                ResponseStatus responseStatus = request.responseStatus();
                if (isBlank(eventId)) {
                  return "missing event_id";
                }
                if (isBlank(attendeeEmail)) {
                  return "missing attendee_email";
                }
                if (responseStatus == null) {
                  return "missing response_status";
                }
                Event event = client.events().get(calendarId, eventId).execute();
                List<EventAttendee> attendees = event.getAttendees();
                if (attendees == null) {
                  attendees = new ArrayList<>();
                }
                boolean updated = false;
                for (EventAttendee attendee : attendees) {
                  if (attendeeEmail.equalsIgnoreCase(attendee.getEmail())) {
                    attendee.setResponseStatus(responseStatusString(responseStatus));
                    updated = true;
                    break;
                  }
                }
                if (!updated) {
                  attendees.add(
                      new EventAttendee()
                          .setEmail(attendeeEmail)
                          .setResponseStatus(responseStatusString(responseStatus)));
                }
                event.setAttendees(attendees);
                Event patched = client.events().patch(calendarId, eventId, event).execute();
                return toJson(patched);
              });
        });
  }

  private String responseStatusString(ResponseStatus status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case ACCEPTED -> "accepted";
      case DECLINED -> "declined";
      case TENTATIVE -> "tentative";
      case NEEDS_ACTION -> "needsAction";
    };
  }
}
