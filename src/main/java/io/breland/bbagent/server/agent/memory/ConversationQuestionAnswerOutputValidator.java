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
  private static final int MAX_COMPLETE_TINY_MESSAGE_MATCHES = 2;
  private static final int MAX_COMPLETE_TINY_MESSAGE_TOKENS = 4;
  private static final int MAX_SCORE_FACT_TOKENS = 8;
  private static final int MAX_SENSITIVE_SOURCE_IDENTIFIERS = 20_000;

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,63}(?![a-z0-9._%+-])");
  private static final Pattern SCHEME =
      Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]{1,20}://|(?:mailto|tel|sms):)\\S+");
  private static final Pattern WWW = Pattern.compile("(?i)\\bwww\\.\\S+");
  private static final Pattern BARE_DOMAIN =
      Pattern.compile(
          "(?i)(?<![@a-z0-9_-])"
              + "((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
              + "([a-z]{2,63}))"
              + "(?::(\\d{1,5}))?"
              + "(/[^\\s<>\"']*)?"
              + "(?![a-z0-9-])");
  private static final Pattern PATH_IDENTIFIER =
      Pattern.compile("(?i)(?<![a-z0-9])/[a-z0-9._~!$&'()*+,;=:@%/-]+");
  private static final Pattern NUMBER_CANDIDATE =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s().-]{5,}\\d(?![\\p{L}\\p{N}])");
  private static final Pattern BARE_LONG_NUMBER =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\d{7,}(?![\\p{L}\\p{N}])");
  private static final Pattern PHONE_CONTEXT =
      Pattern.compile("(?i)\\b(?:phone|call|text|contact|mobile|telephone|tel|sms|fax|reach)\\b");
  private static final Pattern PHONE_FORMAT =
      Pattern.compile("(?:\\d{3}[- .]\\d{4}|\\d{3}[- .]\\d{3}[- .]\\d{4})");
  private static final Pattern SUPPORTED_LONG_NUMBER_CONTEXT =
      Pattern.compile(
          "(?i)\\b(?:puzzle|wordle|score|count|total|entries?|items?|messages?|points?|votes?|round|game)\\b");
  private static final Pattern SCORE_TOKEN = Pattern.compile("\\d{1,2}/\\d{1,2}");
  private static final Pattern PUZZLE_ID_TOKEN = Pattern.compile("\\d{1,7}(?:,\\d{3})*");
  private static final Pattern SAFE_PARTICIPANT_TOKEN = Pattern.compile("[\\p{L}][\\p{L}'-]{0,39}");
  private static final Set<String> SCORE_REPORTING_VERBS =
      Set.of("got", "posted", "reported", "scored", "solved");
  private static final Set<String> SCORE_PUZZLE_MARKERS = Set.of("puzzle", "wordle");
  private static final Set<String> SCORE_CONNECTORS = Set.of("in", "with");
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
  private static final Pattern TOKEN =
      Pattern.compile("[\\p{L}\\p{N}]+(?:[,.'/-][\\p{L}\\p{N}]+)*");

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    requireSafe(answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts);
  }

  static void requireSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts) {
    if (!isSafe(
        answer, forbiddenEvidenceIdentifiers, opaqueEvidenceAliases, submittedSourceTexts)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    return isSafe(answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts);
  }

  private static boolean isSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts) {
    try {
      return evaluate(
          answer, forbiddenEvidenceIdentifiers, opaqueEvidenceAliases, submittedSourceTexts);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean evaluate(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts) {
    String normalizedAnswer = StringUtils.trimToNull(answer);
    List<String> sources = submittedSourceTexts == null ? List.of() : submittedSourceTexts;
    if (normalizedAnswer == null
        || normalizedAnswer.length() > MAX_ANSWER_CHARACTERS
        || sources.size() > MAX_SOURCE_COUNT
        || containsForbiddenIdentifier(normalizedAnswer, forbiddenEvidenceIdentifiers, false)
        || containsForbiddenIdentifier(normalizedAnswer, opaqueEvidenceAliases, true)
        || containsDirectEmailOrUrl(normalizedAnswer)
        || INSTRUCTION_LEAKAGE.matcher(normalizedAnswer).find()) {
      return false;
    }

    List<TokenValue> answerTokens = tokens(normalizedAnswer);
    boolean[] supportedAnswerScoreFacts = supportedScoreFactMask(answerTokens);
    Set<NgramKey> sourceNgrams = new HashSet<>();
    Map<Integer, Set<NgramKey>> wholeShortMessages = new HashMap<>();
    Map<Integer, Set<NgramKey>> completeTinyMessages = new HashMap<>();
    SourceIdentifiers sourceIdentifiers = new SourceIdentifiers();
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
      collectSourceIdentifiers(sourceText, sourceIdentifiers);
      List<TokenValue> values = tokens(sourceText);
      sourceTokens = Math.addExact(sourceTokens, values.size());
      if (sourceTokens > MAX_SOURCE_TOKENS) {
        return false;
      }
      addNgrams(sourceNgrams, values, MATCH_NGRAM_TOKENS);
      int phraseCharacters = phraseCharacters(values, 0, values.size());
      if (!values.isEmpty() && !isMinimalSupportedScoreFact(values, 0, values.size())) {
        if (values.size() <= 2) {
          completeTinyMessages
              .computeIfAbsent(values.size(), ignored -> new HashSet<>())
              .add(hash(values, 0, values.size()));
        } else if (values.size() <= MAX_SHORT_MESSAGE_TOKENS
            || phraseCharacters < MIN_SHORT_MESSAGE_CHARACTERS) {
          wholeShortMessages
              .computeIfAbsent(values.size(), ignored -> new HashSet<>())
              .add(hash(values, 0, values.size()));
        }
      }
    }

    sourceIdentifiers.finish();
    if (containsSensitiveNumber(normalizedAnswer, sourceIdentifiers)
        || containsSourceIdentifier(normalizedAnswer, sourceIdentifiers)
        || reproducesWholeShortMessage(answerTokens, wholeShortMessages)
        || hasUnsafeCompleteTinyMessageMontage(
            answerTokens, supportedAnswerScoreFacts, completeTinyMessages)) {
      return false;
    }
    return !hasUnsafeCumulativeOverlap(answerTokens, supportedAnswerScoreFacts, sourceNgrams);
  }

  private static boolean containsForbiddenIdentifier(
      String answer, Set<String> forbiddenEvidenceIdentifiers, boolean delimiterAware) {
    String foldedAnswer = answer.toLowerCase(Locale.ROOT);
    for (String identifier :
        forbiddenEvidenceIdentifiers == null ? Set.<String>of() : forbiddenEvidenceIdentifiers) {
      String normalized = StringUtils.trimToNull(identifier);
      if (normalized != null
          && (delimiterAware
              ? containsDelimited(foldedAnswer, normalized.toLowerCase(Locale.ROOT))
              : foldedAnswer.contains(normalized.toLowerCase(Locale.ROOT)))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsDelimited(String foldedValue, String foldedIdentifier) {
    for (int offset = foldedValue.indexOf(foldedIdentifier);
        offset >= 0;
        offset = foldedValue.indexOf(foldedIdentifier, offset + 1)) {
      int end = offset + foldedIdentifier.length();
      boolean startsAtBoundary =
          offset == 0 || !isEvidenceIdentifierCharacter(foldedValue.charAt(offset - 1));
      boolean endsAtBoundary =
          end == foldedValue.length() || !isEvidenceIdentifierCharacter(foldedValue.charAt(end));
      if (startsAtBoundary && endsAtBoundary) {
        return true;
      }
    }
    return false;
  }

  private static boolean isEvidenceIdentifierCharacter(char value) {
    return Character.isLetterOrDigit(value) || value == '_' || value == '-';
  }

  private static boolean containsDirectEmailOrUrl(String answer) {
    if (EMAIL.matcher(answer).find()
        || SCHEME.matcher(answer).find()
        || WWW.matcher(answer).find()) {
      return true;
    }
    Matcher domainMatcher = BARE_DOMAIN.matcher(answer);
    while (domainMatcher.find()) {
      if (!isAllowedBareCodeToken(domainMatcher)) {
        return true;
      }
    }
    return false;
  }

  private static void collectSourceIdentifiers(String source, SourceIdentifiers sourceIdentifiers) {
    Matcher emailMatcher = EMAIL.matcher(source);
    while (emailMatcher.find()) {
      sourceIdentifiers.addText(emailMatcher.group());
    }
    Matcher schemeMatcher = SCHEME.matcher(source);
    while (schemeMatcher.find()) {
      sourceIdentifiers.addText(schemeMatcher.group());
      sourceIdentifiers.addPath(pathFromUrl(schemeMatcher.group()));
    }
    Matcher wwwMatcher = WWW.matcher(source);
    while (wwwMatcher.find()) {
      sourceIdentifiers.addText(wwwMatcher.group());
      sourceIdentifiers.addPath(pathFromUrl(wwwMatcher.group()));
    }
    Matcher domainMatcher = BARE_DOMAIN.matcher(source);
    while (domainMatcher.find()) {
      if (isAllowedBareCodeToken(domainMatcher)) {
        continue;
      }
      sourceIdentifiers.addText(domainMatcher.group(1));
      sourceIdentifiers.addPath(domainMatcher.group(4));
    }
    Matcher pathMatcher = PATH_IDENTIFIER.matcher(source);
    while (pathMatcher.find()) {
      sourceIdentifiers.addPath(pathMatcher.group());
    }

    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(source);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      String candidateDigits = digits(candidate);
      if (candidateDigits.length() >= 7
          && (isFormattedNumber(candidate) || hasPhoneContext(source, formattedMatcher))) {
        sourceIdentifiers.addSensitiveNumber(candidateDigits);
      }
    }
    Matcher numberMatcher = BARE_LONG_NUMBER.matcher(source);
    while (numberMatcher.find()) {
      if (hasSupportedLongNumberContext(source, numberMatcher.start(), numberMatcher.end())) {
        sourceIdentifiers.addSupportedNumber(numberMatcher.group());
      } else {
        sourceIdentifiers.addSensitiveNumber(numberMatcher.group());
      }
    }
  }

  private static boolean containsSourceIdentifier(
      String answer, SourceIdentifiers sourceIdentifiers) {
    Matcher emailMatcher = EMAIL.matcher(answer);
    while (emailMatcher.find()) {
      if (sourceIdentifiers
          .textIdentifiers()
          .contains(emailMatcher.group().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    Matcher domainMatcher = BARE_DOMAIN.matcher(answer);
    while (domainMatcher.find()) {
      if (!isAllowedBareCodeToken(domainMatcher)
          && sourceIdentifiers
              .textIdentifiers()
              .contains(domainMatcher.group(1).toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    Matcher pathMatcher = PATH_IDENTIFIER.matcher(answer);
    while (pathMatcher.find()) {
      if (sourceIdentifiers.paths().contains(normalizePath(pathMatcher.group()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAllowedBareCodeToken(Matcher domainMatcher) {
    String extension = domainMatcher.group(2);
    return ("js".equalsIgnoreCase(extension) || "json".equalsIgnoreCase(extension))
        && domainMatcher.group(3) == null
        && domainMatcher.group(4) == null;
  }

  private static boolean containsSensitiveNumber(
      String answer, SourceIdentifiers sourceIdentifiers) {
    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(answer);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      if (digits(candidate).length() >= 7
          && (isFormattedNumber(candidate) || hasPhoneContext(answer, formattedMatcher))) {
        return true;
      }
    }
    Matcher numberMatcher = BARE_LONG_NUMBER.matcher(answer);
    while (numberMatcher.find()) {
      String value = numberMatcher.group();
      if (sourceIdentifiers.sensitiveNumbers().contains(value)
          || !sourceIdentifiers.supportedNumbers().contains(value)
          || !hasSupportedLongNumberContext(answer, numberMatcher.start(), numberMatcher.end())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasPhoneContext(String text, Matcher matcher) {
    int contextStart = Math.max(0, matcher.start() - 24);
    int contextEnd = Math.min(text.length(), matcher.end() + 24);
    return PHONE_CONTEXT.matcher(text.substring(contextStart, contextEnd)).find();
  }

  private static boolean isFormattedNumber(String candidate) {
    return candidate.startsWith("+")
        || candidate.indexOf('(') >= 0
        || candidate.indexOf(')') >= 0
        || PHONE_FORMAT.matcher(candidate.strip()).matches()
        || candidate.codePoints().anyMatch(value -> !Character.isDigit(value));
  }

  private static boolean hasSupportedLongNumberContext(String text, int start, int end) {
    int contextStart = Math.max(0, start - 48);
    int contextEnd = Math.min(text.length(), end + 48);
    return SUPPORTED_LONG_NUMBER_CONTEXT.matcher(text.substring(contextStart, contextEnd)).find();
  }

  private static String pathFromUrl(String value) {
    String normalized = StringUtils.trimToEmpty(value);
    int scheme = normalized.indexOf("://");
    int pathStart;
    if (scheme >= 0) {
      int authorityStart = scheme + 3;
      pathStart = normalized.indexOf('/', authorityStart);
    } else {
      pathStart = normalized.indexOf('/');
    }
    return pathStart < 0 ? "" : normalized.substring(pathStart);
  }

  private static String normalizePath(String path) {
    String normalized = StringUtils.trimToEmpty(path).toLowerCase(Locale.ROOT);
    int query = normalized.indexOf('?');
    int fragment = normalized.indexOf('#');
    int end = normalized.length();
    if (query >= 0) {
      end = Math.min(end, query);
    }
    if (fragment >= 0) {
      end = Math.min(end, fragment);
    }
    while (end > 1 && ".,;:!?)]}".indexOf(normalized.charAt(end - 1)) >= 0) {
      end--;
    }
    return normalized.substring(0, end);
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

  private static boolean hasUnsafeCompleteTinyMessageMontage(
      List<TokenValue> answerTokens,
      boolean[] supportedScoreFacts,
      Map<Integer, Set<NgramKey>> completeTinyMessages) {
    if (answerTokens.isEmpty() || completeTinyMessages.isEmpty()) {
      return false;
    }
    boolean[] matchedTokens = new boolean[answerTokens.size()];
    int matches = 0;
    for (int size = 2; size >= 1; size--) {
      Set<NgramKey> sourceMessages = completeTinyMessages.get(size);
      if (sourceMessages == null) {
        continue;
      }
      for (int index = 0; index + size <= answerTokens.size(); index++) {
        if (!sourceMessages.contains(hash(answerTokens, index, size))
            || allMasked(supportedScoreFacts, index, size)
            || (size == 1 && matchedTokens[index])) {
          continue;
        }
        matches++;
        for (int tokenIndex = index; tokenIndex < index + size; tokenIndex++) {
          if (!supportedScoreFacts[tokenIndex]) {
            matchedTokens[tokenIndex] = true;
          }
        }
      }
    }
    int matchedTokenCount = 0;
    for (boolean matched : matchedTokens) {
      if (matched) {
        matchedTokenCount++;
      }
    }
    return matches > MAX_COMPLETE_TINY_MESSAGE_MATCHES
        || matchedTokenCount > MAX_COMPLETE_TINY_MESSAGE_TOKENS;
  }

  private static boolean allMasked(boolean[] mask, int from, int size) {
    for (int index = from; index < from + size; index++) {
      if (!mask[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasUnsafeCumulativeOverlap(
      List<TokenValue> answerTokens, boolean[] supportedScoreFacts, Set<NgramKey> sourceNgrams) {
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
      if (!matched[index] || supportedScoreFacts[index]) {
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

  private static boolean[] supportedScoreFactMask(List<TokenValue> values) {
    boolean[] mask = new boolean[values.size()];
    for (int scoreIndex = 0; scoreIndex < values.size(); scoreIndex++) {
      if (!isScoreToken(values.get(scoreIndex))) {
        continue;
      }
      int earliest = Math.max(0, scoreIndex + 1 - MAX_SCORE_FACT_TOKENS);
      for (int from = earliest; from <= scoreIndex; from++) {
        int size = scoreIndex - from + 1;
        if (!isMinimalSupportedScoreFact(values, from, size)) {
          continue;
        }
        for (int index = from; index <= scoreIndex; index++) {
          mask[index] = true;
        }
        break;
      }
    }
    return mask;
  }

  private static boolean isMinimalSupportedScoreFact(List<TokenValue> values, int from, int size) {
    if (size < 1 || size > MAX_SCORE_FACT_TOKENS) {
      return false;
    }
    int index = from;
    int to = from + size;
    if (index + 2 < to
        && "participant".equals(values.get(index).normalized())
        && "ending".equals(values.get(index + 1).normalized())
        && isPuzzleId(values.get(index + 2))) {
      index += 3;
    } else if (index < to
        && isParticipantToken(values.get(index))
        && !SCORE_REPORTING_VERBS.contains(values.get(index).normalized())
        && !SCORE_PUZZLE_MARKERS.contains(values.get(index).normalized())) {
      index++;
    }
    if (index < to && SCORE_REPORTING_VERBS.contains(values.get(index).normalized())) {
      index++;
    }
    boolean puzzleMarker = false;
    if (index < to && SCORE_PUZZLE_MARKERS.contains(values.get(index).normalized())) {
      puzzleMarker = true;
      index++;
      if (index < to && "number".equals(values.get(index).normalized())) {
        index++;
      }
    }
    boolean puzzleId = false;
    if (index < to && isPuzzleId(values.get(index))) {
      puzzleId = true;
      index++;
    }
    if (index < to && SCORE_CONNECTORS.contains(values.get(index).normalized())) {
      index++;
    }
    return index + 1 == to
        && isScoreToken(values.get(index))
        && (puzzleMarker || puzzleId || size <= 2);
  }

  private static boolean isParticipantToken(TokenValue value) {
    return SAFE_PARTICIPANT_TOKEN.matcher(value.normalized()).matches();
  }

  private static boolean isPuzzleId(TokenValue value) {
    return PUZZLE_ID_TOKEN.matcher(value.normalized()).matches();
  }

  private static boolean isScoreToken(TokenValue value) {
    return SCORE_TOKEN.matcher(value.normalized()).matches();
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
      tokens.add(new TokenValue(matcher.group().length(), normalized, firstHash, secondHash));
    }
    return List.copyOf(tokens);
  }

  private static final class SourceIdentifiers {
    private final Set<String> textIdentifiers = new HashSet<>();
    private final Set<String> paths = new HashSet<>();
    private final Set<String> sensitiveNumbers = new HashSet<>();
    private final Set<String> supportedNumbers = new HashSet<>();

    private void addText(String value) {
      String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        textIdentifiers.add(normalized);
        checkSize();
      }
    }

    private void addPath(String value) {
      String normalized = normalizePath(value);
      if (normalized.length() > 1) {
        paths.add(normalized);
        checkSize();
      }
    }

    private void addSensitiveNumber(String value) {
      if (!value.isEmpty()) {
        sensitiveNumbers.add(value);
        checkSize();
      }
    }

    private void addSupportedNumber(String value) {
      if (!value.isEmpty()) {
        supportedNumbers.add(value);
        checkSize();
      }
    }

    private void finish() {
      supportedNumbers.removeAll(sensitiveNumbers);
    }

    private Set<String> textIdentifiers() {
      return textIdentifiers;
    }

    private Set<String> paths() {
      return paths;
    }

    private Set<String> sensitiveNumbers() {
      return sensitiveNumbers;
    }

    private Set<String> supportedNumbers() {
      return supportedNumbers;
    }

    private void checkSize() {
      long size =
          (long) textIdentifiers.size()
              + paths.size()
              + sensitiveNumbers.size()
              + supportedNumbers.size();
      if (size > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
        throw new IllegalStateException("sensitive source identifier budget exceeded");
      }
    }
  }

  private record TokenValue(int characters, String normalized, long firstHash, long secondHash) {}

  private record NgramKey(long first, long second) {}
}
