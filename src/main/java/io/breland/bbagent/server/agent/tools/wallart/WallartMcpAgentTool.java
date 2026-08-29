package io.breland.bbagent.server.agent.tools.wallart;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WallartMcpAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "showNewArt";

  private final WallartMcpClient wallartMcpClient;
  private final WallartConversationAccess conversationAccess;

  @Schema(description = "Generate and display new art on the dining room LED wall.")
  public record ShowNewArtRequest(
      @Schema(
              description = "A description of the art to generate and display.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String prompt) {}

  public WallartMcpAgentTool(
      WallartMcpClient wallartMcpClient, WallartConversationAccess conversationAccess) {
    this.wallartMcpClient = wallartMcpClient;
    this.conversationAccess = conversationAccess;
  }

  public boolean isAllowed(IncomingMessage message) {
    return conversationAccess.isAllowed(message);
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Show new art on the dining room LED wall based on a prompt through the wallart MCP"
            + " server.",
        jsonSchema(ShowNewArtRequest.class),
        false,
        (context, args) -> {
          if (!isAllowed(context.message())) {
            return "The wallart tool is not available in this conversation.";
          }
          ShowNewArtRequest request =
              context.getMapper().convertValue(args, ShowNewArtRequest.class);
          String prompt = request == null ? null : StringUtils.trimToNull(request.prompt());
          if (prompt == null) {
            return "prompt is required";
          }
          try {
            return wallartMcpClient.showNewArt(prompt);
          } catch (RuntimeException e) {
            log.warn("Wallart MCP tool call failed: {}", e.getClass().getSimpleName());
            return "The wallart MCP call failed; no new art was submitted.";
          }
        });
  }
}
