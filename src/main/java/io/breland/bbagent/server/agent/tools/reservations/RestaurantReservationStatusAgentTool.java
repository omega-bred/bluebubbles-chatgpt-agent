package io.breland.bbagent.server.agent.tools.reservations;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class RestaurantReservationStatusAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "restaurant_reservation_status";

  private final RestaurantReservationGateway reservationGateway;

  @Schema(description = "Check restaurant reservation provider status.")
  public record StatusRequest(
      @Schema(description = "Optional provider filter: opentable, resy, or all.")
          String provider) {}

  public RestaurantReservationStatusAgentTool(RestaurantReservationGateway reservationGateway) {
    this.reservationGateway = reservationGateway;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check OpenTable and Resy reservation capabilities, login handoff links, and whether API-backed search/availability/booking is configured. Use this when the user asks whether restaurant reservation login or booking is available.",
        jsonSchema(StatusRequest.class),
        false,
        (context, args) ->
            ToolJson.stringify(
                context.getMapper(), reservationGateway.capabilities(), "{\"status\":\"error\"}"));
  }
}
