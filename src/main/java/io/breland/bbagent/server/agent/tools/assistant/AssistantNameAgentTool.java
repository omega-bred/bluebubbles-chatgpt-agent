package io.breland.bbagent.server.agent.tools.assistant;

import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class AssistantNameAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "assistant_name_tool";

  @Schema(description = "Request to store, set, or forget a user's name.")
  public record AssistantNameRequest(
      @Schema(
              description = "Action to take. Use store/set to save a name or forget to remove it.",
              allowableValues = {"store", "set", "forget"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          Action action,
      @Schema(description = "Name to store when action is store or set.") String name) {}

  public enum Action {
    @JsonProperty("store")
    STORE,
    @JsonProperty("set")
    SET,
    @JsonProperty("forget")
    FORGET
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Store, set, or forget a user's name for global use across chats. Only store a name after the user explicitly agrees and mention you will use it across any chats the user is present in.",
        jsonSchema(AssistantNameRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.sender() == null || message.sender().isBlank()) {
            return "no sender";
          }
          AssistantNameRequest request =
              context.getMapper().convertValue(args, AssistantNameRequest.class);
          Action action = request.action() == null ? Action.STORE : request.action();
          if (action == Action.FORGET) {
            context.removeGlobalNameForSender(message.sender());
            return "removed name for sender";
          }
          String name = request.name();
          if (name == null || name.isBlank()) {
            return "missing name";
          }
          context.setGlobalNameForSender(message.sender(), name);
          return "stored name for sender";
        });
  }
}
