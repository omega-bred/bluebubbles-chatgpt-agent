package io.breland.bbagent.server.agent.tools.assistant;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class AssistantResponsivenessAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "assistant_responsiveness_tool";

  @Schema(description = "Update assistant responsiveness for the current conversation.")
  public record AssistantResponsivenessRequest(
      @Schema(
              description = "Desired responsiveness level.",
              allowableValues = {"less_responsive", "more_responsive", "default"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          Responsiveness responsiveness) {}

  public enum Responsiveness {
    @JsonProperty("less_responsive")
    LESS_RESPONSIVE,
    @JsonProperty("more_responsive")
    MORE_RESPONSIVE,
    @JsonProperty("default")
    DEFAULT
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update the assistant responsiveness for this conversation. Use less_responsive to reduce participation or more_responsive to be more active. Use default to reset.",
        jsonSchema(AssistantResponsivenessRequest.class),
        false,
        (context, args) -> {
          AssistantResponsivenessRequest request =
              context.getMapper().convertValue(args, AssistantResponsivenessRequest.class);
          if (request.responsiveness() == null) {
            return "missing responsiveness";
          }
          BBMessageAgent.AssistantResponsiveness resolved =
              resolveVerbosity(request.responsiveness());
          if (resolved == null) {
            return "invalid responsiveness";
          }
          context.setAssistantResponsiveness(resolved);
          return "updated to " + resolved.name().toLowerCase();
        });
  }

  private BBMessageAgent.AssistantResponsiveness resolveVerbosity(Responsiveness responsiveness) {
    if (responsiveness == null) {
      return null;
    }
    return switch (responsiveness) {
      case LESS_RESPONSIVE -> BBMessageAgent.AssistantResponsiveness.LESS_RESPONSIVE;
      case MORE_RESPONSIVE -> BBMessageAgent.AssistantResponsiveness.MORE_RESPONSIVE;
      case DEFAULT -> BBMessageAgent.AssistantResponsiveness.DEFAULT;
    };
  }
}
