package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.*;

public class SearchEventsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "search_events";

  @Schema(description = "Search events in a calendar within a time range.")
  public record SearchEventsRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID to search.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Search query.", requiredMode = Schema.RequiredMode.REQUIRED)
          String query,
      @Schema(description = "Lower bound (inclusive) for event start time.")
          @JsonProperty("time_min")
          String timeMin,
      @Schema(description = "Upper bound (exclusive) for event end time.") @JsonProperty("time_max")
          String timeMax,
      @Schema(description = "Maximum number of events to return.") @JsonProperty("max_results")
          Integer maxResults,
      @Schema(description = "Timezone to interpret date/time strings.") String timezone) {}

  public SearchEventsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search events in a calendar within a time range.",
        jsonSchema(SearchEventsRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          SearchEventsRequest request =
              context.getMapper().convertValue(args, SearchEventsRequest.class);
          String accountKey = resolveAccountKey(context, request.accountKey());
          if (accountKey == null || accountKey.isBlank()) {
            return "no account";
          }
          String calendarId = resolveCalendarId(request.calendarId());
          String query = request.query();
          if (query == null || query.isBlank()) {
            return "missing query";
          }
          ZoneId zone = resolveZone(request.timezone());
          DateTime min = gcalClient.parseDateTime(request.timeMin(), zone);
          DateTime max = gcalClient.parseDateTime(request.timeMax(), zone);
          try {
            Calendar client = gcalClient.getCalendarService(accountKey);
            Calendar.Events.List listCalendarRequest = client.events().list(calendarId).setQ(query);
            if (min != null) {
              listCalendarRequest.setTimeMin(min);
            }
            if (max != null) {
              listCalendarRequest.setTimeMax(max);
            }
            Optional.ofNullable(request.maxResults()).ifPresent(listCalendarRequest::setMaxResults);
            Events events = listCalendarRequest.execute();
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
