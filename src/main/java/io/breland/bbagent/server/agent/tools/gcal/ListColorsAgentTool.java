package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.calendar.model.Colors;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class ListColorsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "list_colors";

  @Schema(description = "List available calendar colors.")
  public record ListColorsRequest(
      @Schema(description = "Account key to query.") @JsonProperty("account_key")
          String accountKey) {}

  public ListColorsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List available calendar colors.",
        jsonSchema(ListColorsRequest.class),
        false,
        (context, args) -> {
          ListColorsRequest request =
              context.getMapper().convertValue(args, ListColorsRequest.class);
          return withCalendar(
              context,
              request.accountKey(),
              (client, accountKey) -> {
                Colors colors = client.colors().get().execute();
                return toJson(colors);
              });
        });
  }
}
