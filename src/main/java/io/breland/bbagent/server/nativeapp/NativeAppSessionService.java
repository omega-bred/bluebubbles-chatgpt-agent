package io.breland.bbagent.server.nativeapp;

import static io.breland.bbagent.server.TimeSupport.offset;

import io.breland.bbagent.generated.model.NativeAppSessionCreateRequest;
import io.breland.bbagent.generated.model.NativeAppSessionResponse;
import io.breland.bbagent.generated.model.TextingNumberResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.nativeapp.NativeAppSessionEntity;
import io.breland.bbagent.server.agent.persistence.nativeapp.NativeAppSessionRepository;
import io.breland.bbagent.server.appclip.AppAccountTokens;
import io.breland.bbagent.server.sessions.SessionTokens;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import io.breland.bbagent.server.texting.TextingNumberService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NativeAppSessionService {
  public static final String SESSION_HEADER = "X-BlueChat-App-Session";
  public static final String NATIVE_APP_ACCOUNT_ID_CLAIM = "bbagent_native_app_account_id";
  private static final int SESSION_TOKEN_BYTES = 32;
  private static final int START_TOKEN_LENGTH = 8;
  private static final char[] START_TOKEN_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final Pattern START_TOKEN_PATTERN =
      Pattern.compile("\\bBC-([A-Z0-9]{8})\\b", Pattern.CASE_INSENSITIVE);

  private final NativeAppSessionRepository sessionRepository;
  private final AgentAccountRepository accountRepository;
  private final AgentAccountResolver accountResolver;
  private final SubscriptionService subscriptionService;
  private final TextingNumberService textingNumberService;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Duration sessionTtl;
  private final Duration startTokenTtl;

  public NativeAppSessionService(
      NativeAppSessionRepository sessionRepository,
      AgentAccountRepository accountRepository,
      AgentAccountResolver accountResolver,
      SubscriptionService subscriptionService,
      TextingNumberService textingNumberService,
      @Value("${native-app.session-token-ttl-days:365}") long sessionTokenTtlDays,
      @Value("${native-app.start-token-ttl-days:365}") long startTokenTtlDays) {
    this.sessionRepository = sessionRepository;
    this.accountRepository = accountRepository;
    this.accountResolver = accountResolver;
    this.subscriptionService = subscriptionService;
    this.textingNumberService = textingNumberService;
    this.sessionTtl = Duration.ofDays(Math.max(1, sessionTokenTtlDays));
    this.startTokenTtl = Duration.ofDays(Math.max(1, startTokenTtlDays));
  }

  @Transactional
  public NativeAppSessionResponse createSession(NativeAppSessionCreateRequest request) {
    Instant now = Instant.now();
    AgentAccountEntity account =
        accountRepository.save(new AgentAccountEntity(UUID.randomUUID().toString(), now, now));
    String sessionToken = SessionTokens.randomUrlToken(secureRandom, SESSION_TOKEN_BYTES);
    String startToken = newStartToken();
    String appAccountToken = AppAccountTokens.forAccountId(account.getAccountId()).toString();
    NativeAppSessionEntity session =
        new NativeAppSessionEntity(
            SessionTokens.sha256Hash(sessionToken),
            account.getAccountId(),
            appAccountToken,
            SessionTokens.sha256Hash(startToken),
            now.plus(startTokenTtl),
            now.plus(sessionTtl),
            now,
            now);
    sessionRepository.save(session);
    return response(sessionToken, session, startToken);
  }

  @Transactional
  public Optional<AuthenticatedNativeAppSession> authenticate(String sessionToken) {
    if (StringUtils.isBlank(sessionToken)) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    return sessionRepository
        .findById(SessionTokens.sha256Hash(sessionToken))
        .filter(session -> session.getRevokedAt() == null)
        .filter(session -> session.getExpiresAt().isAfter(now))
        .flatMap(
            session ->
                accountRepository
                    .findById(session.getAccountId())
                    .map(
                        account -> {
                          session.setLastUsedAt(now);
                          session.setUpdatedAt(now);
                          sessionRepository.save(session);
                          return new AuthenticatedNativeAppSession(
                              account.getAccountId(), session.getExpiresAt());
                        }));
  }

  @Transactional
  public NativeAppSessionResponse getSession(String sessionToken, String accountId) {
    NativeAppSessionEntity session =
        sessionRepository
            .findById(SessionTokens.sha256Hash(sessionToken))
            .filter(candidate -> candidate.getAccountId().equals(accountId))
            .filter(candidate -> candidate.getRevokedAt() == null)
            .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid native app session"));
    String startToken = rotateStartToken(session);
    return response(sessionToken, session, startToken);
  }

  @Transactional
  public IncomingMessage claimStartToken(IncomingMessage message) {
    if (message == null || StringUtils.isBlank(message.text())) {
      return message;
    }
    Matcher matcher = START_TOKEN_PATTERN.matcher(message.text());
    if (!matcher.find()) {
      return message;
    }
    String startToken = "BC-" + matcher.group(1).toUpperCase();
    Instant now = Instant.now();
    Optional<NativeAppSessionEntity> claimedSession =
        sessionRepository
            .findByStartTokenHash(SessionTokens.sha256Hash(startToken))
            .filter(session -> session.getRevokedAt() == null)
            .filter(session -> session.getStartTokenExpiresAt().isAfter(now));
    if (claimedSession.isEmpty()) {
      return message;
    }
    NativeAppSessionEntity session = claimedSession.get();
    accountResolver.linkIncomingMessageToAccount(session.getAccountId(), message);
    if (session.getClaimedAt() == null) {
      session.setClaimedAt(now);
    }
    session.setUpdatedAt(now);
    sessionRepository.save(session);
    return message.withText(stripStartToken(message.text()));
  }

  @Transactional(readOnly = true)
  public Optional<String> accountIdForAppAccountToken(String appAccountToken) {
    String trimmed = StringUtils.trimToNull(appAccountToken);
    if (trimmed == null) {
      return Optional.empty();
    }
    return sessionRepository
        .findByAppAccountToken(trimmed)
        .map(NativeAppSessionEntity::getAccountId);
  }

  private NativeAppSessionResponse response(
      String sessionToken, NativeAppSessionEntity session, String startToken) {
    TextingNumberResponse texting = textingNumberService.response(startMessage(startToken));
    return new NativeAppSessionResponse()
        .sessionToken(sessionToken)
        .expiresAt(offset(session.getExpiresAt()))
        .accountId(session.getAccountId())
        .appAccountToken(UUID.fromString(session.getAppAccountToken()))
        .subscription(subscriptionService.getAccountSubscription(session.getAccountId()))
        .storekitProductIds(subscriptionService.storeKitProductIds())
        .texting(texting);
  }

  private String rotateStartToken(NativeAppSessionEntity session) {
    Instant now = Instant.now();
    String startToken = newStartToken();
    session.setStartTokenHash(SessionTokens.sha256Hash(startToken));
    session.setStartTokenExpiresAt(now.plus(startTokenTtl));
    session.setUpdatedAt(now);
    sessionRepository.save(session);
    return startToken;
  }

  private String startMessage(String startToken) {
    return "Hi BlueChatAI, let's start. Code: " + startToken;
  }

  private String stripStartToken(String text) {
    String stripped = START_TOKEN_PATTERN.matcher(text).replaceAll("").replace("Code:", "");
    stripped = stripped.replaceAll("\\s{2,}", " ").trim();
    return StringUtils.defaultIfBlank(stripped, "Hi BlueChatAI, let's start.");
  }

  private String newStartToken() {
    StringBuilder value = new StringBuilder("BC-");
    for (int index = 0; index < START_TOKEN_LENGTH; index++) {
      value.append(START_TOKEN_ALPHABET[secureRandom.nextInt(START_TOKEN_ALPHABET.length)]);
    }
    return value.toString();
  }

  public record AuthenticatedNativeAppSession(String accountId, Instant expiresAt) {}
}
