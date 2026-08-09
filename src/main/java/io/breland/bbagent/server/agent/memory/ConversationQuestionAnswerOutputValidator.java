package io.breland.bbagent.server.agent.memory;

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
  private static final int MAX_COMPLETE_TINY_MESSAGE_TOKENS = 4;
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
      Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\d[\\d\\s().-]{5,}\\d(?![\\p{L}\\p{N}])");
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
    requireSafe(answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts, Set.of());
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
        Set.of());
  }

  static void requireSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      Set<String> trustedParticipantLabels) {
    if (!isSafe(
        answer,
        forbiddenEvidenceIdentifiers,
        opaqueEvidenceAliases,
        submittedSourceTexts,
        trustedParticipantLabels)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenEvidenceIdentifiers, List<String> submittedSourceTexts) {
    return isSafe(answer, forbiddenEvidenceIdentifiers, Set.of(), submittedSourceTexts, Set.of());
  }

  static boolean isSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      List<String> submittedSourceTexts,
      Set<String> trustedParticipantLabels) {
    return isSafe(
        answer,
        forbiddenEvidenceIdentifiers,
        Set.of(),
        submittedSourceTexts,
        trustedParticipantLabels);
  }

  private static boolean isSafe(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      Set<String> trustedParticipantLabels) {
    try {
      return evaluate(
          answer,
          forbiddenEvidenceIdentifiers,
          opaqueEvidenceAliases,
          submittedSourceTexts,
          trustedParticipantLabels);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean evaluate(
      String answer,
      Set<String> forbiddenEvidenceIdentifiers,
      Set<String> opaqueEvidenceAliases,
      List<String> submittedSourceTexts,
      Set<String> trustedParticipantLabels) {
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
    TrustedFacts trustedFacts = new TrustedFacts(trustedParticipantLabels);
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
      trustedFacts.collectSourceFacts(values);
      sourceTokens = Math.addExact(sourceTokens, values.size());
      if (sourceTokens > MAX_SOURCE_TOKENS) {
        return false;
      }
      addNgrams(sourceNgrams, values, MATCH_NGRAM_TOKENS);
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
    boolean[] supportedAnswerFacts = trustedFacts.validateAndMaskAnswer(answerTokens);
    if (supportedAnswerFacts == null) {
      return false;
    }
    if (containsSensitiveNumber(normalizedAnswer, sourceIdentifiers)
        || containsSourceIdentifier(normalizedAnswer, sourceIdentifiers)
        || reproducesWholeShortMessage(answerTokens, wholeShortMessages)
        || hasUnsafeCompleteTinyMessageMontage(
            answerTokens, supportedAnswerFacts, completeTinyMessages)) {
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
    String normalized = candidate.strip();
    int digitCount = digits(normalized).length();
    if (digitCount < 7 || digitCount > 15 || isValidCalendarDate(normalized)) {
      return false;
    }
    return normalized.startsWith("+")
        || normalized.indexOf('(') >= 0
        || normalized.indexOf(')') >= 0
        || PHONE_FORMAT.matcher(normalized).matches();
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

    int pathStart = value.indexOf('/');
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
    boolean hasPath = normalizedPath.length() > 1;
    boolean validHost =
        isValidIpv4(normalizedHost)
            || isValidDomain(normalizedHost)
            || ("localhost".equals(normalizedHost) && (port != null || hasPath))
            || (port != null
                && containsAsciiLetter(normalizedHost)
                && isValidHostnameLabel(normalizedHost));
    if (!validHost || isAllowedBareCodeToken(normalizedHost, port, normalizedPath)) {
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
      pathStart = suffix.indexOf('/');
      String rawPort = pathStart < 0 ? suffix.substring(1) : suffix.substring(1, pathStart);
      port = parsePort(rawPort);
      if (port == null) {
        return null;
      }
    }
    if (pathStart == 0 && suffix.charAt(0) != '/') {
      return null;
    }
    String path = pathStart < 0 ? "" : normalizePath(suffix.substring(pathStart));
    return new EndpointSuffix(port, path);
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

  private static boolean isAllowedBareCodeToken(String host, Integer port, String normalizedPath) {
    return port == null
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
    if (value.length() < 2 || value.length() > 45) {
      return false;
    }
    int compression = value.indexOf("::");
    if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) {
      return false;
    }
    String left = compression < 0 ? value : value.substring(0, compression);
    String right = compression < 0 ? "" : value.substring(compression + 2);
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

  private static int validIpv6Groups(String value) {
    if (value.isEmpty()) {
      return 0;
    }
    int groups = 1;
    int groupLength = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index == value.length() || value.charAt(index) == ':') {
        if (groupLength == 0 || groupLength > 4) {
          return -1;
        }
        groups++;
        groupLength = 0;
        continue;
      }
      if (Character.digit(value.charAt(index), 16) < 0) {
        return -1;
      }
      groupLength++;
    }
    return groups - 1;
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
          new TokenValue(token.length(), normalized, firstHash, secondHash, clauseBoundaryBefore));
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

  private static final class TrustedFacts {
    private static final Set<String> SAFE_SUBJECT_RENDERING_TOKENS =
        Set.of("a", "an", "of", "only", "the");

    private final Map<Integer, Set<NgramKey>> participantLabels = new HashMap<>();
    private final Set<String> supportedScores = new HashSet<>();
    private final Set<String> supportedPuzzleIds = new HashSet<>();

    private TrustedFacts(Set<String> trustedParticipantLabels) {
      Set<String> labels = trustedParticipantLabels == null ? Set.of() : trustedParticipantLabels;
      if (labels.size() > MAX_SOURCE_COUNT) {
        throw new IllegalStateException("trusted participant budget exceeded");
      }
      for (String label : labels) {
        String normalized = StringUtils.trimToNull(label);
        if (normalized == null || normalized.length() > MAX_PARTICIPANT_LABEL_CHARACTERS) {
          throw new IllegalStateException("trusted participant label is invalid");
        }
        List<TokenValue> values = tokens(normalized);
        if (values.isEmpty() || values.size() > MAX_PARTICIPANT_LABEL_TOKENS) {
          throw new IllegalStateException("trusted participant label is invalid");
        }
        participantLabels
            .computeIfAbsent(values.size(), ignored -> new HashSet<>())
            .add(hash(values, 0, values.size()));
      }
    }

    private void collectSourceFacts(List<TokenValue> values) {
      for (int scoreIndex = 0; scoreIndex < values.size(); scoreIndex++) {
        if (!isScoreToken(values.get(scoreIndex))) {
          continue;
        }
        supportedScores.add(values.get(scoreIndex).normalized());
        int earliest = Math.max(0, scoreIndex - 5);
        for (int index = earliest; index + 1 < scoreIndex; index++) {
          if (SCORE_PUZZLE_MARKERS.contains(values.get(index).normalized())
              && isPuzzleId(values.get(index + 1))) {
            supportedPuzzleIds.add(values.get(index + 1).normalized());
          }
        }
        if (scoreIndex >= 2
            && SCORE_CONNECTORS.contains(values.get(scoreIndex - 1).normalized())
            && isPuzzleId(values.get(scoreIndex - 2))) {
          supportedPuzzleIds.add(values.get(scoreIndex - 2).normalized());
        } else if (scoreIndex >= 1 && isPuzzleId(values.get(scoreIndex - 1))) {
          supportedPuzzleIds.add(values.get(scoreIndex - 1).normalized());
        }
      }
    }

    private boolean[] validateAndMaskAnswer(List<TokenValue> values) {
      boolean[] allowed = new boolean[values.size()];
      boolean[] participant = new boolean[values.size()];
      markParticipantLabels(values, allowed, participant);
      for (int index = 0; index < values.size(); index++) {
        String token = values.get(index).normalized();
        if (SAFE_SCORE_RENDERING_TOKENS.contains(token)
            || SAFE_SUBJECT_RENDERING_TOKENS.contains(token)) {
          allowed[index] = true;
        }
        if (isScoreToken(values.get(index))) {
          if (!supportedScores.contains(token)) {
            return null;
          }
          allowed[index] = true;
        }
        if (!participant[index]
            && isPuzzleId(values.get(index))
            && isScorePuzzleId(values, index)) {
          if (!supportedPuzzleIds.contains(token)) {
            return null;
          }
          allowed[index] = true;
        }
      }
      return hasUntrustedCanonicalScoreSubject(values, participant) ? null : allowed;
    }

    private void markParticipantLabels(
        List<TokenValue> values, boolean[] allowed, boolean[] participant) {
      for (Map.Entry<Integer, Set<NgramKey>> entry : participantLabels.entrySet()) {
        int size = entry.getKey();
        for (int index = 0; index + size <= values.size(); index++) {
          if (!entry.getValue().contains(hash(values, index, size))) {
            continue;
          }
          for (int tokenIndex = index; tokenIndex < index + size; tokenIndex++) {
            allowed[tokenIndex] = true;
            participant[tokenIndex] = true;
          }
        }
      }
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
        if (isScoreToken(values.get(index))) {
          return true;
        }
      }
      return false;
    }

    private boolean isScorePuzzleId(List<TokenValue> values, int index) {
      int end = Math.min(values.size(), index + 4);
      for (int next = index + 1; next < end; next++) {
        if (isScoreToken(values.get(next))) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class SourceIdentifiers {
    private final Set<String> textIdentifiers = new HashSet<>();
    private final Set<String> endpoints = new HashSet<>();
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

    private void checkSize() {
      long size =
          (long) textIdentifiers.size()
              + endpoints.size()
              + paths.size()
              + sensitiveNumbers.size()
              + supportedNumbers.size();
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
      boolean clauseBoundaryBefore) {}

  private record NgramKey(long first, long second) {}

  private record EndpointSuffix(Integer port, String path) {}

  private record EndpointValue(String key, String path) {}
}
