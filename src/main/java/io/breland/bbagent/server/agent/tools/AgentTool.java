package io.breland.bbagent.server.agent.tools;

import com.openai.models.responses.FunctionTool;
import io.breland.bbagent.server.agent.IncomingMessage;

public record AgentTool(
    String name,
    String description,
    FunctionTool.Parameters parameters,
    boolean strict,
    ToolHandler handler) {

  public FunctionTool asFunctionTool() {
    return FunctionTool.builder()
        .name(name)
        .description(description)
        .parameters(parameters)
        .strict(strict)
        .build();
  }

  public static boolean isGroupMessage(IncomingMessage message) {
    if (message == null) {
      return false;
    }
    if (message.isGroup() != null && message.isGroup()) {
      return true;
    }
    return message.chatGuid().startsWith("iMessage;+;chat");
  }

  public static String getSenderId(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    String sender = message.sender();
    if (sender != null && !sender.isBlank()) {
      return sender;
    }
    return null;
  }

  public static String resolveUserIdOrGroupChatId(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    String sender = message.sender();
    String chatGuid = message.chatGuid();
    if (isGroupMessage(message)) {
      if (chatGuid != null && !chatGuid.isBlank()) {
        return chatGuid;
      }
    }
    if (sender != null && !sender.isBlank()) {
      return sender;
    }
    if (chatGuid != null && !chatGuid.isBlank()) {
      return "chat:" + chatGuid;
    }
    return null;
  }
}
