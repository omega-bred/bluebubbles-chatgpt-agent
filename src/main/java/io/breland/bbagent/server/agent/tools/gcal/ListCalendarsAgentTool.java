package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;

public class ListCalendarsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "list_calendars";

  public ListCalendarsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List calendars available for the current account. Use this to map a calendar name to a calendar_id when other tools take the calendar_id parameter. Always search for calendar names to calendar_id or listing the associated calendars using this tool. Use account_key when multiple accounts are linked.",
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
            List<GcalClient.CalendarInfo> calendars = gcalClient.listCalendars(accountKey);
            GcalClient.CalendarAccessConfig accessConfig =
                gcalClient.getCalendarAccessConfig(accountKey);
            Map<String, GcalClient.CalendarAccessMode> accessMap = accessConfig.accessMap();
            if (accessConfig.configured()) {
              calendars =
                  calendars.stream()
                      .filter(item -> accessMap.containsKey(item.calendarId()))
                      .toList();
            }
            List<Map<String, Object>> response = new ArrayList<>();
            for (GcalClient.CalendarInfo entry : calendars) {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("calendar_id", entry.calendarId());
              item.put("summary", entry.summary());
              item.put("primary", entry.primary());
              item.put("timeZone", entry.timeZone());
              item.put("accessRole", entry.accessRole());
              response.add(item);
            }
            return gcalClient.mapper().writeValueAsString(response);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
