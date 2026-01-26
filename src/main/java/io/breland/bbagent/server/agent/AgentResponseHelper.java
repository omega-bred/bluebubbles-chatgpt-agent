package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AgentResponseHelper {

  private AgentResponseHelper() {}

  public static List<ResponseFunctionToolCall> extractFunctionCalls(Response response) {
    if (response == null || response.output() == null) {
      return List.of();
    }
    List<ResponseFunctionToolCall> calls = new ArrayList<>();
    for (ResponseOutputItem item : response.output()) {
      if (item.functionCall().isPresent()) {
        calls.add(item.functionCall().get());
      }
    }
    return calls;
  }

  public static List<ResponseInputItem> extractToolContextItems(Response response) {
    if (response == null || response.output() == null) {
      return List.of();
    }
    List<ResponseInputItem> items = new ArrayList<>();
    for (ResponseOutputItem item : response.output()) {
      if (item.reasoning().isPresent()) {
        items.add(ResponseInputItem.ofReasoning(item.reasoning().get()));
      }
      if (item.functionCall().isPresent()) {
        items.add(ResponseInputItem.ofFunctionCall(item.functionCall().get()));
      }
    }
    return items;
  }

  public static String extractResponseText(Response response) {
    if (response == null || response.output() == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (ResponseOutputItem item : response.output()) {
      if (item.message().isEmpty()) {
        continue;
      }
      ResponseOutputMessage message = item.message().get();
      for (ResponseOutputMessage.Content content : message.content()) {
        if (content.isOutputText()) {
          if (builder.length() > 0) {
            builder.append(' ');
          }
          builder.append(content.asOutputText().text());
        }
      }
    }
    return builder.toString().trim();
  }

  public static String normalizeAssistantText(ObjectMapper objectMapper, String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JsonNode node = objectMapper.readTree(trimmed);
        JsonNode messageNode = node.get("message");
        if (messageNode != null && messageNode.isTextual()) {
          return messageNode.asText().trim();
        }
      } catch (Exception ignored) {
        return trimmed;
      }
    }
    return trimmed;
  }

  public static Optional<String> parseReactionText(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String trimmed = text.trim();
    if (!trimmed.startsWith("[reaction:") || !trimmed.endsWith("]")) {
      return Optional.empty();
    }
    String inner = trimmed.substring("[reaction:".length(), trimmed.length() - 1).trim();
    if (inner.isBlank()) {
      return Optional.empty();
    }
    String reaction = inner.toLowerCase(Locale.ROOT);
    if (!BBMessageAgent.SUPPORTED_REACTIONS.contains(reaction)) {
      return Optional.empty();
    }
    return Optional.of(reaction);
  }
}
