package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Colors;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.Map;

public class ListColorsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "list_colors";

  public ListColorsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List available calendar colors.",
        jsonSchema(
            Map.of(
                "type", "object", "properties", Map.of("account_key", Map.of("type", "string")))),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          String accountKey = resolveAccountKey(context, args);
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Colors colors = client.colors().get().execute();
            return gcalClient.mapper().writeValueAsString(colors);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
