package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.calendar.Calendar;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class DeleteEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "delete_event";

  @Schema(description = "Delete a calendar event.")
  public record DeleteEventRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID containing the event.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Event ID to delete.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("event_id")
          String eventId) {}

  public DeleteEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a calendar event.",
        jsonSchema(DeleteEventRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          DeleteEventRequest request =
              context.getMapper().convertValue(args, DeleteEventRequest.class);
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
            client.events().delete(calendarId, eventId).execute();
            return "deleted";
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
