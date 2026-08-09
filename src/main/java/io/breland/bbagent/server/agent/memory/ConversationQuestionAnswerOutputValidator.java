package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.TrustedQuestionFact;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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
  private static final int MAX_COMPLETE_TINY_MESSAGE_MATCHES = 1;
  private static final int UNSAFE_GLOBAL_MATCHED_SOURCE_TOKEN_BOUNDARY = 4;
  private static final int UNSAFE_GLOBAL_MATCHED_SOURCE_CHARACTER_BOUNDARY = 32;
  private static final int MAX_SENSITIVE_SOURCE_IDENTIFIERS = 20_000;
  private static final int MAX_PARTICIPANT_LABEL_TOKENS = 8;
  private static final int MAX_PARTICIPANT_LABEL_CHARACTERS = 160;

  private static final Pattern EMAIL =
      Pattern.compile(
          "(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,63}(?![a-z0-9._%+-])");
  private static final Pattern SCHEME =
      Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]{1,20}://|(?:mailto|tel|sms):)\\S+");
  private static final Pattern WWW = Pattern.compile("(?i)\\bwww\\.\\S+");
  private static final Pattern PATH_IDENTIFIER =
      Pattern.compile("(?i)(?<![a-z0-9])/[a-z0-9._~!$&'()*+,;=:@%/-]+");
  private static final Pattern NUMBER_CANDIDATE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s()./\\-\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]{5,}\\d(?![\\p{L}\\p{N}])");
  private static final Pattern BARE_LONG_NUMBER =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\d{7,}(?![\\p{L}\\p{N}])");
  private static final Pattern GROUPED_LONG_NUMBER =
      Pattern.compile("(?<![\\p{L}\\p{N}])\\d{1,3}(?:,\\d{3}){2,}(?![\\p{L}\\p{N}])");
  private static final Pattern PHONE_CONTEXT =
      Pattern.compile("(?i)\\b(?:phone|call|text|contact|mobile|telephone|tel|sms|fax|reach)\\b");
  private static final Pattern PHONE_FORMAT =
      Pattern.compile("(?:\\d{3}[- .]\\d{4}|\\d{3}[- .]\\d{3}[- .]\\d{4}|\\d{3}-\\d{2}-\\d{4})");
  private static final Pattern SUPPORTED_LONG_NUMBER_CONTEXT =
      Pattern.compile(
          "(?i)\\b(?:puzzle|wordle|score|count|total|entries?|items?|messages?|points?|votes?|round|game)\\b");
  private static final Pattern SCORE_TOKEN = Pattern.compile("\\d{1,2}/\\d{1,2}");
  private static final Pattern PUZZLE_ID_TOKEN = Pattern.compile("\\d{1,7}(?:,\\d{3})*");
  private static final Set<String> SCORE_REPORTING_VERBS =
      Set.of("got", "posted", "reported", "scored", "solved");
  private static final Set<String> SCORE_PUZZLE_MARKERS = Set.of("puzzle", "wordle");
  private static final Set<String> SCORE_CONNECTORS = Set.of("in", "with");
  private static final Set<String> SAFE_SCORE_RENDERING_TOKENS =
      Set.of("reported", "puzzle", "wordle", "in", "with", "score", "scores");
  private static final Set<String> SAFE_GENERATED_TOKENS =
      Set.of(
          "a",
          "an",
          "and",
          "are",
          "according",
          "code",
          "codes",
          "evidence",
          "in",
          "is",
          "model",
          "models",
          "of",
          "only",
          "puzzle",
          "reported",
          "result",
          "results",
          "score",
          "scores",
          "the",
          "to",
          "was",
          "were",
          "with",
          "wordle");
  private static final DateTimeFormatter US_DASH_DATE =
      new DateTimeFormatterBuilder()
          .appendPattern("MM-dd-uuuu")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter US_SLASH_DATE =
      new DateTimeFormatterBuilder()
          .appendPattern("MM/dd/uuuu")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
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

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    requireSafe(
        answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts, List.of(), Set.of());
  }

  static void requireSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts) {
    requireSafe(
        answer,
        forbiddenEvidenceIdentifiers,
        opaqueEvidenceAliases,
        submittedSourceTexts,
        List.of(),
        Set.of());
  }

  static void requireSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      List<TrustedQuestionFact> trustedFacts,
      Set<String> citedEvidenceIdentifiers) {
    if (!isSafe(
        answer,
        forbiddenEvidenceIdentifiers,
        opaqueEvidenceAliases,
        submittedSourceTexts,
        trustedFacts,
        citedEvidenceIdentifiers)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    return isSafe(
        answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts, List.of(), Set.of());
  }

  static boolean isSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      List<String> submittedSourceTexts,
      List<TrustedQuestionFact> trustedFacts,
      Set<String> citedEvidenceIdentifiers) {
    return isSafe(
        answer,
        forbiddenEvidenceIdentifiers,
        Set.of(),
        submittedSourceTexts,
        trustedFacts,
        citedEvidenceIdentifiers);
  }

  private static boolean isSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      List<TrustedQuestionFact> trustedFacts,
      Set<String> citedEvidenceIdentifiers) {
    try {
      return evaluate(
          answer,
          forbiddenEvidenceIdentifiers,
          opaqueEvidenceAliases,
          submittedSourceTexts,
          trustedFacts,
          citedEvidenceIdentifiers);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean evaluate(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      List<TrustedQuestionFact> trustedFactMetadata,
      Set<String> citedEvidenceIdentifiers) {
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
    TrustedFacts trustedFacts = new TrustedFacts(trustedFactMetadata, citedEvidenceIdentifiers);
    Set<NgramKey> sourceNgrams = new HashSet<>();
    Map<String, Integer> sourceUnigrams = new HashMap<>();
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
      addUnigrams(sourceUnigrams, values);
      int phraseCharacters = phraseCharacters(values, 0, values.size());
      if (!values.isEmpty()) {
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
    boolean[] supportedAnswerFacts =
        trustedFacts.validateAndMaskAnswer(normalizedAnswer, answerTokens);
    if (supportedAnswerFacts == null) {
      return false;
    }
    if (containsSensitiveNumber(normalizedAnswer, sourceIdentifiers)
        || containsSourceIdentifier(normalizedAnswer, sourceIdentifiers)
        || reproducesWholeShortMessage(answerTokens, wholeShortMessages)
        || hasUnsafeCompleteTinyMessageMontage(
            answerTokens, supportedAnswerFacts, completeTinyMessages, sourceIdentifiers)
        || hasUnsafeGlobalUnigramMontage(
            answerTokens, supportedAnswerFacts, sourceUnigrams, sourceIdentifiers)) {
      return false;
    }
    return !hasUnsafeCumulativeOverlap(answerTokens, supportedAnswerFacts, sourceNgrams);
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
    return !endpoints(answer).isEmpty();
  }

  private static void collectSourceIdentifiers(String source, SourceIdentifiers sourceIdentifiers) {
    Matcher emailMatcher = EMAIL.matcher(source);
    while (emailMatcher.find()) {
      sourceIdentifiers.addText(emailMatcher.group());
    }
    Matcher schemeMatcher = SCHEME.matcher(source);
    while (schemeMatcher.find()) {
      sourceIdentifiers.addText(schemeMatcher.group());
    }
    Matcher wwwMatcher = WWW.matcher(source);
    while (wwwMatcher.find()) {
      sourceIdentifiers.addText(wwwMatcher.group());
    }
    for (EndpointValue endpoint : endpoints(source)) {
      sourceIdentifiers.addEndpoint(endpoint.key());
      sourceIdentifiers.addPath(endpoint.path());
    }
    Matcher pathMatcher = PATH_IDENTIFIER.matcher(source);
    while (pathMatcher.find()) {
      sourceIdentifiers.addPath(pathMatcher.group());
    }

    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(source);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      String candidateDigits = digits(candidate);
      if (isValidCalendarDate(normalizePhoneCandidate(candidate))) {
        for (TokenValue value : tokens(candidate)) {
          sourceIdentifiers.addOverlapExemptToken(value.normalized());
        }
      }
      if (candidateDigits.length() >= 7
          && (isRealisticPhone(candidate) || hasPhoneContext(source, formattedMatcher))) {
        sourceIdentifiers.addSensitiveNumber(candidateDigits);
      }
    }
    Matcher numberMatcher = BARE_LONG_NUMBER.matcher(source);
    while (numberMatcher.find()) {
      if (hasSupportedLongNumberContext(source, numberMatcher.start(), numberMatcher.end())) {
        sourceIdentifiers.addSupportedNumber(digits(numberMatcher.group()));
      } else {
        sourceIdentifiers.addSensitiveNumber(digits(numberMatcher.group()));
      }
    }
    Matcher groupedNumberMatcher = GROUPED_LONG_NUMBER.matcher(source);
    while (groupedNumberMatcher.find()) {
      if (hasSupportedLongNumberContext(
          source, groupedNumberMatcher.start(), groupedNumberMatcher.end())) {
        sourceIdentifiers.addSupportedNumber(digits(groupedNumberMatcher.group()));
      } else {
        sourceIdentifiers.addSensitiveNumber(digits(groupedNumberMatcher.group()));
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
    for (EndpointValue endpoint : endpoints(answer)) {
      if (sourceIdentifiers.endpoints().contains(endpoint.key())) {
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

  private static boolean containsSensitiveNumber(
      String answer, SourceIdentifiers sourceIdentifiers) {
    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(answer);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      String candidateDigits = digits(candidate);
      if (candidateDigits.length() >= 7
          && (sourceIdentifiers.sensitiveNumbers().contains(candidateDigits)
              || isRealisticPhone(candidate)
              || hasPhoneContext(answer, formattedMatcher))) {
        return true;
      }
    }
    Matcher numberMatcher = BARE_LONG_NUMBER.matcher(answer);
    while (numberMatcher.find()) {
      String value = numberMatcher.group();
      String valueDigits = digits(value);
      if (sourceIdentifiers.sensitiveNumbers().contains(valueDigits)
          || !sourceIdentifiers.supportedNumbers().contains(valueDigits)
          || !hasSupportedLongNumberContext(answer, numberMatcher.start(), numberMatcher.end())) {
        return true;
      }
    }
    Matcher groupedNumberMatcher = GROUPED_LONG_NUMBER.matcher(answer);
    while (groupedNumberMatcher.find()) {
      String valueDigits = digits(groupedNumberMatcher.group());
      if (sourceIdentifiers.sensitiveNumbers().contains(valueDigits)
          || !sourceIdentifiers.supportedNumbers().contains(valueDigits)
          || !hasSupportedLongNumberContext(
              answer, groupedNumberMatcher.start(), groupedNumberMatcher.end())) {
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

  private static boolean isRealisticPhone(String candidate) {
    String normalized = normalizePhoneCandidate(candidate);
    int digitCount = digits(normalized).length();
    if (digitCount < 7 || digitCount > 15 || isValidCalendarDate(normalized)) {
      return false;
    }
    return normalized.startsWith("+")
        || normalized.indexOf('(') >= 0
        || normalized.indexOf(')') >= 0
        || PHONE_FORMAT.matcher(normalized).matches();
  }

  private static String normalizePhoneCandidate(String candidate) {
    StringBuilder normalized = new StringBuilder(candidate.length());
    for (int index = 0; index < candidate.length(); index++) {
      char value = candidate.charAt(index);
      if (value == '/'
          || (value >= '\u2010' && value <= '\u2015')
          || value == '\u2212'
          || value == '\uFE58'
          || value == '\uFE63'
          || value == '\uFF0D') {
        normalized.append('-');
      } else {
        normalized.append(value);
      }
    }
    return normalized.toString().strip();
  }

  private static boolean isValidCalendarDate(String value) {
    return parsesDate(value, DateTimeFormatter.ISO_LOCAL_DATE)
        || parsesDate(value, US_DASH_DATE)
        || parsesDate(value, US_SLASH_DATE);
  }

  private static boolean parsesDate(String value, DateTimeFormatter formatter) {
    try {
      LocalDate.parse(value, formatter);
      return true;
    } catch (DateTimeParseException ignored) {
      return false;
    }
  }

  private static boolean hasSupportedLongNumberContext(String text, int start, int end) {
    int contextStart = Math.max(0, start - 48);
    int contextEnd = Math.min(text.length(), end + 48);
    return SUPPORTED_LONG_NUMBER_CONTEXT.matcher(text.substring(contextStart, contextEnd)).find();
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

  private static List<EndpointValue> endpoints(String text) {
    if (StringUtils.isBlank(text)) {
      return List.of();
    }
    List<EndpointValue> values = new ArrayList<>();
    int start = -1;
    for (int index = 0; index <= text.length(); index++) {
      boolean boundary = index == text.length() || isEndpointCandidateBoundary(text.charAt(index));
      if (!boundary && start < 0) {
        start = index;
      }
      if (!boundary || start < 0) {
        continue;
      }
      EndpointValue parsed = parseEndpointCandidate(text.substring(start, index));
      if (parsed != null) {
        values.add(parsed);
        if (values.size() > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
          throw new IllegalStateException("endpoint work budget exceeded");
        }
      }
      start = -1;
    }
    return List.copyOf(values);
  }

  private static boolean isEndpointCandidateBoundary(char value) {
    return Character.isWhitespace(value) || "<>\"'(),;={}@".indexOf(value) >= 0;
  }

  private static EndpointValue parseEndpointCandidate(String rawValue) {
    String value = trimEndpointCandidate(rawValue);
    if (value.isEmpty()) {
      return null;
    }
    EndpointValue endpoint = parseEndpointValue(value);
    if (endpoint != null) {
      return endpoint;
    }
    int punctuationColon = value.indexOf(':');
    if (punctuationColon > 0 && punctuationColon + 1 < value.length()) {
      return parseEndpointValue(value.substring(punctuationColon + 1));
    }
    return null;
  }

  private static EndpointValue parseEndpointValue(String rawValue) {
    String value = rawValue;
    int schemeSeparator = value.indexOf("://");
    if (schemeSeparator > 0) {
      if (!isValidScheme(value, schemeSeparator)) {
        return null;
      }
      value = value.substring(schemeSeparator + 3);
    }
    if (value.isEmpty()) {
      return null;
    }

    if (value.charAt(0) == '[') {
      int close = value.indexOf(']');
      if (close < 0 || !isValidIpv6(value.substring(1, close))) {
        return null;
      }
      EndpointSuffix suffix = endpointSuffix(value.substring(close + 1));
      if (suffix == null) {
        return null;
      }
      String host = value.substring(1, close).toLowerCase(Locale.ROOT);
      return new EndpointValue(endpointKey("[" + host + "]", suffix), suffix.path());
    }

    int pathStart = firstEndpointSuffixIndex(value, 0);
    String authority = pathStart < 0 ? value : value.substring(0, pathStart);
    String rawPath = pathStart < 0 ? "" : value.substring(pathStart);
    if (authority.isEmpty()) {
      return null;
    }
    int colonCount = characterCount(authority, ':');
    if (colonCount >= 2) {
      if (!isValidIpv6(authority)) {
        return null;
      }
      EndpointSuffix suffix = new EndpointSuffix(null, normalizePath(rawPath));
      String host = authority.toLowerCase(Locale.ROOT);
      return new EndpointValue(endpointKey("[" + host + "]", suffix), suffix.path());
    }

    Integer port = null;
    String host = authority;
    if (colonCount == 1) {
      int colon = authority.indexOf(':');
      port = parsePort(authority.substring(colon + 1));
      if (port == null) {
        return null;
      }
      host = authority.substring(0, colon);
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    String normalizedPath = normalizePath(rawPath);
    boolean hasPathOrQuery = !rawPath.isEmpty();
    boolean validHost =
        isValidIpv4(normalizedHost)
            || isValidDomain(normalizedHost)
            || ("localhost".equals(normalizedHost) && (port != null || hasPathOrQuery))
            || (port != null
                && containsAsciiLetter(normalizedHost)
                && isValidHostnameLabel(normalizedHost));
    if (!validHost
        || isAllowedBareCodeToken(normalizedHost, port, normalizedPath, hasPathOrQuery)) {
      return null;
    }
    EndpointSuffix suffix = new EndpointSuffix(port, normalizedPath);
    return new EndpointValue(endpointKey(normalizedHost, suffix), normalizedPath);
  }

  private static String trimEndpointCandidate(String rawValue) {
    int start = 0;
    int end = rawValue.length();
    while (start < end && ".!?_-".indexOf(rawValue.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && ".!?_".indexOf(rawValue.charAt(end - 1)) >= 0) {
      end--;
    }
    if (end > start + 1 && rawValue.charAt(end - 1) == ':' && rawValue.charAt(end - 2) != ':') {
      end--;
    }
    return rawValue.substring(start, end);
  }

  private static boolean isValidScheme(String value, int separator) {
    if (separator < 2 || !Character.isLetter(value.charAt(0))) {
      return false;
    }
    for (int index = 1; index < separator; index++) {
      char character = value.charAt(index);
      if (!Character.isLetterOrDigit(character) && "+.-".indexOf(character) < 0) {
        return false;
      }
    }
    return true;
  }

  private static EndpointSuffix endpointSuffix(String suffix) {
    if (suffix.isEmpty()) {
      return new EndpointSuffix(null, "");
    }
    Integer port = null;
    int pathStart = 0;
    if (suffix.charAt(0) == ':') {
      pathStart = firstEndpointSuffixIndex(suffix, 1);
      String rawPort = pathStart < 0 ? suffix.substring(1) : suffix.substring(1, pathStart);
      port = parsePort(rawPort);
      if (port == null) {
        return null;
      }
    }
    if (pathStart == 0 && "/?#".indexOf(suffix.charAt(0)) < 0) {
      return null;
    }
    String path = pathStart < 0 ? "" : normalizePath(suffix.substring(pathStart));
    return new EndpointSuffix(port, path);
  }

  private static int firstEndpointSuffixIndex(String value, int from) {
    for (int index = Math.max(0, from); index < value.length(); index++) {
      if ("/?#".indexOf(value.charAt(index)) >= 0) {
        return index;
      }
    }
    return -1;
  }

  private static Integer parsePort(String value) {
    if (value.isEmpty() || value.length() > 5) {
      return null;
    }
    int port = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!Character.isDigit(character)) {
        return null;
      }
      port = port * 10 + character - '0';
    }
    return port >= 1 && port <= 65_535 ? port : null;
  }

  private static String endpointKey(String host, EndpointSuffix suffix) {
    return host
        + (suffix.port() == null ? "" : ":" + suffix.port())
        + StringUtils.trimToEmpty(suffix.path());
  }

  private static boolean isAllowedBareCodeToken(
      String host, Integer port, String normalizedPath, boolean hasPathOrQuery) {
    return port == null
        && !hasPathOrQuery
        && StringUtils.isEmpty(normalizedPath)
        && ("node.js".equals(host) || "package.json".equals(host));
  }

  private static boolean isValidIpv4(String value) {
    int octets = 0;
    int octet = 0;
    int digits = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index < value.length() && Character.isDigit(value.charAt(index))) {
        if (++digits > 3) {
          return false;
        }
        octet = octet * 10 + value.charAt(index) - '0';
        continue;
      }
      if (digits == 0 || octet > 255 || (index < value.length() && value.charAt(index) != '.')) {
        return false;
      }
      octets++;
      digits = 0;
      octet = 0;
    }
    return octets == 4;
  }

  private static boolean isValidIpv6(String value) {
    if (value.length() < 2 || value.length() > 80) {
      return false;
    }
    int zoneStart = value.indexOf('%');
    String address = value;
    if (zoneStart >= 0) {
      if (value.indexOf('%', zoneStart + 1) >= 0
          || !isValidIpv6Zone(value.substring(zoneStart + 1))) {
        return false;
      }
      address = value.substring(0, zoneStart);
    }
    int compression = address.indexOf("::");
    if (compression >= 0 && address.indexOf("::", compression + 2) >= 0) {
      return false;
    }
    String left = compression < 0 ? address : address.substring(0, compression);
    String right = compression < 0 ? "" : address.substring(compression + 2);
    if (compression >= 0 && left.indexOf('.') >= 0) {
      return false;
    }
    int groups = validIpv6Groups(left);
    if (groups < 0) {
      return false;
    }
    if (compression >= 0) {
      int rightGroups = validIpv6Groups(right);
      return rightGroups >= 0 && groups + rightGroups < 8;
    }
    return groups == 8;
  }

  private static boolean isValidIpv6Zone(String zone) {
    if (zone.isEmpty() || zone.length() > 32) {
      return false;
    }
    for (int index = 0; index < zone.length(); index++) {
      char value = zone.charAt(index);
      if (!Character.isLetterOrDigit(value) && "._-".indexOf(value) < 0) {
        return false;
      }
    }
    return true;
  }

  private static int validIpv6Groups(String value) {
    if (value.isEmpty()) {
      return 0;
    }
    int groups = 0;
    int groupStart = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index == value.length() || value.charAt(index) == ':') {
        if (index == groupStart) {
          return -1;
        }
        String group = value.substring(groupStart, index);
        if (group.indexOf('.') >= 0) {
          if (index != value.length() || !isValidIpv4(group)) {
            return -1;
          }
          groups += 2;
        } else {
          if (group.length() > 4) {
            return -1;
          }
          for (int character = 0; character < group.length(); character++) {
            if (Character.digit(group.charAt(character), 16) < 0) {
              return -1;
            }
          }
          groups++;
        }
        groupStart = index + 1;
        continue;
      }
    }
    return groups;
  }

  private static boolean isValidDomain(String value) {
    if (value.length() < 4 || value.length() > 253 || value.indexOf('.') < 0) {
      return false;
    }
    int labelStart = 0;
    int lastLabelStart = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index < value.length() && value.charAt(index) != '.') {
        continue;
      }
      if (!isValidHostnameLabel(value.substring(labelStart, index))) {
        return false;
      }
      lastLabelStart = labelStart;
      labelStart = index + 1;
    }
    int tldLength = value.length() - lastLabelStart;
    if (tldLength < 2 || tldLength > 63) {
      return false;
    }
    for (int index = lastLabelStart; index < value.length(); index++) {
      if (!isAsciiLetter(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isValidHostnameLabel(String value) {
    if (value.isEmpty()
        || value.length() > 63
        || value.charAt(0) == '-'
        || value.charAt(value.length() - 1) == '-') {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!isAsciiLetter(character) && !Character.isDigit(character) && character != '-') {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char value) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
  }

  private static boolean containsAsciiLetter(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (isAsciiLetter(value.charAt(index))) {
        return true;
      }
    }
    return false;
  }

  private static int characterCount(String value, char target) {
    int count = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == target) {
        count++;
      }
    }
    return count;
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

  private static void addUnigrams(Map<String, Integer> target, List<TokenValue> values) {
    for (TokenValue value : values) {
      target.merge(value.normalized(), 1, Math::addExact);
    }
  }

  private static boolean hasUnsafeGlobalUnigramMontage(
      List<TokenValue> answerTokens,
      boolean[] supportedFacts,
      Map<String, Integer> sourceUnigrams,
      SourceIdentifiers sourceIdentifiers) {
    if (answerTokens.isEmpty() || sourceUnigrams.isEmpty()) {
      return false;
    }
    Map<String, Integer> used = new HashMap<>();
    int matchedTokens = 0;
    int matchedCharacters = 0;
    for (int index = 0; index < answerTokens.size(); index++) {
      TokenValue answerToken = answerTokens.get(index);
      String token = answerToken.normalized();
      if (isOverlapExempt(answerToken, supportedFacts[index], sourceIdentifiers)) {
        continue;
      }
      int available = sourceUnigrams.getOrDefault(token, 0);
      int consumed = used.getOrDefault(token, 0);
      if (consumed >= available) {
        continue;
      }
      used.put(token, consumed + 1);
      matchedTokens++;
      matchedCharacters = Math.addExact(matchedCharacters, answerToken.characters());
      if (matchedTokens >= UNSAFE_GLOBAL_MATCHED_SOURCE_TOKEN_BOUNDARY
          || matchedCharacters >= UNSAFE_GLOBAL_MATCHED_SOURCE_CHARACTER_BOUNDARY) {
        return true;
      }
    }
    return false;
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
      Map<Integer, Set<NgramKey>> completeTinyMessages,
      SourceIdentifiers sourceIdentifiers) {
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
            || allOverlapExempt(answerTokens, supportedScoreFacts, index, size, sourceIdentifiers)
            || (size == 1 && matchedTokens[index])) {
          continue;
        }
        matches++;
        for (int tokenIndex = index; tokenIndex < index + size; tokenIndex++) {
          if (!isOverlapExempt(
              answerTokens.get(tokenIndex), supportedScoreFacts[tokenIndex], sourceIdentifiers)) {
            matchedTokens[tokenIndex] = true;
          }
        }
      }
    }
    int matchedTokenCount = 0;
    int matchedCharacterCount = 0;
    for (boolean matched : matchedTokens) {
      if (matched) {
        matchedTokenCount++;
      }
    }
    for (int index = 0; index < matchedTokens.length; index++) {
      if (matchedTokens[index]) {
        matchedCharacterCount =
            Math.addExact(matchedCharacterCount, answerTokens.get(index).characters());
      }
    }
    return matches > MAX_COMPLETE_TINY_MESSAGE_MATCHES
        || matchedTokenCount >= UNSAFE_GLOBAL_MATCHED_SOURCE_TOKEN_BOUNDARY
        || matchedCharacterCount >= UNSAFE_GLOBAL_MATCHED_SOURCE_CHARACTER_BOUNDARY;
  }

  private static boolean allOverlapExempt(
      List<TokenValue> values,
      boolean[] mask,
      int from,
      int size,
      SourceIdentifiers sourceIdentifiers) {
    for (int index = from; index < from + size; index++) {
      if (!isOverlapExempt(values.get(index), mask[index], sourceIdentifiers)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isOverlapExempt(
      TokenValue value, boolean supportedFact, SourceIdentifiers sourceIdentifiers) {
    return supportedFact
        || SAFE_GENERATED_TOKENS.contains(value.normalized())
        || sourceIdentifiers.overlapExemptTokens().contains(value.normalized())
        || isSupportedLongNumberToken(value.normalized(), sourceIdentifiers.supportedNumbers());
  }

  private static boolean isSupportedLongNumberToken(String token, Set<String> supportedNumbers) {
    if (token.isEmpty() || !isAsciiDigit(token.charAt(0))) {
      return false;
    }
    for (int index = 0; index < token.length(); index++) {
      char value = token.charAt(index);
      if (!isAsciiDigit(value) && value != ',') {
        return false;
      }
    }
    return supportedNumbers.contains(digits(token));
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
    int index = 0;
    int previousEnd = 0;
    while (index < value.length()) {
      while (index < value.length() && !Character.isLetterOrDigit(value.charAt(index))) {
        index++;
      }
      if (index == value.length()) {
        break;
      }
      int start = index++;
      boolean clauseBoundaryBefore =
          tokens.isEmpty() || hasClauseBoundary(value, previousEnd, start);
      boolean statementBoundaryBefore =
          tokens.isEmpty() || hasStatementBoundary(value, previousEnd, start);
      while (index < value.length()) {
        char character = value.charAt(index);
        if (Character.isLetterOrDigit(character)) {
          index++;
          continue;
        }
        if (isTokenSeparator(character)
            && index + 1 < value.length()
            && Character.isLetterOrDigit(value.charAt(index + 1))) {
          index++;
          continue;
        }
        break;
      }
      String token = value.substring(start, index);
      String normalized = token.toLowerCase(Locale.ROOT);
      long firstHash = 0xcbf29ce484222325L;
      long secondHash = 0x84222325cbf29ce4L;
      for (int hashIndex = 0; hashIndex < normalized.length(); hashIndex++) {
        char character = normalized.charAt(hashIndex);
        firstHash = (firstHash ^ character) * 0x100000001b3L;
        secondHash = Long.rotateLeft(secondHash ^ character, 7) * 0x9e3779b185ebca87L;
      }
      tokens.add(
          new TokenValue(
              token.length(),
              normalized,
              firstHash,
              secondHash,
              start,
              index,
              clauseBoundaryBefore,
              statementBoundaryBefore));
      previousEnd = index;
    }
    return List.copyOf(tokens);
  }

  private static boolean isTokenSeparator(char value) {
    return ",.'/-".indexOf(value) >= 0;
  }

  private static boolean hasClauseBoundary(String value, int from, int to) {
    for (int index = from; index < to; index++) {
      if (",;:.!?\n\r".indexOf(value.charAt(index)) >= 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasStatementBoundary(String value, int from, int to) {
    for (int index = from; index < to; index++) {
      if (";.!?\n\r".indexOf(value.charAt(index)) >= 0) {
        return true;
      }
    }
    return false;
  }

  private static int[] statementIds(String value) {
    int[] statementIds = new int[value.length() + 1];
    int statement = 0;
    for (int index = 0; index < value.length(); index++) {
      statementIds[index] = statement;
      if (";.!?\n\r".indexOf(value.charAt(index)) >= 0) {
        statement++;
      }
    }
    statementIds[value.length()] = statement;
    return statementIds;
  }

  private static List<AttributionAtom> attributionAtoms(String value, int[] statementIds) {
    List<AttributionAtom> atoms = new ArrayList<>();
    int index = 0;
    while (index < value.length()) {
      while (index < value.length() && !Character.isLetterOrDigit(value.charAt(index))) {
        index++;
      }
      if (index == value.length()) {
        break;
      }
      int start = index++;
      while (index < value.length() && Character.isLetterOrDigit(value.charAt(index))) {
        index++;
      }
      atoms.add(
          new AttributionAtom(
              value.substring(start, index).toLowerCase(Locale.ROOT),
              start,
              index,
              statementIds[start]));
    }
    return List.copyOf(atoms);
  }

  private static List<RawScoreOccurrence> rawScoreOccurrences(String value, int[] statementIds) {
    List<RawScoreOccurrence> scores = new ArrayList<>();
    for (int index = 0; index < value.length(); index++) {
      if (!isAsciiDigit(value.charAt(index))
          || (index > 0 && Character.isLetterOrDigit(value.charAt(index - 1)))) {
        continue;
      }
      int numeratorEnd = index;
      while (numeratorEnd < value.length()
          && isAsciiDigit(value.charAt(numeratorEnd))
          && numeratorEnd - index < 2) {
        numeratorEnd++;
      }
      if (numeratorEnd == index
          || (numeratorEnd < value.length() && isAsciiDigit(value.charAt(numeratorEnd)))
          || numeratorEnd >= value.length()
          || value.charAt(numeratorEnd) != '/') {
        continue;
      }
      int denominatorStart = numeratorEnd + 1;
      int end = denominatorStart;
      while (end < value.length()
          && isAsciiDigit(value.charAt(end))
          && end - denominatorStart < 2) {
        end++;
      }
      if (end == denominatorStart
          || (end < value.length() && isAsciiDigit(value.charAt(end)))
          || (end < value.length()
              && (Character.isLetterOrDigit(value.charAt(end)) || value.charAt(end) == '/'))) {
        continue;
      }
      scores.add(
          new RawScoreOccurrence(
              value.substring(index, end).toLowerCase(Locale.ROOT),
              index,
              end,
              statementIds[index]));
      index = end - 1;
    }
    return List.copyOf(scores);
  }

  private static boolean isAsciiDigit(char value) {
    return value >= '0' && value <= '9';
  }

  static boolean isSafeParticipantLabel(String label) {
    String normalized = StringUtils.trimToNull(label);
    if (normalized == null || normalized.length() > MAX_PARTICIPANT_LABEL_CHARACTERS) {
      return false;
    }
    List<TokenValue> values = tokens(normalized);
    return !values.isEmpty() && values.size() <= MAX_PARTICIPANT_LABEL_TOKENS;
  }

  static List<TrustedQuestionFact> trustedFacts(List<QuestionMessage> messages) {
    List<QuestionMessage> submitted = messages == null ? List.of() : messages;
    if (submitted.size() > MAX_SOURCE_COUNT) {
      throw new IllegalStateException("trusted fact message budget exceeded");
    }
    List<TrustedQuestionFact> facts = new ArrayList<>();
    int sourceCharacters = 0;
    int sourceTokens = 0;
    for (QuestionMessage message : submitted) {
      if (message == null || !isSafeParticipantLabel(message.participant())) {
        throw new IllegalStateException("trusted participant label is invalid");
      }
      sourceCharacters = Math.addExact(sourceCharacters, message.text().length());
      if (sourceCharacters > MAX_SOURCE_CHARACTERS) {
        throw new IllegalStateException("trusted fact character budget exceeded");
      }
      List<TokenValue> values = tokens(message.text());
      sourceTokens = Math.addExact(sourceTokens, values.size());
      if (sourceTokens > MAX_SOURCE_TOKENS) {
        throw new IllegalStateException("trusted fact token budget exceeded");
      }
      for (int scoreIndex = 0; scoreIndex < values.size(); scoreIndex++) {
        if (!isScoreToken(values.get(scoreIndex))) {
          continue;
        }
        PuzzleReference puzzle = findPuzzleReference(values, scoreIndex, null);
        facts.add(
            new TrustedQuestionFact(
                message.messageGuid(),
                message.participant().trim(),
                puzzle == null ? null : puzzle.puzzleId(),
                values.get(scoreIndex).normalized()));
        if (facts.size() > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
          throw new IllegalStateException("trusted fact count budget exceeded");
        }
      }
    }
    return List.copyOf(facts);
  }

  private static PuzzleReference findPuzzleReference(
      List<TokenValue> values, int scoreIndex, boolean[] participantTokens) {
    PuzzleReference result = null;
    int earliest = Math.max(0, scoreIndex - 12);
    for (int index = scoreIndex; index > earliest; index--) {
      if (values.get(index).statementBoundaryBefore()) {
        earliest = index;
        break;
      }
    }
    for (int index = earliest; index + 1 < scoreIndex; index++) {
      if (SCORE_PUZZLE_MARKERS.contains(values.get(index).normalized())
          && isPuzzleId(values.get(index + 1))
          && !isMarked(participantTokens, index + 1)) {
        result =
            new PuzzleReference(index + 1, canonicalPuzzleId(values.get(index + 1).normalized()));
      }
    }
    if (result != null) {
      return result;
    }
    if (scoreIndex - 2 >= earliest
        && SCORE_CONNECTORS.contains(values.get(scoreIndex - 1).normalized())
        && isPuzzleId(values.get(scoreIndex - 2))
        && !isMarked(participantTokens, scoreIndex - 2)) {
      return new PuzzleReference(
          scoreIndex - 2, canonicalPuzzleId(values.get(scoreIndex - 2).normalized()));
    }
    if (scoreIndex - 1 >= earliest
        && isPuzzleId(values.get(scoreIndex - 1))
        && !isMarked(participantTokens, scoreIndex - 1)) {
      return new PuzzleReference(
          scoreIndex - 1, canonicalPuzzleId(values.get(scoreIndex - 1).normalized()));
    }
    int latest = Math.min(values.size() - 1, scoreIndex + 8);
    for (int index = scoreIndex + 1; index < latest; index++) {
      if (values.get(index).statementBoundaryBefore()) {
        break;
      }
      if (SCORE_PUZZLE_MARKERS.contains(values.get(index).normalized())
          && isPuzzleId(values.get(index + 1))
          && !isMarked(participantTokens, index + 1)) {
        return new PuzzleReference(
            index + 1, canonicalPuzzleId(values.get(index + 1).normalized()));
      }
    }
    return null;
  }

  private static boolean isMarked(boolean[] marks, int index) {
    return marks != null && marks[index];
  }

  private static String canonicalPuzzleId(String value) {
    return digits(value);
  }

  private static final class TrustedFacts {
    private static final Set<String> SAFE_SUBJECT_RENDERING_TOKENS =
        Set.of("a", "an", "of", "only", "the");

    private final ParticipantNode participantLabels = new ParticipantNode();
    private final ParticipantNode rawParticipantLabels = new ParticipantNode();
    private final Set<String> supportedScores = new HashSet<>();
    private final Set<ScorePuzzle> supportedScorePuzzles = new HashSet<>();
    private final Set<ScoreParticipant> supportedScoreParticipants = new HashSet<>();
    private final Set<FactTuple> supportedTuples = new HashSet<>();

    private TrustedFacts(
        List<TrustedQuestionFact> trustedFactMetadata, Set<String> citedEvidenceIdentifiers) {
      List<TrustedQuestionFact> metadata =
          trustedFactMetadata == null ? List.of() : trustedFactMetadata;
      Set<String> cited = citedEvidenceIdentifiers == null ? Set.of() : citedEvidenceIdentifiers;
      if (metadata.size() > MAX_SENSITIVE_SOURCE_IDENTIFIERS || cited.size() > MAX_SOURCE_COUNT) {
        throw new IllegalStateException("trusted fact budget exceeded");
      }
      for (TrustedQuestionFact fact : metadata) {
        if (fact == null || !cited.contains(fact.evidenceMessageGuid())) {
          continue;
        }
        if (!isSafeParticipantLabel(fact.participantLabel())) {
          throw new IllegalStateException("trusted participant label is invalid");
        }
        List<TokenValue> participantTokens = tokens(fact.participantLabel());
        List<AttributionAtom> participantAtoms =
            attributionAtoms(fact.participantLabel(), statementIds(fact.participantLabel()));
        String participantKey = participantKey(participantTokens);
        String score = StringUtils.trimToEmpty(fact.score()).toLowerCase(Locale.ROOT);
        if (!SCORE_TOKEN.matcher(score).matches()) {
          throw new IllegalStateException("trusted score is invalid");
        }
        String puzzle = fact.puzzleId() == null ? null : canonicalPuzzleId(fact.puzzleId());
        if (puzzle != null && puzzle.isEmpty()) {
          throw new IllegalStateException("trusted puzzle is invalid");
        }
        addParticipant(participantTokens, participantKey);
        addRawParticipant(participantAtoms, participantKey);
        supportedScores.add(score);
        supportedScoreParticipants.add(new ScoreParticipant(score, participantKey));
        if (puzzle != null) {
          supportedScorePuzzles.add(new ScorePuzzle(score, puzzle));
        }
        supportedTuples.add(new FactTuple(score, participantKey, puzzle));
      }
    }

    private void addParticipant(List<TokenValue> values, String participantKey) {
      ParticipantNode node = participantLabels;
      for (TokenValue value : values) {
        node = node.children.computeIfAbsent(value.normalized(), ignored -> new ParticipantNode());
      }
      if (node.participantKey != null && !node.participantKey.equals(participantKey)) {
        throw new IllegalStateException("ambiguous trusted participant label");
      }
      node.participantKey = participantKey;
    }

    private void addRawParticipant(List<AttributionAtom> values, String participantKey) {
      ParticipantNode node = rawParticipantLabels;
      for (AttributionAtom value : values) {
        node = node.children.computeIfAbsent(value.normalized(), ignored -> new ParticipantNode());
      }
      if (node.participantKey != null && !node.participantKey.equals(participantKey)) {
        throw new IllegalStateException("ambiguous trusted participant label");
      }
      node.participantKey = participantKey;
    }

    private boolean[] validateAndMaskAnswer(String rawAnswer, List<TokenValue> values) {
      boolean[] allowed = new boolean[values.size()];
      ParticipantMatches participants = markParticipantLabels(values, allowed);
      if (!validateRawAttributions(rawAnswer, values, participants.participantTokens())) {
        return null;
      }
      String currentParticipant = null;
      for (int index = 0; index < values.size(); index++) {
        if (values.get(index).statementBoundaryBefore()) {
          currentParticipant = null;
        }
        if (participants.endingAt()[index] != null) {
          currentParticipant = participants.endingAt()[index];
        }
        String token = values.get(index).normalized();
        if (SAFE_GENERATED_TOKENS.contains(token)
            || SAFE_SCORE_RENDERING_TOKENS.contains(token)
            || SAFE_SUBJECT_RENDERING_TOKENS.contains(token)) {
          allowed[index] = true;
        }
        if (!isScoreToken(values.get(index))) {
          continue;
        }
        String participant =
            currentParticipant != null
                ? currentParticipant
                : nextParticipant(values, participants.startingAt(), index + 1);
        PuzzleReference puzzle =
            findPuzzleReference(values, index, participants.participantTokens());
        String puzzleId = puzzle == null ? null : puzzle.puzzleId();
        if (!isSupported(token, participant, puzzleId)) {
          return null;
        }
        allowed[index] = true;
        if (puzzle != null) {
          allowed[puzzle.tokenIndex()] = true;
        }
      }
      return hasUntrustedCanonicalScoreSubject(values, participants.participantTokens())
          ? null
          : allowed;
    }

    private boolean validateRawAttributions(
        String answer, List<TokenValue> values, boolean[] participantTokens) {
      int[] statements = statementIds(answer);
      List<AttributionAtom> answerAtoms = attributionAtoms(answer, statements);
      Map<Integer, List<RawParticipantOccurrence>> participantsByStatement =
          rawParticipantsByStatement(answerAtoms);
      Map<Integer, Integer> participantCursors = new HashMap<>();
      int tokenIndex = 0;
      for (RawScoreOccurrence score : rawScoreOccurrences(answer, statements)) {
        while (tokenIndex < values.size() && values.get(tokenIndex).end() <= score.start()) {
          tokenIndex++;
        }
        PuzzleReference puzzle =
            tokenIndex < values.size()
                    && values.get(tokenIndex).start() <= score.start()
                    && values.get(tokenIndex).end() >= score.end()
                ? findPuzzleReference(values, tokenIndex, participantTokens)
                : null;
        String puzzleId = puzzle == null ? null : puzzle.puzzleId();
        String participant =
            nearestRawParticipant(score, participantsByStatement, participantCursors);
        if (participant == null) {
          if (!isSupported(score.score(), null, puzzleId)) {
            return false;
          }
          continue;
        }
        if (!isSupported(score.score(), participant, puzzleId)) {
          return false;
        }
      }
      return true;
    }

    private Map<Integer, List<RawParticipantOccurrence>> rawParticipantsByStatement(
        List<AttributionAtom> values) {
      Map<Integer, List<RawParticipantOccurrence>> participants = new HashMap<>();
      for (int start = 0; start < values.size(); start++) {
        AttributionAtom first = values.get(start);
        ParticipantNode node = rawParticipantLabels;
        for (int end = start; end < values.size(); end++) {
          AttributionAtom value = values.get(end);
          if (value.statementId() != first.statementId()) {
            break;
          }
          node = node.children.get(value.normalized());
          if (node == null) {
            break;
          }
          if (node.participantKey != null) {
            participants
                .computeIfAbsent(first.statementId(), ignored -> new ArrayList<>())
                .add(
                    new RawParticipantOccurrence(
                        node.participantKey, first.start(), value.end(), first.statementId()));
          }
        }
      }
      return participants;
    }

    private String nearestRawParticipant(
        RawScoreOccurrence score,
        Map<Integer, List<RawParticipantOccurrence>> participantsByStatement,
        Map<Integer, Integer> participantCursors) {
      List<RawParticipantOccurrence> participants =
          participantsByStatement.getOrDefault(score.statementId(), List.of());
      int cursor = participantCursors.getOrDefault(score.statementId(), 0);
      while (cursor < participants.size() && participants.get(cursor).start() < score.start()) {
        cursor++;
      }
      participantCursors.put(score.statementId(), cursor);
      if (cursor > 0) {
        return participants.get(cursor - 1).participant();
      }
      return cursor < participants.size() ? participants.get(cursor).participant() : null;
    }

    private boolean isSupported(String score, String participant, String puzzle) {
      if (participant != null && puzzle != null) {
        return supportedTuples.contains(new FactTuple(score, participant, puzzle));
      }
      if (participant != null) {
        return supportedScoreParticipants.contains(new ScoreParticipant(score, participant));
      }
      if (puzzle != null) {
        return supportedScorePuzzles.contains(new ScorePuzzle(score, puzzle));
      }
      return supportedScores.contains(score);
    }

    private ParticipantMatches markParticipantLabels(List<TokenValue> values, boolean[] allowed) {
      boolean[] participant = new boolean[values.size()];
      String[] endingAt = new String[values.size()];
      String[] startingAt = new String[values.size()];
      for (int start = 0; start < values.size(); start++) {
        ParticipantNode node = participantLabels;
        int end = start;
        while (end < values.size() && end - start < MAX_PARTICIPANT_LABEL_TOKENS) {
          node = node.children.get(values.get(end).normalized());
          if (node == null) {
            break;
          }
          if (node.participantKey != null) {
            startingAt[start] = node.participantKey;
            endingAt[end] = node.participantKey;
            for (int tokenIndex = start; tokenIndex <= end; tokenIndex++) {
              allowed[tokenIndex] = true;
              participant[tokenIndex] = true;
            }
          }
          end++;
        }
      }
      return new ParticipantMatches(participant, endingAt, startingAt);
    }

    private String nextParticipant(List<TokenValue> values, String[] startingAt, int startIndex) {
      int end = Math.min(values.size(), startIndex + MAX_PARTICIPANT_LABEL_TOKENS + 2);
      for (int index = startIndex; index < end; index++) {
        if (values.get(index).statementBoundaryBefore()) {
          break;
        }
        if (startingAt[index] != null) {
          return startingAt[index];
        }
      }
      return null;
    }

    private boolean hasUntrustedCanonicalScoreSubject(
        List<TokenValue> values, boolean[] participant) {
      for (int verbIndex = 0; verbIndex < values.size(); verbIndex++) {
        if (!SCORE_REPORTING_VERBS.contains(values.get(verbIndex).normalized())
            || !hasScoreAfter(values, verbIndex)) {
          continue;
        }
        int clauseStart = verbIndex;
        while (clauseStart > 0 && !values.get(clauseStart).clauseBoundaryBefore()) {
          clauseStart--;
        }
        int cursor = verbIndex - 1;
        while (cursor >= clauseStart
            && SAFE_SUBJECT_RENDERING_TOKENS.contains(values.get(cursor).normalized())) {
          cursor--;
        }
        if (cursor < clauseStart) {
          continue;
        }
        if (!participant[cursor]) {
          return true;
        }
        while (cursor >= clauseStart && participant[cursor]) {
          cursor--;
        }
        for (int index = clauseStart; index <= cursor; index++) {
          if (!SAFE_SUBJECT_RENDERING_TOKENS.contains(values.get(index).normalized())) {
            return true;
          }
        }
      }
      return false;
    }

    private boolean hasScoreAfter(List<TokenValue> values, int verbIndex) {
      int end = Math.min(values.size(), verbIndex + 9);
      for (int index = verbIndex + 1; index < end; index++) {
        if (values.get(index).statementBoundaryBefore()) {
          return false;
        }
        if (isScoreToken(values.get(index))) {
          return true;
        }
      }
      return false;
    }

    private static String participantKey(List<TokenValue> values) {
      StringBuilder key = new StringBuilder();
      for (TokenValue value : values) {
        if (!key.isEmpty()) {
          key.append('\0');
        }
        key.append(value.normalized());
      }
      return key.toString();
    }

    private static final class ParticipantNode {
      private final Map<String, ParticipantNode> children = new HashMap<>();
      private String participantKey;
    }

    private record ParticipantMatches(
        boolean[] participantTokens, String[] endingAt, String[] startingAt) {}

    private record ScorePuzzle(String score, String puzzle) {}

    private record ScoreParticipant(String score, String participant) {}

    private record FactTuple(String score, String participant, String puzzle) {}
  }

  private record PuzzleReference(int tokenIndex, String puzzleId) {}

  private static final class SourceIdentifiers {
    private final Set<String> textIdentifiers = new HashSet<>();
    private final Set<String> endpoints = new HashSet<>();
    private final Set<String> paths = new HashSet<>();
    private final Set<String> sensitiveNumbers = new HashSet<>();
    private final Set<String> supportedNumbers = new HashSet<>();
    private final Set<String> overlapExemptTokens = new HashSet<>();

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

    private void addEndpoint(String value) {
      String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        endpoints.add(normalized);
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

    private void addOverlapExemptToken(String value) {
      String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        overlapExemptTokens.add(normalized);
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

    private Set<String> endpoints() {
      return endpoints;
    }

    private Set<String> sensitiveNumbers() {
      return sensitiveNumbers;
    }

    private Set<String> supportedNumbers() {
      return supportedNumbers;
    }

    private Set<String> overlapExemptTokens() {
      return overlapExemptTokens;
    }

    private void checkSize() {
      long size =
          (long) textIdentifiers.size()
              + endpoints.size()
              + paths.size()
              + sensitiveNumbers.size()
              + supportedNumbers.size()
              + overlapExemptTokens.size();
      if (size > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
        throw new IllegalStateException("sensitive source identifier budget exceeded");
      }
    }
  }

  private record TokenValue(
      int characters,
      String normalized,
      long firstHash,
      long secondHash,
      int start,
      int end,
      boolean clauseBoundaryBefore,
      boolean statementBoundaryBefore) {}

  private record AttributionAtom(String normalized, int start, int end, int statementId) {}

  private record RawScoreOccurrence(String score, int start, int end, int statementId) {}

  private record RawParticipantOccurrence(
      String participant, int start, int end, int statementId) {}

  private record NgramKey(long first, long second) {}

  private record EndpointSuffix(Integer port, String path) {}

  private record EndpointValue(String key, String path) {}
}
