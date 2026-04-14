package io.breland.bbagent.server.agent.tools.workflowcallback;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class CreateWorkflowCallbackAgentTool implements ToolProvider {
  private final WorkflowCallbackService callbackService;

  @Schema(description = "Create a signed callback URL for long-running async work.")
  public record CreateWorkflowCallbackRequest(
      @Schema(
              description = "Short description of the async work that will call this callback.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String purpose,
      @JsonProperty("resume_instructions")
          @Schema(
              description =
                  "Instructions for the assistant when the callback arrives. Include how to notify the user.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String resumeInstructions,
      @JsonProperty("expires_in_seconds")
          @Schema(description = "Optional callback lifetime in seconds. Defaults to 24 hours.")
          Long expiresInSeconds) {}

  public CreateWorkflowCallbackAgentTool(WorkflowCallbackService callbackService) {
    this.callbackService = callbackService;
  }

  public AgentTool getTool() {
    return new AgentTool(
        WorkflowCallbackService.TOOL_NAME,
        "Create a signed Standard Webhooks callback URL for long-running async work. "
            + "Use before starting a Coder task, workspace build, deploy, test run, or other external work that should wake the agent when complete. "
            + "Inject the returned callback_instructions into the external task prompt. Do not reveal the signing_secret to the user.",
        jsonSchema(CreateWorkflowCallbackRequest.class),
        false,
        (context, args) -> {
          CreateWorkflowCallbackRequest request =
              context.getMapper().convertValue(args, CreateWorkflowCallbackRequest.class);
          Duration expiresIn =
              request.expiresInSeconds() == null
                  ? null
                  : Duration.ofSeconds(request.expiresInSeconds());
          try {
            WorkflowCallbackService.CreatedCallback callback =
                callbackService.createCallback(
                    context.message(), request.purpose(), request.resumeInstructions(), expiresIn);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("callback_id", callback.callbackId());
            response.put("callback_url", callback.callbackUrl());
            response.put("signing_secret", callback.signingSecret());
            response.put("expires_at", callback.expiresAt().toString());
            response.put("callback_instructions", callback.callbackInstructions());
            return context.getMapper().writeValueAsString(response);
          } catch (Exception e) {
            return "failed to create workflow callback: " + e.getMessage();
          }
        });
  }
}
