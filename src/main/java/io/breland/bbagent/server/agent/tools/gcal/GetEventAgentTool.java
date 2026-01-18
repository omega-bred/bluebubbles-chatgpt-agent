package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class GetEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "get_event";

  @Schema(description = "Fetch a single event by id.")
  public record GetEventRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID containing the event.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Event ID to fetch.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("event_id")
          String eventId) {}

  public GetEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Fetch a single event by id.",
        jsonSchema(GetEventRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          GetEventRequest request = context.getMapper().convertValue(args, GetEventRequest.class);
          String accountKey = resolveAccountKey(context, request.accountKey());
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(request.calendarId());
          String eventId = request.eventId();
          if (eventId == null || eventId.isBlank()) {
            return "missing event_id";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Event event = client.events().get(calendarId, eventId).execute();
            return gcalClient.mapper().writeValueAsString(event);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
