package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ListCalendarsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "list_calendars";

  @Schema(description = "List calendars for the current linked account.")
  public record ListCalendarsRequest(
      @Schema(description = "Account key to list calendars for.") @JsonProperty("account_key")
          String accountKey) {}

  public ListCalendarsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List calendars available for the current account. Use this to map a calendar name to a calendar_id when other tools take the calendar_id parameter. Always search for calendar names to calendar_id or listing the associated calendars using this tool. Use account_key when multiple accounts are linked.",
        jsonSchema(ListCalendarsRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          ListCalendarsRequest request =
              context.getMapper().convertValue(args, ListCalendarsRequest.class);
          String accountKey = resolveAccountKey(context, request.accountKey());
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            CalendarList list = client.calendarList().list().execute();
            List<Map<String, Object>> calendars = new ArrayList<>();
            if (list.getItems() != null) {
              for (CalendarListEntry entry : list.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("calendar_id", entry.getId());
                item.put("summary", entry.getSummary());
                item.put("primary", entry.getPrimary());
                item.put("timeZone", entry.getTimeZone());
                item.put("accessRole", entry.getAccessRole());
                calendars.add(item);
              }
            }
            return gcalClient.mapper().writeValueAsString(calendars);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
