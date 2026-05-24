package io.breland.bbagent.server.agent.tools.reservations;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;

public class MakeRestaurantReservationAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "make_restaurant_reservation";

  private final RestaurantReservationGateway reservationGateway;

  public MakeRestaurantReservationAgentTool(RestaurantReservationGateway reservationGateway) {
    this.reservationGateway = reservationGateway;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create a restaurant reservation. Only call after the user explicitly confirms the exact restaurant, date/time, party size, guest contact details, and provider terms acceptance. Never ask for or include raw payment-card data. OpenTable can book through the partner API when configured; Resy returns a web handoff link.",
        jsonSchema(RestaurantReservationModels.BookingRequest.class),
        false,
        (context, args) -> {
          RestaurantReservationModels.BookingRequest request =
              context
                  .getMapper()
                  .convertValue(args, RestaurantReservationModels.BookingRequest.class);
          return ToolJson.stringify(
              context.getMapper(),
              reservationGateway.makeReservation(request),
              "{\"status\":\"error\"}");
        });
  }
}
