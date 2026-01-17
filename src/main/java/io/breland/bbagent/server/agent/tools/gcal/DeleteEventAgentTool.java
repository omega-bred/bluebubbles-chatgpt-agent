package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.services.calendar.Calendar;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;

public class DeleteEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "delete_event";

  public DeleteEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a calendar event.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "account_key", Map.of("type", "string"),
                    "calendar_id", Map.of("type", "string"),
                    "event_id", Map.of("type", "string")),
                "required",
                List.of("event_id"))),
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
          String eventId = getRequired(args, "event_id");
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
