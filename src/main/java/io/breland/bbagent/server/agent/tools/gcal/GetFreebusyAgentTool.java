package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GetFreebusyAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "get_freebusy";

  public GetFreebusyAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get free/busy information for calendars.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "account_key", Map.of("type", "string"),
                    "time_min", Map.of("type", "string"),
                    "time_max", Map.of("type", "string"),
                    "timezone", Map.of("type", "string"),
                    "calendars", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("time_min", "time_max", "calendars"))),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          String accountKey = resolveAccountKey(context, args);
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          ZoneId zone = resolveZone(args);
          DateTime min = gcalClient.parseDateTime(getRequired(args, "time_min"), zone);
          DateTime max = gcalClient.parseDateTime(getRequired(args, "time_max"), zone);
          if (min == null || max == null) {
            return "invalid time";
          }
          if (!args.has("calendars") || !args.get("calendars").isArray()) {
            return "missing calendars";
          }
          List<FreeBusyRequestItem> items = new ArrayList<>();
          for (var node : args.get("calendars")) {
            if (node != null && node.isTextual()) {
              items.add(new FreeBusyRequestItem().setId(node.asText()));
            }
          }
          if (items.isEmpty()) {
            return "missing calendars";
          }
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            FreeBusyRequest request = new FreeBusyRequest();
            request.setTimeMin(min);
            request.setTimeMax(max);
            request.setItems(items);
            String timezone = getOptionalText(args, "timezone");
            if (timezone != null && !timezone.isBlank()) {
              request.setTimeZone(timezone);
            }
            FreeBusyResponse response = client.freebusy().query(request).execute();
            return gcalClient.mapper().writeValueAsString(response);
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
