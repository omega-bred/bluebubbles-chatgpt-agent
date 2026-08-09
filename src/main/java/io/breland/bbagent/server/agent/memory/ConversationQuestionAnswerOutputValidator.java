package io.breland.bbagent.server.agent.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

final class ConversationQuestionAnswerOutputValidator {
  private static final int MAX_ANSWER_CHARACTERS = 4_000;
  private static final int MAX_SOURCE_CHARACTERS = 300_000;
  private static final int MAX_SOURCE_COUNT = 5_000;
  private static final int MAX_SOURCE_TOKENS = 160_000;
  private static final int MATCH_NGRAM_TOKENS = 3;
  private static final int MAX_SAFE_VERBATIM_TOKENS = 7;
  private static final int MIN_UNSAFE_VERBATIM_CHARACTERS = 40;
  private static final int ALWAYS_UNSAFE_VERBATIM_CHARACTERS = 120;
  private static final int MAX_SHORT_MESSAGE_TOKENS = 7;
  private static final int MIN_SHORT_MESSAGE_CHARACTERS = 16;

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,63}(?![a-z0-9._%+-])");
  private static final Pattern SCHEME =
      Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]{1,20}://|(?:mailto|tel|sms):)\\S+");
  private static final Pattern WWW = Pattern.compile("(?i)\\bwww\\.\\S+");
  private static final Pattern COMMON_TLD_DOMAIN =
      Pattern.compile(
          "(?i)(?<![@a-z0-9_-])(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?\\.)+"
              + "(?:com|org|net|edu|gov|mil|io|co|uk|us|ca|de|fr|jp|au|dev|app|ai|me|info|biz|xyz)"
              + "(?::\\d{1,5})?(?:/\\S*)?");
  private static final Pattern NUMBER_CANDIDATE =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s().-]{5,}\\d(?![\\p{L}\\p{N}])");
  private static final Pattern PHONE_CONTEXT =
      Pattern.compile("(?i)\\b(?:phone|call|text|contact|mobile|telephone|tel|sms|fax|reach)\\b");
  private static final Pattern PHONE_FORMAT =
      Pattern.compile("(?:\\d{3}[- .]\\d{4}|\\d{3}[- .]\\d{3}[- .]\\d{4})");
  private static final Pattern INSTRUCTION_LEAKAGE =
      Pattern.compile(
          "(?i)(?:"
              + "\\b(?:ignore|disregard|override|forget|follow|obey)\\b.{0,48}\\b(?:instructions?|directives?|prompts?|messages?|above|below|system|developer|user|tool)\\b"
              + "|\\b(?:instructions?|directives?|prompts?)\\s+(?:above|below)\\b"
              + "|\\b(?:system|developer)\\s+(?:prompt|message|instructions?|directives?)\\b"
              + "|\\b(?:prompt\\s+injection|jailbreak|tool\\s+(?:call|request|output))\\b"
              + "|\\breveal\\s+(?:the\\s+)?(?:system|developer)\\s+prompt\\b"
              + "|(?:^|[.!?]\\s+|\\R)\\s*(?:please\\s+)?(?:reply|respond|output|print|write|say|follow|ignore|obey|call\\s+(?:a\\s+)?tool)\\b"
              + "|(?:^|\\R)\\s*(?:system|developer|assistant|user|tool)\\s*:"
              + ")");
  private static final Pattern SCORE_FRAGMENT =
      Pattern.compile("(?i)(?<!\\d)\\d{1,7}(?:,\\d{3})*(?:\\s+in)?\\s+\\d{1,2}/\\d{1,2}(?!\\d)");
  private static final Pattern TOKEN =
      Pattern.compile("[\\p{L}\\p{N}]+(?:[,.'/-][\\p{L}\\p{N}]+)*");

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    if (!isSafe(answer, forbiddenEvidenceIdentifiers, submittedSourceTexts)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    try {
      return evaluate(answer, forbiddenEvidenceIdentifiers, submittedSourceTexts);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean evaluate(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    String normalizedAnswer = StringUtils.trimToNull(answer);
    List<String> sources = submittedSourceTexts == null ? List.of() : submittedSourceTexts;
    if (normalizedAnswer == null
        || normalizedAnswer.length() > MAX_ANSWER_CHARACTERS
        || sources.size() > MAX_SOURCE_COUNT
        || containsForbiddenIdentifier(normalizedAnswer, forbiddenEvidenceIdentifiers)
        || containsDirectSensitiveIdentifier(normalizedAnswer)
        || INSTRUCTION_LEAKAGE.matcher(normalizedAnswer).find()) {
      return false;
    }

    List<TokenValue> answerTokens = tokens(normalizedAnswer);
    Set<NgramKey> sourceNgrams = new HashSet<>();
    Map<Integer, Set<NgramKey>> wholeShortMessages = new HashMap<>();
    Set<String> sensitiveSourcePhones = new HashSet<>();
    int sourceCharacters = 0;
    int sourceTokens = 0;
    for (String sourceText : sources) {
      if (sourceText == null) {
        continue;
      }
      sourceCharacters = Math.addExact(sourceCharacters, sourceText.length());
      if (sourceCharacters > MAX_SOURCE_CHARACTERS) {
        return false;
      }
      collectSensitivePhones(sourceText, sensitiveSourcePhones);
      List<TokenValue> values = tokens(sourceText);
      sourceTokens = Math.addExact(sourceTokens, values.size());
      if (sourceTokens > MAX_SOURCE_TOKENS) {
        return false;
      }
      addNgrams(sourceNgrams, values, MATCH_NGRAM_TOKENS);
      int phraseCharacters = phraseCharacters(values, 0, values.size());
      if (!values.isEmpty()
          && values.size() <= MAX_SHORT_MESSAGE_TOKENS
          && phraseCharacters >= MIN_SHORT_MESSAGE_CHARACTERS
          && !SCORE_FRAGMENT.matcher(sourceText).find()) {
        wholeShortMessages
            .computeIfAbsent(values.size(), ignored -> new HashSet<>())
            .add(hash(values, 0, values.size()));
      }
    }

    if (containsSensitiveSourcePhone(normalizedAnswer, sensitiveSourcePhones)
        || reproducesWholeShortMessage(answerTokens, wholeShortMessages)) {
      return false;
    }
    return !hasUnsafeCumulativeOverlap(answerTokens, sourceNgrams);
  }

  private static boolean containsForbiddenIdentifier(
      String answer, Set<String> forbiddenEvidenceIdentifiers) {
    String foldedAnswer = answer.toLowerCase(Locale.ROOT);
    for (String identifier :
        forbiddenEvidenceIdentifiers == null ? Set.<String>of() : forbiddenEvidenceIdentifiers) {
      String normalized = StringUtils.trimToNull(identifier);
      if (normalized != null && foldedAnswer.contains(normalized.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsDirectSensitiveIdentifier(String answer) {
    if (EMAIL.matcher(answer).find()
        || SCHEME.matcher(answer).find()
        || WWW.matcher(answer).find()
        || COMMON_TLD_DOMAIN.matcher(answer).find()) {
      return true;
    }
    Matcher matcher = NUMBER_CANDIDATE.matcher(answer);
    while (matcher.find()) {
      if (looksLikePhone(answer, matcher)) {
        return true;
      }
    }
    return false;
  }

  private static void collectSensitivePhones(String source, Set<String> sensitivePhones) {
    Matcher matcher = NUMBER_CANDIDATE.matcher(source);
    while (matcher.find()) {
      if (looksLikePhone(source, matcher)) {
        sensitivePhones.add(digits(matcher.group()));
      }
    }
  }

  private static boolean containsSensitiveSourcePhone(
      String answer, Set<String> sensitiveSourcePhones) {
    if (sensitiveSourcePhones.isEmpty()) {
      return false;
    }
    Matcher matcher = NUMBER_CANDIDATE.matcher(answer);
    while (matcher.find()) {
      if (sensitiveSourcePhones.contains(digits(matcher.group()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean looksLikePhone(String text, Matcher matcher) {
    String candidate = matcher.group();
    String digits = digits(candidate);
    if (digits.length() < 7 || digits.length() > 15) {
      return false;
    }
    if (candidate.startsWith("+")
        || candidate.indexOf('(') >= 0
        || candidate.indexOf(')') >= 0
        || PHONE_FORMAT.matcher(candidate.strip()).matches()) {
      return true;
    }
    int contextStart = Math.max(0, matcher.start() - 24);
    int contextEnd = Math.min(text.length(), matcher.end() + 24);
    return PHONE_CONTEXT.matcher(text.substring(contextStart, contextEnd)).find();
  }

  private static String digits(String value) {
    StringBuilder digits = new StringBuilder();
    value.codePoints().filter(Character::isDigit).forEach(digits::appendCodePoint);
    return digits.toString();
  }

  private static void addNgrams(Set<NgramKey> target, List<TokenValue> values, int size) {
    for (int index = 0; index + size <= values.size(); index++) {
      target.add(hash(values, index, size));
    }
  }

  private static boolean reproducesWholeShortMessage(
      List<TokenValue> answerTokens, Map<Integer, Set<NgramKey>> wholeShortMessages) {
    for (Map.Entry<Integer, Set<NgramKey>> entry : wholeShortMessages.entrySet()) {
      int size = entry.getKey();
      for (int index = 0; index + size <= answerTokens.size(); index++) {
        if (entry.getValue().contains(hash(answerTokens, index, size))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasUnsafeCumulativeOverlap(
      List<TokenValue> answerTokens, Set<NgramKey> sourceNgrams) {
    if (answerTokens.size() < MATCH_NGRAM_TOKENS || sourceNgrams.isEmpty()) {
      return false;
    }
    boolean[] matched = new boolean[answerTokens.size()];
    for (int index = 0; index + MATCH_NGRAM_TOKENS <= answerTokens.size(); index++) {
      if (!sourceNgrams.contains(hash(answerTokens, index, MATCH_NGRAM_TOKENS))) {
        continue;
      }
      for (int matchedIndex = index; matchedIndex < index + MATCH_NGRAM_TOKENS; matchedIndex++) {
        matched[matchedIndex] = true;
      }
    }
    int matchedTokens = 0;
    int matchedCharacters = 0;
    boolean previousMatched = false;
    for (int index = 0; index < matched.length; index++) {
      if (!matched[index]) {
        previousMatched = false;
        continue;
      }
      matchedTokens++;
      matchedCharacters += answerTokens.get(index).characters();
      if (previousMatched) {
        matchedCharacters++;
      }
      previousMatched = true;
    }
    return (matchedTokens > MAX_SAFE_VERBATIM_TOKENS
            && matchedCharacters >= MIN_UNSAFE_VERBATIM_CHARACTERS)
        || matchedCharacters >= ALWAYS_UNSAFE_VERBATIM_CHARACTERS;
  }

  private static int phraseCharacters(List<TokenValue> values, int from, int size) {
    int characters = Math.max(0, size - 1);
    for (int index = from; index < from + size; index++) {
      characters += values.get(index).characters();
    }
    return characters;
  }

  private static NgramKey hash(List<TokenValue> values, int from, int size) {
    long first = 0xcbf29ce484222325L;
    long second = 0x9e3779b97f4a7c15L;
    for (int index = from; index < from + size; index++) {
      TokenValue value = values.get(index);
      first = (first ^ value.firstHash()) * 0x100000001b3L;
      second = Long.rotateLeft(second ^ value.secondHash(), 13) * 0xc2b2ae3d27d4eb4fL;
    }
    return new NgramKey(first, second);
  }

  private static List<TokenValue> tokens(String value) {
    if (StringUtils.isBlank(value)) {
      return List.of();
    }
    List<TokenValue> tokens = new ArrayList<>();
    Matcher matcher = TOKEN.matcher(value);
    while (matcher.find()) {
      String normalized = matcher.group().toLowerCase(Locale.ROOT);
      long firstHash = 0xcbf29ce484222325L;
      long secondHash = 0x84222325cbf29ce4L;
      for (int index = 0; index < normalized.length(); index++) {
        char character = normalized.charAt(index);
        firstHash = (firstHash ^ character) * 0x100000001b3L;
        secondHash = Long.rotateLeft(secondHash ^ character, 7) * 0x9e3779b185ebca87L;
      }
      tokens.add(new TokenValue(matcher.group().length(), firstHash, secondHash));
    }
    return List.copyOf(tokens);
  }

  private record TokenValue(int characters, long firstHash, long secondHash) {}

  private record NgramKey(long first, long second) {}
}
