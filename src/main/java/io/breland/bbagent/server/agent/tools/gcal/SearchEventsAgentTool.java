package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchEventsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "search_events";

  public SearchEventsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search events in a calendar within a time range.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "account_key", Map.of("type", "string"),
                    "calendar_id", Map.of("type", "string"),
                    "query", Map.of("type", "string"),
                    "time_min", Map.of("type", "string"),
                    "time_max", Map.of("type", "string"),
                    "max_results", Map.of("type", "integer"),
                    "timezone", Map.of("type", "string")))),
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
          String query = getOptionalText(args, "query");
          if (query == null || query.isBlank()) {
            return "missing query";
          }
          ZoneId zone = resolveZone(args);
          DateTime min = gcalClient.parseDateTime(getOptionalText(args, "time_min"), zone);
          DateTime max = gcalClient.parseDateTime(getOptionalText(args, "time_max"), zone);
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Calendar.Events.List request = client.events().list(calendarId).setQ(query);
            if (min != null) {
              request.setTimeMin(min);
            }
            if (max != null) {
              request.setTimeMax(max);
            }
            getOptionalInt(args, "max_results").ifPresent(request::setMaxResults);
            Events events = request.execute();
            List<Map<String, Object>> results = new ArrayList<>();
            if (events.getItems() != null) {
              for (Event event : events.getItems()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", event.getId());
                entry.put("summary", event.getSummary());
                entry.put("start", event.getStart());
                entry.put("end", event.getEnd());
                entry.put("status", event.getStatus());
                entry.put("htmlLink", event.getHtmlLink());
                results.add(entry);
              }
            }
            return gcalClient.mapper().writeValueAsString(results);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
