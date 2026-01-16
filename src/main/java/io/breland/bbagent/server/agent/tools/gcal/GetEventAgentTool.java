package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.Map;

public class GetEventAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "get_event";

  public GetEventAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Fetch a single event by id.",
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
                java.util.List.of("event_id"))),
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
