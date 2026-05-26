package io.breland.bbagent.server.agent.tools.model;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;

public class SetPreferredModelAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "set_preferred_model";

  private final ModelAccessService modelAccessService;

  @Schema(description = "Change the current premium account's preferred assistant model.")
  public record SetPreferredModelRequest(
      @Schema(
              description = "Model key to use for future assistant replies on the current account.",
              allowableValues = {"chatgpt", "claude", "gemini"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("model")
          String model) {}

  public SetPreferredModelAgentTool(ModelAccessService modelAccessService) {
    this.modelAccessService = modelAccessService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Change the preferred assistant model for the current chat account. Use only when the user"
            + " explicitly asks to switch, change, use, or set their model to ChatGPT, Claude, or"
            + " Gemini. Model switching is premium-only.",
        jsonSchema(SetPreferredModelRequest.class),
        false,
        (context, args) -> {
          SetPreferredModelRequest request =
              context.getMapper().convertValue(args, SetPreferredModelRequest.class);
          try {
            ModelAccessService.ModelSelectionResult result =
                modelAccessService.selectModel(context.message(), request.model());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("changed", result.changed());
            response.put("account_id", result.modelAccess().accountId());
            response.put("current_model", result.modelAccess().currentModelKey());
            response.put("current_model_label", result.modelAccess().currentModelLabel());
            response.put("provider", result.modelAccess().provider());
            response.put("is_premium", result.modelAccess().premium());
            response.put("user_facing_text", result.message());
            return ToolJson.stringify(
                context.getMapper(), response, "error: unable to encode model selection");
          } catch (ResponseStatusException e) {
            return "error: " + e.getReason();
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}
