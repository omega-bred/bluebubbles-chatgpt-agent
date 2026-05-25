package io.breland.bbagent.server.agent.reactions;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MessageReactionSupport {
  public static final Set<String> SUPPORTED_REACTIONS =
      Set.of(
          "love",
          "like",
          "dislike",
          "laugh",
          "emphasize",
          "question",
          "-love",
          "-like",
          "-dislike",
          "-laugh",
          "-emphasize",
          "-question");

  private static final List<String> REACTION_PREFIXES =
      List.of(
          "reacted ", "loved ", "liked ", "disliked ", "questioned ", "emphasized ", "laughed at ");

  private MessageReactionSupport() {}

  public static boolean isReactionMessage(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    if (trimmed.isBlank()) {
      return false;
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT);
    for (String prefix : REACTION_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
