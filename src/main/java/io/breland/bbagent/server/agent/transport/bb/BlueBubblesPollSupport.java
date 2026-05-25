package io.breland.bbagent.server.agent.transport.bb;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    return normalizeMessageGuid(
        firstNonBlank(
            message.associatedMessageGuid(),
            message.replyToGuid(),
            message.threadOriginatorGuid(),
            message.messageGuid()));
  }

  public static String normalizeMessageGuid(String messageGuid) {
    if (messageGuid == null) {
      return null;
    }
    String normalized = messageGuid.trim();
    int slashIndex = normalized.indexOf('/');
    if (normalized.startsWith("p:") && slashIndex >= 0 && slashIndex < normalized.length() - 1) {
      normalized = normalized.substring(slashIndex + 1).trim();
    }
    return normalized.isBlank() ? null : normalized;
  }

  public static String fallbackPollNotification(IncomingMessage trigger, String pollMessageGuid) {
    String guid = pollMessageGuid == null ? "unknown" : pollMessageGuid;
    String triggerGuid = trigger == null ? "unknown" : trigger.messageGuid();
    return "Poll update: A poll was updated, but I couldn't load the current poll state.\n"
        + "Poll update notification for poll "
        + guid
        + ". The detailed poll state could not be loaded. Trigger message GUID: "
        + triggerGuid
        + ". Reply with a concise poll update for the chat.";
  }

  public static String formatPollNotification(
      IncomingMessage trigger, String pollMessageGuid, JsonNode poll) {
    StringBuilder text = new StringBuilder();
    boolean associatedUpdate =
        trigger != null
            && pollMessageGuid != null
            && !pollMessageGuid.equals(trigger.messageGuid())
            && firstNonBlank(trigger.associatedMessageGuid(), trigger.replyToGuid()) != null;
    text.append("Poll update: ").append(userVisiblePollNotification(poll)).append('\n');
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
    text.append(
        "Reply with a concise poll update for the chat using the Poll update summary above.");
    return text.toString();
  }

  public static String fallbackUserVisiblePollNotification(IncomingMessage message) {
    if (message == null || !isPollBundle(message.balloonBundleId())) {
      return null;
    }
    String text = message.text();
    if (text == null || text.isBlank() || !text.startsWith("Poll update: ")) {
      return null;
    }
    int newline = text.indexOf('\n');
    String firstLine = newline >= 0 ? text.substring(0, newline) : text;
    return firstLine.trim();
  }

  public static String formatPollReadResult(
      JsonNode poll, String readMessageGuid, List<String> attemptedMessageGuids) {
    StringBuilder text = new StringBuilder();
    text.append("Poll read result\n");
    String resolvedGuid = textValue(poll, "messageGuid");
    if (resolvedGuid != null) {
      text.append("Resolved poll message GUID: ").append(resolvedGuid).append('\n');
    }
    if (readMessageGuid != null && !readMessageGuid.isBlank()) {
      text.append("Read using message GUID: ").append(readMessageGuid).append('\n');
    }
    if (attemptedMessageGuids != null && !attemptedMessageGuids.isEmpty()) {
      text.append("Attempted message GUIDs: ")
          .append(String.join(", ", attemptedMessageGuids))
          .append('\n');
    }
    String title = textValue(poll, "title");
    text.append("Title: ").append(title == null ? "(untitled)" : title).append('\n');

    JsonNode options = poll == null ? null : poll.get("options");
    Map<String, List<String>> votersByOptionId = votersByOptionIdentifier(poll);
    if (options != null && options.isArray() && !options.isEmpty()) {
      text.append("Options and votes:\n");
      int index = 1;
      for (JsonNode option : options) {
        String id = textValue(option, "optionIdentifier");
        String label = textValue(option, "text");
        if (label == null) {
          label = id == null ? "(unlabeled option)" : "(unlabeled option " + id + ")";
        }
        List<String> voters = id == null ? List.of() : votersByOptionId.getOrDefault(id, List.of());
        text.append(index++)
            .append(". ")
            .append(label)
            .append(" - ")
            .append(voters.size())
            .append(voters.size() == 1 ? " vote" : " votes");
        if (!voters.isEmpty()) {
          text.append(" (").append(String.join(", ", voters)).append(")");
        }
        if (id != null) {
          text.append(" [optionIdentifier=").append(id).append(']');
        }
        text.append('\n');
      }
    } else {
      text.append("Options and votes: unavailable\n");
    }

    String responses = pollResponsesSummary(poll);
    text.append("Responses: ").append(responses == null ? "none" : responses).append('\n');
    text.append("Raw poll JSON: ").append(poll == null ? "null" : poll.toString());
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

  private static String userVisiblePollNotification(JsonNode poll) {
    String title = textValue(poll, "title");
    String subject = title == null ? "Poll update" : "Poll \"" + title + "\" update";
    String responses = pollResponsesSummary(poll);
    StringBuilder summary = new StringBuilder(subject);
    if (responses != null) {
      summary.append(": ").append(responses);
    } else {
      summary.append(": no votes recorded");
    }
    String tally = pollTallySummary(poll);
    if (tally != null) {
      summary.append(". Tally: ").append(tally);
    }
    String options = pollOptionLabelsSummary(poll);
    if (responses == null && options != null) {
      summary.append(". Options: ").append(options);
    }
    summary.append('.');
    return summary.toString();
  }

  private static String pollTallySummary(JsonNode poll) {
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return null;
    }
    Map<String, Integer> countsByOptionId = new LinkedHashMap<>();
    JsonNode responses = poll.get("responses");
    if (responses != null && responses.isArray()) {
      for (JsonNode response : responses) {
        JsonNode optionIdentifiers = response.get("optionIdentifiers");
        if (optionIdentifiers == null || !optionIdentifiers.isArray()) {
          continue;
        }
        for (JsonNode optionIdentifier : optionIdentifiers) {
          String id = optionIdentifier.asText(null);
          if (id != null) {
            countsByOptionId.merge(id, 1, Integer::sum);
          }
        }
      }
    }
    StringJoiner joiner = new StringJoiner(", ");
    for (JsonNode option : options) {
      String id = textValue(option, "optionIdentifier");
      String label = textValue(option, "text");
      if (label == null) {
        label = id;
      }
      if (label == null) {
        continue;
      }
      int count = id == null ? 0 : countsByOptionId.getOrDefault(id, 0);
      joiner.add(label + " " + count + (count == 1 ? " vote" : " votes"));
    }
    String value = joiner.toString();
    return value.isBlank() ? null : value;
  }

  private static String pollOptionLabelsSummary(JsonNode poll) {
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return null;
    }
    StringJoiner joiner = new StringJoiner(", ");
    for (JsonNode option : options) {
      String label = textValue(option, "text");
      if (label != null) {
        joiner.add(label);
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

  private static Map<String, List<String>> votersByOptionIdentifier(JsonNode poll) {
    Map<String, List<String>> votersByOptionId = new LinkedHashMap<>();
    JsonNode responses = poll == null ? null : poll.get("responses");
    if (responses == null || !responses.isArray()) {
      return votersByOptionId;
    }
    for (JsonNode response : responses) {
      String handle = textValue(response, "handle");
      JsonNode optionIdentifiers = response.get("optionIdentifiers");
      if (handle == null || optionIdentifiers == null || !optionIdentifiers.isArray()) {
        continue;
      }
      for (JsonNode optionIdentifier : optionIdentifiers) {
        String id = optionIdentifier.asText(null);
        if (id == null) {
          continue;
        }
        votersByOptionId.computeIfAbsent(id, ignored -> new ArrayList<>()).add(handle);
      }
    }
    return votersByOptionId;
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
