package io.breland.bbagent.server.agent.tools.reservations;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;

public class CheckRestaurantAvailabilityAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "check_restaurant_availability";

  private final RestaurantReservationGateway reservationGateway;

  public CheckRestaurantAvailabilityAgentTool(RestaurantReservationGateway reservationGateway) {
    this.reservationGateway = reservationGateway;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check restaurant reservation availability. OpenTable availability uses the partner API when credentials and an OpenTable rid are available. Resy returns a provider handoff link because direct Resy booking API access is not configured.",
        jsonSchema(RestaurantReservationModels.AvailabilityRequest.class),
        false,
        (context, args) -> {
          RestaurantReservationModels.AvailabilityRequest request =
              context
                  .getMapper()
                  .convertValue(args, RestaurantReservationModels.AvailabilityRequest.class);
          return ToolJson.stringify(
              context.getMapper(),
              reservationGateway.checkAvailability(request),
              "{\"status\":\"error\"}");
        });
  }
}
