package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;
import static org.springframework.util.StringUtils.hasText;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Events;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.Optional;

public class ListEventsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "list_events";

  @Schema(description = "List events from a calendar within a time range.")
  public record ListEventsRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Calendar ID to list events for.") @JsonProperty("calendar_id")
          String calendarId,
      @Schema(description = "Lower bound (inclusive) for event start time.")
          @JsonProperty("time_min")
          String timeMin,
      @Schema(description = "Upper bound (exclusive) for event end time.") @JsonProperty("time_max")
          String timeMax,
      @Schema(description = "Maximum number of events to return.") @JsonProperty("max_results")
          Integer maxResults,
      @Schema(description = "Expand recurring events into instances.")
          @JsonProperty("single_events")
          Boolean singleEvents,
      @Schema(description = "Order results by (e.g. startTime).") @JsonProperty("order_by")
          String orderBy,
      @Schema(description = "Timezone to interpret date/time strings.") String timezone) {}

  public ListEventsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List events from a calendar within a time range.",
        jsonSchema(ListEventsRequest.class),
        false,
        (context, args) -> {
          ListEventsRequest listEventsRequest =
              context.getMapper().convertValue(args, ListEventsRequest.class);
          return withCalendar(
              context,
              listEventsRequest.accountKey(),
              (client, accountKey) -> {
                String calendarId = resolveCalendarId(listEventsRequest.calendarId());
                ZoneId zone = resolveZone(listEventsRequest.timezone());
                DateTime min = gcalClient.parseDateTime(listEventsRequest.timeMin(), zone);
                DateTime max = gcalClient.parseDateTime(listEventsRequest.timeMax(), zone);
                Calendar.Events.List request = client.events().list(calendarId);
                if (min != null) {
                  request.setTimeMin(min);
                }
                if (max != null) {
                  request.setTimeMax(max);
                }
                Optional.ofNullable(listEventsRequest.maxResults()).ifPresent(request::setMaxResults);
                Optional.ofNullable(listEventsRequest.singleEvents())
                    .ifPresent(request::setSingleEvents);
                String orderBy = listEventsRequest.orderBy();
                if (hasText(orderBy)) {
                  request.setOrderBy(orderBy);
                }
                Events events = request.execute();
                return toJson(eventSummaries(events));
              });
        });
  }
}
