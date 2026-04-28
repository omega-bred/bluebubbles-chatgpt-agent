package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ResponseInputMessages {

  private ResponseInputMessages() {}

  static List<ResponseInputItem> squashDeveloperMessagesIntoSystem(
      List<ResponseInputItem> inputItems) {
    if (inputItems == null || inputItems.isEmpty()) {
      return inputItems;
    }
    List<String> developerTexts = new ArrayList<>();
    List<ResponseInputItem> squashedItems = new ArrayList<>();
    int systemIndex = -1;
    for (ResponseInputItem item : inputItems) {
      if (isDeveloperInputMessage(item)) {
        developerTexts.add(extractInputMessageText(item).orElseGet(item::toString));
        continue;
      }
      if (systemIndex < 0 && isSystemInputMessage(item)) {
        systemIndex = squashedItems.size();
      }
      squashedItems.add(item);
    }
    String developerText = joinedText(developerTexts);
    if (developerText.isBlank()) {
      return squashedItems;
    }
    if (systemIndex >= 0) {
      ResponseInputItem systemItem = squashedItems.get(systemIndex);
      squashedItems.set(systemIndex, mergeSystemInputMessage(systemItem, developerText));
    } else {
      squashedItems.add(0, systemInputItem(developerText));
    }
    return squashedItems;
  }

  private static boolean isDeveloperInputMessage(ResponseInputItem item) {
    return "developer".equals(inputMessageRole(item).orElse(null));
  }

  private static boolean isSystemInputMessage(ResponseInputItem item) {
    return "system".equals(inputMessageRole(item).orElse(null));
  }

  private static Optional<String> inputMessageRole(ResponseInputItem item) {
    if (item == null) {
      return Optional.empty();
    }
    if (item.isEasyInputMessage()) {
      return Optional.ofNullable(item.asEasyInputMessage().role().asString());
    }
    if (item.isMessage()) {
      return Optional.ofNullable(item.asMessage().role().asString());
    }
    return responseInputItemJson(item).map(node -> node.path("role").asText(null));
  }

  private static Optional<JsonNode> responseInputItemJson(ResponseInputItem item) {
    if (item == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(JsonValue.from(item).convert(JsonNode.class));
    } catch (RuntimeException e) {
      log.debug("Unable to inspect response input item JSON while squashing roles", e);
      return Optional.empty();
    }
  }

  private static ResponseInputItem mergeSystemInputMessage(
      ResponseInputItem systemItem, String developerText) {
    String systemText = extractInputMessageText(systemItem).orElse("");
    String mergedText = joinedText(List.of(systemText, developerText));
    if (systemItem.isMessage()) {
      ResponseInputItem.Message message =
          systemItem.asMessage().toBuilder().content(inputTextContentList(mergedText)).build();
      return ResponseInputItem.ofMessage(message);
    }
    if (systemItem.isEasyInputMessage()) {
      EasyInputMessage message =
          systemItem.asEasyInputMessage().toBuilder().content(mergedText).build();
      return ResponseInputItem.ofEasyInputMessage(message);
    }
    return systemInputItem(mergedText);
  }

  private static ResponseInputItem systemInputItem(String text) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(EasyInputMessage.Role.SYSTEM).content(text).build());
  }

  private static Optional<String> extractInputMessageText(ResponseInputItem item) {
    if (item == null) {
      return Optional.empty();
    }
    if (item.isEasyInputMessage()) {
      return extractEasyInputMessageText(item.asEasyInputMessage());
    }
    if (item.isMessage()) {
      return extractInputContentText(item.asMessage().content());
    }
    return responseInputItemJson(item).flatMap(ResponseInputMessages::extractInputMessageJsonText);
  }

  private static Optional<String> extractEasyInputMessageText(EasyInputMessage message) {
    if (message == null) {
      return Optional.empty();
    }
    EasyInputMessage.Content content = message.content();
    if (content.isTextInput()) {
      return Optional.ofNullable(content.asTextInput());
    }
    if (content.isResponseInputMessageContentList()) {
      return extractInputContentText(content.asResponseInputMessageContentList());
    }
    return Optional.empty();
  }

  private static Optional<String> extractInputContentText(List<ResponseInputContent> content) {
    if (content == null || content.isEmpty()) {
      return Optional.empty();
    }
    String text =
        content.stream()
            .filter(ResponseInputContent::isInputText)
            .map(ResponseInputContent::asInputText)
            .map(ResponseInputText::text)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static Optional<String> extractInputMessageJsonText(JsonNode node) {
    if (node == null || !node.has("content")) {
      return Optional.empty();
    }
    JsonNode content = node.get("content");
    if (content.isTextual()) {
      return Optional.of(content.asText());
    }
    if (!content.isArray()) {
      return Optional.empty();
    }
    List<String> textParts = new ArrayList<>();
    for (JsonNode item : content) {
      JsonNode text = item.get("text");
      if (text != null && text.isTextual()) {
        textParts.add(text.asText());
      }
    }
    String text = String.join("\n", textParts);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static List<ResponseInputContent> inputTextContentList(String text) {
    return List.of(
        ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build()));
  }

  private static String joinedText(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return "";
    }
    return texts.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(text -> !text.isBlank())
        .collect(Collectors.joining("\n\n"));
  }
}
