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
  private static final int MAX_PAYMENT_CARD_SCAN_CHARACTERS = MAX_SOURCE_CHARACTERS;
  private static final Pattern PHONE_CONTEXT =
      Pattern.compile("(?i)\\b(?:phone|call|text|contact|mobile|telephone|tel|sms|fax|reach)\\b");
  private static final Pattern PHONE_FORMAT =
      Pattern.compile("(?:\\d{3}[- .]\\d{4}|\\d{3}[- .]\\d{3}[- .]\\d{4}|\\d{3}-\\d{2}-\\d{4})");
  private static final Set<String> GENERIC_STOPWORDS =
      Set.of(
          "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "had", "has", "have",
          "in", "is", "it", "of", "on", "or", "that", "the", "to", "was", "were", "with");
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
        || containsPaymentCardLike(normalizedAnswer)
        || INSTRUCTION_LEAKAGE.matcher(normalizedAnswer).find()) {
      return false;
    }

    List<TokenValue> answerTokens = tokens(normalizedAnswer);
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

    boolean[] overlapExemptions = new boolean[answerTokens.size()];
    if (containsSensitiveNumber(normalizedAnswer, sourceIdentifiers)
        || containsSourceIdentifier(normalizedAnswer, sourceIdentifiers)
        || reproducesWholeShortMessage(answerTokens, wholeShortMessages)
        || hasUnsafeCompleteTinyMessageMontage(
            answerTokens, overlapExemptions, completeTinyMessages, sourceIdentifiers)
        || hasUnsafeGlobalUnigramMontage(
            answerTokens, overlapExemptions, sourceUnigrams, sourceIdentifiers)
        || hasUnsafeRawMessageCopy(
            normalizedAnswer, answerTokens, overlapExemptions, sources, sourceIdentifiers)) {
      return false;
    }
    return !hasUnsafeCumulativeOverlap(answerTokens, overlapExemptions, sourceNgrams);
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
    return EMAIL.matcher(answer).find()
        || SCHEME.matcher(answer).find()
        || WWW.matcher(answer).find()
        || !endpoints(answer).isEmpty();
  }

  private static void collectSourceIdentifiers(String source, SourceIdentifiers identifiers) {
    collectMatches(source, EMAIL, identifiers::addText);
    collectMatches(source, SCHEME, identifiers::addText);
    collectMatches(source, WWW, identifiers::addText);
    for (EndpointValue endpoint : endpoints(source)) {
      identifiers.addEndpoint(endpoint.key());
      identifiers.addPath(endpoint.path());
    }
    collectMatches(source, PATH_IDENTIFIER, identifiers::addPath);
    collectPaymentCardIdentifiers(source, identifiers);

    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(source);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      String candidateDigits = digits(candidate);
      if (isValidCalendarDate(normalizePhoneCandidate(candidate))) {
        for (TokenValue value : tokens(candidate)) {
          identifiers.addOverlapExemptToken(value.normalized());
        }
      } else if (candidateDigits.length() >= 7
          && (isRealisticPhone(candidate) || hasPhoneContext(source, formattedMatcher))) {
        identifiers.addSensitiveNumber(candidateDigits);
      }
    }
    collectNumberMatches(source, BARE_LONG_NUMBER, identifiers);
    collectNumberMatches(source, GROUPED_LONG_NUMBER, identifiers);
  }

  private static void collectMatches(
      String source, Pattern pattern, java.util.function.Consumer<String> consumer) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      consumer.accept(matcher.group());
    }
  }

  private static void collectNumberMatches(
      String source, Pattern pattern, SourceIdentifiers identifiers) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      identifiers.addSensitiveNumber(digits(matcher.group()));
    }
  }

  private static boolean containsSourceIdentifier(String answer, SourceIdentifiers identifiers) {
    Matcher emailMatcher = EMAIL.matcher(answer);
    while (emailMatcher.find()) {
      if (identifiers.textIdentifiers().contains(emailMatcher.group().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    for (EndpointValue endpoint : endpoints(answer)) {
      if (identifiers.endpoints().contains(endpoint.key())) {
        return true;
      }
    }
    Matcher pathMatcher = PATH_IDENTIFIER.matcher(answer);
    while (pathMatcher.find()) {
      if (identifiers.paths().contains(normalizePath(pathMatcher.group()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsSensitiveNumber(String answer, SourceIdentifiers identifiers) {
    Matcher formattedMatcher = NUMBER_CANDIDATE.matcher(answer);
    while (formattedMatcher.find()) {
      String candidate = formattedMatcher.group();
      String candidateDigits = digits(candidate);
      if (candidateDigits.length() >= 7
          && !isValidCalendarDate(normalizePhoneCandidate(candidate))
          && (identifiers.sensitiveNumbers().contains(candidateDigits)
              || isRealisticPhone(candidate)
              || hasPhoneContext(answer, formattedMatcher))) {
        return true;
      }
    }
    return containsSensitiveNumberMatch(answer, BARE_LONG_NUMBER, identifiers)
        || containsSensitiveNumberMatch(answer, GROUPED_LONG_NUMBER, identifiers);
  }

  private static void collectPaymentCardIdentifiers(String source, SourceIdentifiers identifiers) {
    for (String candidate : paymentCardCandidates(source)) {
      identifiers.addSensitiveNumber(candidate);
    }
  }

  private static boolean containsPaymentCardLike(String text) {
    return !paymentCardCandidates(text).isEmpty();
  }

  private static List<String> paymentCardCandidates(String text) {
    if (text.length() > MAX_PAYMENT_CARD_SCAN_CHARACTERS) {
      throw new IllegalStateException("payment-card character budget exceeded");
    }
    List<String> candidates = new ArrayList<>();
    int index = 0;
    while (index < text.length()) {
      if (!Character.isDigit(text.charAt(index))
          || (index > 0 && Character.isLetterOrDigit(text.charAt(index - 1)))) {
        index++;
        continue;
      }
      int cursor = index;
      int digitCount = 0;
      int lastDigitEnd = index;
      StringBuilder digits = new StringBuilder(19);
      while (cursor < text.length()) {
        char value = text.charAt(cursor);
        if (Character.isDigit(value)) {
          digitCount++;
          lastDigitEnd = cursor + 1;
          if (digitCount <= 19) {
            digits.append(value);
          }
          cursor++;
          continue;
        }
        if (!isPaymentCardSeparator(value)) {
          break;
        }
        int nextDigit = cursor;
        while (nextDigit < text.length() && isPaymentCardSeparator(text.charAt(nextDigit))) {
          nextDigit++;
        }
        if (nextDigit == text.length() || !Character.isDigit(text.charAt(nextDigit))) {
          cursor = nextDigit;
          break;
        }
        cursor = nextDigit;
      }
      boolean tokenEnd =
          lastDigitEnd == text.length() || !Character.isLetterOrDigit(text.charAt(lastDigitEnd));
      String rawCandidate = text.substring(index, lastDigitEnd);
      if (tokenEnd
          && digitCount >= 13
          && digitCount <= 19
          && isPlausiblePaymentCardGrouping(rawCandidate)) {
        candidates.add(digits.toString());
        if (candidates.size() > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
          throw new IllegalStateException("payment-card work budget exceeded");
        }
      }
      index = Math.max(index + 1, cursor);
    }
    return List.copyOf(candidates);
  }

  private static boolean isPlausiblePaymentCardGrouping(String candidate) {
    int groupDigits = 0;
    int groupCount = 0;
    boolean separated = false;
    boolean betweenGroups = false;
    for (int index = 0; index < candidate.length(); index++) {
      char value = candidate.charAt(index);
      if (Character.isDigit(value)) {
        if (betweenGroups) {
          if (groupDigits < 3 || groupDigits > 6) {
            return false;
          }
          groupCount++;
          groupDigits = 0;
          betweenGroups = false;
        }
        groupDigits++;
      } else if (isPaymentCardSeparator(value)) {
        separated = true;
        betweenGroups = groupDigits > 0;
      } else {
        return false;
      }
    }
    if (!separated) {
      return true;
    }
    if (groupDigits < 1 || groupDigits > 6) {
      return false;
    }
    return groupCount + 1 >= 3;
  }

  private static boolean isPaymentCardSeparator(char value) {
    return Character.isWhitespace(value)
        || Character.isSpaceChar(value)
        || value == '-'
        || (value >= '\u2010' && value <= '\u2015')
        || value == '\u2212'
        || value == '\uFE58'
        || value == '\uFE63'
        || value == '\uFF0D';
  }

  private static boolean containsSensitiveNumberMatch(
      String answer, Pattern pattern, SourceIdentifiers identifiers) {
    Matcher matcher = pattern.matcher(answer);
    while (matcher.find()) {
      if (identifiers.sensitiveNumbers().contains(digits(matcher.group()))) {
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
    return punctuationColon > 0 && punctuationColon + 1 < value.length()
        ? parseEndpointValue(value.substring(punctuationColon + 1))
        : null;
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
      return new EndpointValue(
          endpointKey("[" + authority.toLowerCase(Locale.ROOT) + "]", suffix), suffix.path());
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
            || "localhost".equals(normalizedHost)
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
    int digitCount = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index < value.length() && Character.isDigit(value.charAt(index))) {
        if (++digitCount > 3) {
          return false;
        }
        octet = octet * 10 + value.charAt(index) - '0';
        continue;
      }
      if (digitCount == 0
          || octet > 255
          || (index < value.length() && value.charAt(index) != '.')) {
        return false;
      }
      octets++;
      digitCount = 0;
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
      boolean[] exemptions,
      Map<String, Integer> sourceUnigrams,
      SourceIdentifiers identifiers) {
    Map<String, Integer> used = new HashMap<>();
    int matchedTokens = 0;
    int matchedCharacters = 0;
    for (int index = 0; index < answerTokens.size(); index++) {
      TokenValue token = answerTokens.get(index);
      if (isOverlapExempt(token, exemptions[index], identifiers)) {
        continue;
      }
      int available = sourceUnigrams.getOrDefault(token.normalized(), 0);
      int consumed = used.getOrDefault(token.normalized(), 0);
      if (consumed >= available) {
        continue;
      }
      used.put(token.normalized(), consumed + 1);
      matchedTokens++;
      matchedCharacters = Math.addExact(matchedCharacters, token.characters());
      if (matchedTokens >= UNSAFE_GLOBAL_MATCHED_SOURCE_TOKEN_BOUNDARY
          || matchedCharacters >= UNSAFE_GLOBAL_MATCHED_SOURCE_CHARACTER_BOUNDARY) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasUnsafeRawMessageCopy(
      String answer,
      List<TokenValue> answerTokens,
      boolean[] exemptions,
      List<String> sources,
      SourceIdentifiers identifiers) {
    String normalizedAnswer = normalizeRawText(answer);
    List<TokenValue> normalizedAnswerTokens = tokens(normalizedAnswer);
    if (normalizedAnswerTokens.size() != answerTokens.size()) {
      return true;
    }
    int[] nextNonExemptTokenEnd =
        nextNonExemptTokenEnd(normalizedAnswer, normalizedAnswerTokens, exemptions, identifiers);
    boolean[] matchedCharacters = new boolean[normalizedAnswer.length()];
    Set<String> distinctSources = new HashSet<>();
    int matchedCharacterCount = 0;
    for (String source : sources) {
      String normalizedSource = normalizeRawText(source);
      if (normalizedSource.isEmpty() || !distinctSources.add(normalizedSource)) {
        continue;
      }
      List<TokenValue> sourceTokens = tokens(normalizedSource);
      for (int matchStart = normalizedAnswer.indexOf(normalizedSource);
          matchStart >= 0;
          matchStart = normalizedAnswer.indexOf(normalizedSource, matchStart + 1)) {
        int matchEnd = matchStart + normalizedSource.length();
        if (sourceTokens.isEmpty()) {
          if (isMaterialTokenlessMessage(normalizedSource)) {
            return true;
          }
          continue;
        }
        if (nextNonExemptTokenEnd[matchStart] > matchEnd) {
          continue;
        }
        for (int index = matchStart; index < matchEnd; index++) {
          if (!matchedCharacters[index]) {
            matchedCharacters[index] = true;
            if (++matchedCharacterCount >= UNSAFE_GLOBAL_MATCHED_SOURCE_CHARACTER_BOUNDARY) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static int[] nextNonExemptTokenEnd(
      String answer, List<TokenValue> tokens, boolean[] exemptions, SourceIdentifiers identifiers) {
    int[] nextEnd = new int[answer.length() + 1];
    int tokenIndex = tokens.size() - 1;
    int nearestEnd = Integer.MAX_VALUE;
    for (int position = answer.length(); position >= 0; position--) {
      while (tokenIndex >= 0 && tokens.get(tokenIndex).start() == position) {
        TokenValue token = tokens.get(tokenIndex);
        if (!isOverlapExempt(token, exemptions[tokenIndex], identifiers)) {
          nearestEnd = token.end();
        }
        tokenIndex--;
      }
      nextEnd[position] = nearestEnd;
    }
    return nextEnd;
  }

  private static boolean isMaterialTokenlessMessage(String value) {
    int codePoints = value.codePointCount(0, value.length());
    if (codePoints > 1) {
      return true;
    }
    if (codePoints == 0) {
      return false;
    }
    int type = Character.getType(value.codePointAt(0));
    return type == Character.MATH_SYMBOL
        || type == Character.CURRENCY_SYMBOL
        || type == Character.MODIFIER_SYMBOL
        || type == Character.OTHER_SYMBOL;
  }

  private static String normalizeRawText(String value) {
    StringBuilder normalized = new StringBuilder(value == null ? 0 : value.length());
    boolean pendingSpace = false;
    for (int index = 0; value != null && index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
        pendingSpace = !normalized.isEmpty();
      } else {
        if (pendingSpace) {
          normalized.append(' ');
          pendingSpace = false;
        }
        normalized.append(Character.toLowerCase(character));
      }
    }
    return normalized.toString();
  }

  private static boolean reproducesWholeShortMessage(
      List<TokenValue> answerTokens, Map<Integer, Set<NgramKey>> messages) {
    for (Map.Entry<Integer, Set<NgramKey>> entry : messages.entrySet()) {
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
      boolean[] exemptions,
      Map<Integer, Set<NgramKey>> messages,
      SourceIdentifiers identifiers) {
    boolean[] matched = new boolean[answerTokens.size()];
    int matches = 0;
    for (int size = 2; size >= 1; size--) {
      Set<NgramKey> sourceMessages = messages.get(size);
      if (sourceMessages == null) {
        continue;
      }
      for (int index = 0; index + size <= answerTokens.size(); index++) {
        if (!sourceMessages.contains(hash(answerTokens, index, size))
            || allOverlapExempt(answerTokens, exemptions, index, size, identifiers)
            || (size == 1 && matched[index])) {
          continue;
        }
        matches++;
        for (int tokenIndex = index; tokenIndex < index + size; tokenIndex++) {
          if (!isOverlapExempt(answerTokens.get(tokenIndex), exemptions[tokenIndex], identifiers)) {
            matched[tokenIndex] = true;
          }
        }
      }
    }
    int matchedTokenCount = 0;
    int matchedCharacterCount = 0;
    for (int index = 0; index < matched.length; index++) {
      if (matched[index]) {
        matchedTokenCount++;
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
      boolean[] exemptions,
      int from,
      int size,
      SourceIdentifiers identifiers) {
    for (int index = from; index < from + size; index++) {
      if (!isOverlapExempt(values.get(index), exemptions[index], identifiers)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isOverlapExempt(
      TokenValue value, boolean exempt, SourceIdentifiers identifiers) {
    return exempt
        || GENERIC_STOPWORDS.contains(value.normalized())
        || identifiers.overlapExemptTokens().contains(value.normalized());
  }

  private static boolean hasUnsafeCumulativeOverlap(
      List<TokenValue> answerTokens, boolean[] exemptions, Set<NgramKey> sourceNgrams) {
    if (answerTokens.size() < MATCH_NGRAM_TOKENS || sourceNgrams.isEmpty()) {
      return false;
    }
    boolean[] matched = new boolean[answerTokens.size()];
    for (int index = 0; index + MATCH_NGRAM_TOKENS <= answerTokens.size(); index++) {
      if (sourceNgrams.contains(hash(answerTokens, index, MATCH_NGRAM_TOKENS))) {
        for (int offset = 0; offset < MATCH_NGRAM_TOKENS; offset++) {
          matched[index + offset] = true;
        }
      }
    }
    int matchedTokens = 0;
    int matchedCharacters = 0;
    boolean previousMatched = false;
    for (int index = 0; index < matched.length; index++) {
      if (!matched[index]
          || exemptions[index]
          || GENERIC_STOPWORDS.contains(answerTokens.get(index).normalized())) {
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
    int index = 0;
    while (index < value.length()) {
      while (index < value.length() && !Character.isLetterOrDigit(value.charAt(index))) {
        index++;
      }
      if (index == value.length()) {
        break;
      }
      int start = index++;
      while (index < value.length()) {
        char character = value.charAt(index);
        if (Character.isLetterOrDigit(character)) {
          index++;
        } else if (isTokenSeparator(character)
            && index + 1 < value.length()
            && Character.isLetterOrDigit(value.charAt(index + 1))) {
          index++;
        } else {
          break;
        }
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
      tokens.add(new TokenValue(token.length(), normalized, firstHash, secondHash, start, index));
    }
    return List.copyOf(tokens);
  }

  private static boolean isTokenSeparator(char value) {
    return ",.'/-".indexOf(value) >= 0;
  }

  static boolean isSafeParticipantLabel(String label) {
    String normalized = StringUtils.trimToNull(label);
    if (normalized == null || normalized.length() > MAX_PARTICIPANT_LABEL_CHARACTERS) {
      return false;
    }
    List<TokenValue> values = tokens(normalized);
    return !values.isEmpty() && values.size() <= MAX_PARTICIPANT_LABEL_TOKENS;
  }

  private static final class SourceIdentifiers {
    private final Set<String> textIdentifiers = new HashSet<>();
    private final Set<String> endpoints = new HashSet<>();
    private final Set<String> paths = new HashSet<>();
    private final Set<String> sensitiveNumbers = new HashSet<>();
    private final Set<String> overlapExemptTokens = new HashSet<>();

    private void addText(String value) {
      addNormalized(textIdentifiers, value);
    }

    private void addEndpoint(String value) {
      addNormalized(endpoints, value);
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

    private void addOverlapExemptToken(String value) {
      addNormalized(overlapExemptTokens, value);
    }

    private void addNormalized(Set<String> target, String value) {
      String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
      if (!normalized.isEmpty()) {
        target.add(normalized);
        checkSize();
      }
    }

    private Set<String> textIdentifiers() {
      return textIdentifiers;
    }

    private Set<String> endpoints() {
      return endpoints;
    }

    private Set<String> paths() {
      return paths;
    }

    private Set<String> sensitiveNumbers() {
      return sensitiveNumbers;
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
              + overlapExemptTokens.size();
      if (size > MAX_SENSITIVE_SOURCE_IDENTIFIERS) {
        throw new IllegalStateException("sensitive source identifier budget exceeded");
      }
    }
  }

  private record TokenValue(
      int characters, String normalized, long firstHash, long secondHash, int start, int end) {}

  private record NgramKey(long first, long second) {}

  private record EndpointSuffix(Integer port, String path) {}

  private record EndpointValue(String key, String path) {}
}
