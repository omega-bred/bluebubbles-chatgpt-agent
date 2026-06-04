package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import org.springframework.lang.Nullable;

final class ThreadReplySupport {
  private ThreadReplySupport() {}

  static @Nullable String threadRootGuid(IncomingMessage message) {
    if (message == null || message.threadOriginatorGuid() == null) {
      return null;
    }
    return message.threadOriginatorGuid().isBlank() ? null : message.threadOriginatorGuid();
  }

  static JsonNode applySendTextReplyDefault(
      String toolName, JsonNode args, IncomingMessage message) {
    if (toolName == null || args == null || message == null) {
      return args;
    }
    if (!SendTextAgentTool.TOOL_NAME.equals(toolName)) {
      return args;
    }
    if (args.hasNonNull("selectedMessageGuid")) {
      return args;
    }
    String replyTarget = threadRootGuid(message);
    if (replyTarget == null) {
      return args;
    }
    if (!(args instanceof ObjectNode objectNode)) {
      return args;
    }
    objectNode.put("selectedMessageGuid", replyTarget);
    return objectNode;
  }
}
