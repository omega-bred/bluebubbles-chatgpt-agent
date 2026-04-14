package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentResponseHelper {
  private static final Pattern TEXT_TOOL_CALL_PATTERN =
      Pattern.compile("(?im)^\\s*call:([a-zA-Z0-9_\\-.]+)\\s*\\{(.*)}\\s*$");

  private AgentResponseHelper() {}

  public static List<ResponseFunctionToolCall> extractFunctionCalls(Response response) {
    if (response == null) {
      return List.of();
    }
    List<ResponseFunctionToolCall> calls = new ArrayList<>();
    if (response.output() != null) {
      for (ResponseOutputItem item : response.output()) {
        if (item.functionCall().isPresent()) {
          calls.add(item.functionCall().get());
        }
      }
    }
    if (!calls.isEmpty()) {
      return calls;
    }
    String text = extractResponseText(response);
    if (text.isBlank()) {
      return List.of();
    }
    return parseTextFunctionCalls(text);
  }

  public static List<ResponseInputItem> extractToolContextItems(
      Response response, List<ResponseFunctionToolCall> toolCalls) {
    if (response == null) {
      return List.of();
    }
    List<ResponseInputItem> items = new ArrayList<>();
    boolean addedFunctionCall = false;
    if (response.output() != null) {
      for (ResponseOutputItem item : response.output()) {
        if (item.reasoning().isPresent()) {
          items.add(ResponseInputItem.ofReasoning(item.reasoning().get()));
        }
        if (item.functionCall().isPresent()) {
          items.add(ResponseInputItem.ofFunctionCall(item.functionCall().get()));
          addedFunctionCall = true;
        }
      }
    }
    if (!addedFunctionCall && toolCalls != null && !toolCalls.isEmpty()) {
      for (ResponseFunctionToolCall toolCall : toolCalls) {
        items.add(ResponseInputItem.ofFunctionCall(toolCall));
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
        if (content.isOutputText() && content.asOutputText().isValid()) {
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

  static List<ResponseFunctionToolCall> parseTextFunctionCalls(String text) {
    List<ResponseFunctionToolCall> calls = new ArrayList<>();
    String[] lines = text.split("\\R");
    for (String line : lines) {
      Matcher matcher = TEXT_TOOL_CALL_PATTERN.matcher(line);
      if (!matcher.matches()) {
        continue;
      }
      String toolName = matcher.group(1).trim();
      String rawArgs = matcher.group(2).trim();
      Map<String, String> args = parseToolArgs(rawArgs);
      calls.add(
          ResponseFunctionToolCall.builder()
              .callId("text-call-" + UUID.randomUUID())
              .name(toolName)
              .arguments(toJsonObject(args))
              .build());
    }
    return calls;
  }

  private static Map<String, String> parseToolArgs(String rawArgs) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawArgs == null || rawArgs.isBlank()) {
      return values;
    }
    String[] pairs = rawArgs.split("\\s*,\\s*");
    for (String pair : pairs) {
      String[] parts = pair.split("\\s*:\\s*", 2);
      if (parts.length != 2) {
        continue;
      }
      String key = parts[0].trim();
      String value = stripOptionalQuotes(parts[1].trim());
      if (!key.isBlank()) {
        values.put(key, value);
      }
    }
    return values;
  }

  private static String stripOptionalQuotes(String value) {
    if (value.length() < 2) {
      return value;
    }
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String toJsonObject(Map<String, String> args) {
    StringBuilder builder = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : args.entrySet()) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      builder.append('"').append(escapeJson(entry.getKey())).append('"').append(':');
      builder.append('"').append(escapeJson(entry.getValue())).append('"');
    }
    builder.append('}');
    return builder.toString();
  }

  private static String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
