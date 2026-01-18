package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class GetCurrentTimeAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "get_current_time";

  @Schema(description = "Get the current time in a specified timezone.")
  public record GetCurrentTimeRequest(
      @Schema(description = "IANA timezone ID (e.g. America/New_York).") String timezone) {}

  public GetCurrentTimeAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get the current time in a specified timezone.",
        jsonSchema(GetCurrentTimeRequest.class),
        false,
        (context, args) -> {
          GetCurrentTimeRequest request =
              context.getMapper().convertValue(args, GetCurrentTimeRequest.class);
          String timezone = request.timezone();
          ZoneId zone;
          try {
            zone =
                (timezone == null || timezone.isBlank())
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timezone);
          } catch (Exception e) {
            zone = ZoneId.systemDefault();
          }
          ZonedDateTime now = ZonedDateTime.now(zone);
          Map<String, Object> response = new LinkedHashMap<>();
          response.put("timezone", zone.getId());
          response.put("current_time", now.toString());
          try {
            return gcalClient.mapper().writeValueAsString(response);
          } catch (Exception e) {
            return response.toString();
          }
        });
  }
}
