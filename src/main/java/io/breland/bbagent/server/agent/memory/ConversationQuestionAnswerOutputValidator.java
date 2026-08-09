package io.breland.bbagent.server.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

final class ConversationQuestionAnswerOutputValidator {
  private static final int MIN_SENSITIVE_GUID_LENGTH = 8;
  private static final int MAX_SAFE_VERBATIM_TOKENS = 7;
  private static final int MIN_UNSAFE_VERBATIM_CHARACTERS = 40;
  private static final int ALWAYS_UNSAFE_VERBATIM_CHARACTERS = 120;

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,63}(?![a-z0-9._%+-])");
  private static final Pattern URL =
      Pattern.compile(
          "(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}(?:/\\S*)?");
  private static final Pattern PHONE_CANDIDATE =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s().-]{5,}\\d(?![\\p{L}\\p{N}])");
  private static final Pattern INSTRUCTION_LEAKAGE =
      Pattern.compile(
          "(?i)(?:"
              + "\\b(?:ignore|disregard|override|forget)\\s+(?:all\\s+)?(?:prior|previous|above|system|developer|user)?\\s*(?:instructions?|messages?|prompts?)\\b"
              + "|\\b(?:system|developer)\\s+(?:prompt|message|instructions?)\\b"
              + "|\\b(?:prompt\\s+injection|jailbreak|tool\\s+call)\\b"
              + "|\\breveal\\s+(?:the\\s+)?(?:system|developer)\\s+prompt\\b"
              + "|(?:^|\\R)\\s*(?:system|developer|assistant|user)\\s*:"
              + ")");
  private static final Pattern TOKEN =
      Pattern.compile("[\\p{L}\\p{N}]+(?:[,.'/-][\\p{L}\\p{N}]+)*");

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> submittedMessageGuids, List<String> submittedSourceTexts) {
    if (!isSafe(answer, submittedMessageGuids, submittedSourceTexts)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> submittedMessageGuids, List<String> submittedSourceTexts) {
    String normalizedAnswer = StringUtils.trimToNull(answer);
    if (normalizedAnswer == null
        || containsSubmittedGuid(normalizedAnswer, submittedMessageGuids)
        || EMAIL.matcher(normalizedAnswer).find()
        || URL.matcher(normalizedAnswer).find()
        || containsPhone(normalizedAnswer)
        || INSTRUCTION_LEAKAGE.matcher(normalizedAnswer).find()) {
      return false;
    }
    List<String> answerTokens = tokens(normalizedAnswer);
    for (String sourceText :
        submittedSourceTexts == null ? List.<String>of() : submittedSourceTexts) {
      if (unsafeVerbatimOverlap(answerTokens, tokens(sourceText))) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsSubmittedGuid(String answer, Set<String> submittedMessageGuids) {
    String foldedAnswer = answer.toLowerCase(Locale.ROOT);
    for (String guid : submittedMessageGuids == null ? Set.<String>of() : submittedMessageGuids) {
      String normalizedGuid = StringUtils.trimToNull(guid);
      if (normalizedGuid != null
          && normalizedGuid.length() >= MIN_SENSITIVE_GUID_LENGTH
          && foldedAnswer.contains(normalizedGuid.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsPhone(String answer) {
    Matcher matcher = PHONE_CANDIDATE.matcher(answer);
    while (matcher.find()) {
      long digits = matcher.group().codePoints().filter(Character::isDigit).count();
      if (digits >= 7) {
        return true;
      }
    }
    return false;
  }

  private static boolean unsafeVerbatimOverlap(
      List<String> answerTokens, List<String> sourceTokens) {
    if (answerTokens.isEmpty() || sourceTokens.isEmpty()) {
      return false;
    }
    int[][] overlap = new int[2][sourceTokens.size() + 1];
    int currentRow = 0;
    for (int answerIndex = 1; answerIndex <= answerTokens.size(); answerIndex++) {
      currentRow ^= 1;
      int previousRow = currentRow ^ 1;
      java.util.Arrays.fill(overlap[currentRow], 0);
      for (int sourceIndex = 1; sourceIndex <= sourceTokens.size(); sourceIndex++) {
        if (!answerTokens
            .get(answerIndex - 1)
            .equalsIgnoreCase(sourceTokens.get(sourceIndex - 1))) {
          continue;
        }
        int matchedTokens = overlap[previousRow][sourceIndex - 1] + 1;
        overlap[currentRow][sourceIndex] = matchedTokens;
        int matchedCharacters =
            contiguousCharacters(answerTokens, answerIndex - matchedTokens, answerIndex);
        if ((matchedTokens > MAX_SAFE_VERBATIM_TOKENS
                && matchedCharacters >= MIN_UNSAFE_VERBATIM_CHARACTERS)
            || matchedCharacters >= ALWAYS_UNSAFE_VERBATIM_CHARACTERS) {
          return true;
        }
      }
    }
    return false;
  }

  private static int contiguousCharacters(List<String> values, int from, int to) {
    int characters = Math.max(0, to - from - 1);
    for (int index = from; index < to; index++) {
      characters += values.get(index).length();
    }
    return characters;
  }

  private static List<String> tokens(String value) {
    if (StringUtils.isBlank(value)) {
      return List.of();
    }
    List<String> tokens = new ArrayList<>();
    Matcher matcher = TOKEN.matcher(value);
    while (matcher.find()) {
      tokens.add(matcher.group());
    }
    return List.copyOf(tokens);
  }
}
