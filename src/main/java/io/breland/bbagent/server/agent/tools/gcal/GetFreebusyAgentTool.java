package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class GetFreebusyAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "get_freebusy";

  @Schema(description = "Get free/busy information for calendars.")
  public record GetFreebusyRequest(
      @Schema(description = "Account key to use.") @JsonProperty("account_key") String accountKey,
      @Schema(description = "Start of the time range.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("time_min")
          String timeMin,
      @Schema(description = "End of the time range.", requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("time_max")
          String timeMax,
      @Schema(description = "Timezone to interpret date/time strings.") String timezone,
      @Schema(description = "Calendar IDs to check.", requiredMode = Schema.RequiredMode.REQUIRED)
          List<String> calendars) {}

  public GetFreebusyAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get free/busy information for calendars.",
        jsonSchema(GetFreebusyRequest.class),
        false,
        (context, args) -> {
          GetFreebusyRequest request =
              context.getMapper().convertValue(args, GetFreebusyRequest.class);
          return withCalendar(
              context,
              request.accountKey(),
              (client, accountKey) -> {
                ZoneId zone = resolveZone(request.timezone());
                if (isBlank(request.timeMin())) {
                  return "missing time_min";
                }
                if (isBlank(request.timeMax())) {
                  return "missing time_max";
                }
                DateTime min = gcalClient.parseDateTime(request.timeMin(), zone);
                DateTime max = gcalClient.parseDateTime(request.timeMax(), zone);
                if (min == null || max == null) {
                  return "invalid time";
                }
                List<String> calendarIds = request.calendars();
                if (calendarIds == null || calendarIds.isEmpty()) {
                  return "missing calendars";
                }
                List<FreeBusyRequestItem> items = new ArrayList<>();
                for (String calendarId : calendarIds) {
                  if (!isBlank(calendarId)) {
                    items.add(new FreeBusyRequestItem().setId(calendarId));
                  }
                }
                if (items.isEmpty()) {
                  return "missing calendars";
                }
                FreeBusyRequest gRequest = new FreeBusyRequest();
                gRequest.setTimeMin(min);
                gRequest.setTimeMax(max);
                gRequest.setItems(items);
                String timezone = request.timezone();
                if (!isBlank(timezone)) {
                  gRequest.setTimeZone(timezone);
                }
                FreeBusyResponse response = client.freebusy().query(gRequest).execute();
                return toJson(response);
              });
        });
  }
}
