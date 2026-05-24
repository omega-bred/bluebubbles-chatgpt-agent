package io.breland.bbagent.server.agent.transport.bb;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class BlueBubblesPollSupport {

  private static final String POLLS_BUNDLE_SUFFIX = "com.apple.messages.Polls";

  private BlueBubblesPollSupport() {}

  public static boolean isPollBundle(String bundleIdentifier) {
    return bundleIdentifier != null
        && (bundleIdentifier.equals(POLLS_BUNDLE_SUFFIX)
            || bundleIdentifier.endsWith(":" + POLLS_BUNDLE_SUFFIX)
            || bundleIdentifier.contains(POLLS_BUNDLE_SUFFIX));
  }

  public static String pollMessageGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    return firstNonBlank(
        message.associatedMessageGuid(),
        message.replyToGuid(),
        message.threadOriginatorGuid(),
        message.messageGuid());
  }

  public static String fallbackPollNotification(IncomingMessage trigger, String pollMessageGuid) {
    String guid = pollMessageGuid == null ? "unknown" : pollMessageGuid;
    String triggerGuid = trigger == null ? "unknown" : trigger.messageGuid();
    return "Poll update notification for poll "
        + guid
        + ". The detailed poll state could not be loaded. Trigger message GUID: "
        + triggerGuid
        + ". Reply only if this update needs attention.";
  }

  public static String formatPollNotification(
      IncomingMessage trigger, String pollMessageGuid, JsonNode poll) {
    StringBuilder text = new StringBuilder();
    boolean associatedUpdate =
        trigger != null
            && pollMessageGuid != null
            && !pollMessageGuid.equals(trigger.messageGuid())
            && firstNonBlank(trigger.associatedMessageGuid(), trigger.replyToGuid()) != null;
    text.append(
        associatedUpdate
            ? "Poll vote or option update notification"
            : "Poll created or updated notification");
    text.append(" for poll ").append(pollMessageGuid);
    String title = textValue(poll, "title");
    if (title != null) {
      text.append(" [title=").append(title).append("]");
    }
    text.append(". ");
    String options = pollOptionsSummary(poll);
    if (options != null) {
      text.append("Current options: ").append(options).append(". ");
    }
    String responses = pollResponsesSummary(poll);
    if (responses != null) {
      text.append("Current votes: ").append(responses).append(". ");
    } else {
      text.append("Current votes: none. ");
    }
    text.append("Trigger message GUID: ")
        .append(trigger == null ? "unknown" : trigger.messageGuid())
        .append(". ");
    text.append("Reply only if this poll update needs attention.");
    return text.toString();
  }

  private static String pollOptionsSummary(JsonNode poll) {
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return null;
    }
    StringJoiner joiner = new StringJoiner("; ");
    for (JsonNode option : options) {
      String id = textValue(option, "optionIdentifier");
      String label = textValue(option, "text");
      if (label == null) {
        label = id;
      }
      if (label != null) {
        joiner.add(id == null ? label : label + " (" + id + ")");
      }
    }
    String value = joiner.toString();
    return value.isBlank() ? null : value;
  }

  private static String pollResponsesSummary(JsonNode poll) {
    JsonNode responses = poll == null ? null : poll.get("responses");
    if (responses == null || !responses.isArray() || responses.isEmpty()) {
      return null;
    }
    Map<String, String> optionTexts = optionTextsByIdentifier(poll);
    StringJoiner joiner = new StringJoiner("; ");
    for (JsonNode response : responses) {
      String handle = textValue(response, "handle");
      JsonNode optionIdentifiers = response.get("optionIdentifiers");
      if (handle == null || optionIdentifiers == null || !optionIdentifiers.isArray()) {
        continue;
      }
      StringJoiner votes = new StringJoiner(", ");
      for (JsonNode optionIdentifier : optionIdentifiers) {
        String id = optionIdentifier.asText(null);
        if (id == null) {
          continue;
        }
        votes.add(optionTexts.getOrDefault(id, id));
      }
      String voteText = votes.toString();
      if (!voteText.isBlank()) {
        joiner.add(handle + " voted for " + voteText);
      }
    }
    String value = joiner.toString();
    return value.isBlank() ? null : value;
  }

  private static Map<String, String> optionTextsByIdentifier(JsonNode poll) {
    Map<String, String> optionTexts = new LinkedHashMap<>();
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray()) {
      return optionTexts;
    }
    for (JsonNode option : options) {
      String id = textValue(option, "optionIdentifier");
      String text = textValue(option, "text");
      if (id != null && text != null) {
        optionTexts.put(id, text);
      }
    }
    return optionTexts;
  }

  private static String textValue(JsonNode node, String field) {
    if (node == null || field == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
