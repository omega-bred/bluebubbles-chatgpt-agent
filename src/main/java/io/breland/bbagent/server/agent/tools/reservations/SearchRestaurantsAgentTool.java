package io.breland.bbagent.server.agent.tools.reservations;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;

public class SearchRestaurantsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "search_restaurants";

  private final RestaurantReservationGateway reservationGateway;

  public SearchRestaurantsAgentTool(RestaurantReservationGateway reservationGateway) {
    this.reservationGateway = reservationGateway;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search restaurants for reservations on OpenTable and Resy. Returns API-backed OpenTable results when configured and safe provider handoff links otherwise. Use this before availability or booking when the restaurant/provider id is unknown.",
        jsonSchema(RestaurantReservationModels.SearchRequest.class),
        false,
        (context, args) -> {
          RestaurantReservationModels.SearchRequest request =
              context
                  .getMapper()
                  .convertValue(args, RestaurantReservationModels.SearchRequest.class);
          return ToolJson.stringify(
              context.getMapper(),
              reservationGateway.searchRestaurants(request),
              "{\"status\":\"error\"}");
        });
  }
}
