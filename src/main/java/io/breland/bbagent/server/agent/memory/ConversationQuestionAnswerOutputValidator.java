package io.breland.bbagent.server.agent.memory;

import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

final class ConversationQuestionAnswerOutputValidator {
  private static final int MAX_ANSWER_CHARACTERS = 4_000;

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> forbiddenMessageGuids, Set<String> opaqueAliases) {
    if (!isSafe(answer, forbiddenMessageGuids, opaqueAliases)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenMessageGuids, Set<String> opaqueAliases) {
    try {
      String normalizedAnswer = StringUtils.trimToNull(answer);
      if (normalizedAnswer == null
          || normalizedAnswer.length() > MAX_ANSWER_CHARACTERS
          || forbiddenMessageGuids == null
          || opaqueAliases == null) {
        return false;
      }
      String foldedAnswer = normalizedAnswer.toLowerCase(Locale.ROOT);
      return !containsDelimitedIdentifier(foldedAnswer, forbiddenMessageGuids)
          && !containsLiteralIdentifier(foldedAnswer, opaqueAliases);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean containsDelimitedIdentifier(String foldedAnswer, Set<String> identifiers) {
    for (String identifier : identifiers) {
      String foldedIdentifier = normalizeIdentifier(identifier);
      if (foldedIdentifier == null) {
        continue;
      }
      for (int offset = foldedAnswer.indexOf(foldedIdentifier);
          offset >= 0;
          offset = foldedAnswer.indexOf(foldedIdentifier, offset + 1)) {
        int end = offset + foldedIdentifier.length();
        boolean startsAtBoundary =
            offset == 0 || !Character.isLetterOrDigit(foldedAnswer.codePointBefore(offset));
        boolean endsAtBoundary =
            end == foldedAnswer.length()
                || !Character.isLetterOrDigit(foldedAnswer.codePointAt(end));
        if (startsAtBoundary && endsAtBoundary) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsLiteralIdentifier(String foldedAnswer, Set<String> identifiers) {
    for (String identifier : identifiers) {
      String foldedIdentifier = normalizeIdentifier(identifier);
      if (foldedIdentifier != null && foldedAnswer.contains(foldedIdentifier)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeIdentifier(String identifier) {
    String normalized = StringUtils.trimToNull(identifier);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }
}
