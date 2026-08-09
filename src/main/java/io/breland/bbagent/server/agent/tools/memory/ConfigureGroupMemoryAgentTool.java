package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService.GroupMemoryUpdateResult;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.StringUtils;

public class ConfigureGroupMemoryAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "configure_group_memory";

  private final ConversationMemorySettingsService settingsService;

  @Schema(description = "Enable or disable prospective collective memory for the current group.")
  public record ConfigureGroupMemoryRequest(
      @Schema(
              description = "True to enable prospective group memory; false to disable it.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          boolean enabled) {}

  public ConfigureGroupMemoryAgentTool(ConversationMemorySettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Enable or disable collective memory for the current group only after an explicit request"
            + " in that group. Enabling starts prospectively and posts a visible group notice.",
        jsonSchema(ConfigureGroupMemoryRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || !message.isGroup()) {
            return "group memory is only available in a group conversation";
          }
          String accountId = context.accountId();
          if (StringUtils.isBlank(accountId)) {
            return "a linked account is required";
          }
          ConfigureGroupMemoryRequest request =
              context.getMapper().convertValue(args, ConfigureGroupMemoryRequest.class);
          GroupMemoryUpdateResult result =
              settingsService.tryUpdateGroupMemory(
                  accountId, message.chatGuid(), request.enabled());
          return result.message();
        });
  }
}
