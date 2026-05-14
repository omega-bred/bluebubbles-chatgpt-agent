package io.breland.bbagent.server.agent.tools.gcal;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarListEntry;
import io.breland.bbagent.server.agent.persistence.GcalCredentialEntity;
import io.breland.bbagent.server.agent.persistence.GcalCredentialRepository;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class GcalClient {
  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static final Collection<String> SCOPES = List.of(CalendarScopes.CALENDAR);
  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
  private static final String STORE_ID = StoredCredential.DEFAULT_DATA_STORE_ID;
  private static final String ACCOUNT_PENDING_PREFIX = "pending::";

  private final ObjectMapper objectMapper;
  private final String clientSecretPath;
  private final String clientSecret;
  private final String redirectUri;
  private final String applicationName;
  private final Algorithm stateAlgorithm;
  private final GcalCredentialRepository credentialRepository;

  public GcalClient(
      @Value("${gcal.oauth.client_secret_path:}") String clientSecretPath,
      @Value("${gcal.oauth.client_secret:}") String clientSecret,
      @Value("${gcal.oauth.redirect_uri:}") String redirectUri,
      @Value("${gcal.oauth.state_secret:}") String stateSecret,
      @Value("${gcal.application_name:iMessage + ChatGPT}") String applicationName,
      GcalCredentialRepository credentialRepository,
      ObjectMapper objectMapper) {
    this.clientSecretPath = clientSecretPath;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.stateAlgorithm = StringUtils.hasText(stateSecret) ? Algorithm.HMAC256(stateSecret) : null;
    this.applicationName = applicationName;
    this.credentialRepository = credentialRepository;
    this.objectMapper = objectMapper;
  }

  public boolean isConfigured() {
    boolean configured = isClientSecretPathConfigured() || StringUtils.hasText(clientSecret);
    if (!configured) {
      log.warn(
          "Gcal client is not configured, no direct secret, path does not exist: {}",
          clientSecretPath);
    }
    return configured;
  }

  public String getAuthUrl(String accountBase, String chatGuid, String messageGuid) {
    if (!isConfigured()) {
      return null;
    }
    String pendingKey =
        accountBase + AccountKeyParts.ACCOUNT_DELIM + ACCOUNT_PENDING_PREFIX + UUID.randomUUID();
    String state = createOauthState(accountBase, pendingKey, chatGuid, messageGuid);
    if (state == null) {
      return null;
    }
    GoogleAuthorizationCodeFlow flow = buildFlow();
    return flow.newAuthorizationUrl().setRedirectUri(redirectUri).setState(state).build();
  }

  public Optional<String> exchangeCode(String accountBase, String pendingKey, String code) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    try {
      GoogleAuthorizationCodeFlow flow = buildFlow();
      TokenResponse tokenResponse =
          flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
      flow.createAndStoreCredential(tokenResponse, pendingKey);
      String accountId = resolveAccountId(pendingKey);
      if (!StringUtils.hasText(accountId)) {
        return Optional.empty();
      }
      String scopedKey = scopeAccountKey(accountBase, accountId);
      if (!scopedKey.equals(pendingKey)) {
        migrateCredentialKey(pendingKey, scopedKey);
      }
      return Optional.of(accountId);
    } catch (Exception e) {
      log.warn("Failed to exchange code", e);
      return Optional.empty();
    }
  }

  public List<String> listAccounts() {
    return credentialRepository.findAllAccountKeysByStoreId(STORE_ID);
  }

  public List<String> listAccountsFor(String accountBase) {
    if (!StringUtils.hasText(accountBase)) {
      return List.of();
    }
    List<String> accountIds =
        credentialRepository.findAccountIdsByStoreIdAndAccountBase(STORE_ID, accountBase);
    if (accountIds == null || accountIds.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String accountId : accountIds) {
      if (!StringUtils.hasText(accountId)) {
        continue;
      }
      if (accountId.startsWith(ACCOUNT_PENDING_PREFIX)) {
        continue;
      }
      result.add(accountId);
    }
    return List.copyOf(result);
  }

  public String scopeAccountKey(String accountBase, String accountId) {
    if (!StringUtils.hasText(accountId)) {
      return accountBase;
    }
    if (accountId.contains(AccountKeyParts.ACCOUNT_DELIM)) {
      return accountId;
    }
    if (AccountKeyParts.DEFAULT_ACCOUNT_ID.equalsIgnoreCase(accountId)) {
      return accountBase;
    }
    if (!StringUtils.hasText(accountBase)) {
      return accountId;
    }
    return accountBase + AccountKeyParts.ACCOUNT_DELIM + accountId;
  }

  public boolean revokeAccount(String accountKey) {
    if (!StringUtils.hasText(accountKey)) {
      return false;
    }
    return credentialRepository.deleteByStoreIdAndAccountKey(STORE_ID, accountKey) > 0;
  }

  public Calendar getCalendarService(String accountKey) throws IOException {
    Credential credential = getCredential(accountKey);
    try {
      NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
      return new Calendar.Builder(httpTransport, JSON_FACTORY, credential)
          .setApplicationName(applicationName)
          .build();
    } catch (Exception e) {
      throw new IOException("Failed to create calendar client", e);
    }
  }

  public com.google.api.client.util.DateTime parseDateTime(String value, ZoneId fallbackZone) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      Instant instant = Instant.parse(value);
      return new com.google.api.client.util.DateTime(instant.toEpochMilli());
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      ZonedDateTime zoned = ZonedDateTime.parse(value);
      return new com.google.api.client.util.DateTime(zoned.toInstant().toEpochMilli());
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDateTime local = LocalDateTime.parse(value);
      ZoneId zone = fallbackZone != null ? fallbackZone : ZoneId.systemDefault();
      return new com.google.api.client.util.DateTime(local.atZone(zone).toInstant().toEpochMilli());
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDate date = LocalDate.parse(value);
      ZoneId zone = fallbackZone != null ? fallbackZone : ZoneId.systemDefault();
      return new com.google.api.client.util.DateTime(
          date.atStartOfDay(zone).toInstant().toEpochMilli());
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  public ObjectMapper mapper() {
    return objectMapper;
  }

  public Optional<OauthState> parseOauthState(String state) {
    if (!StringUtils.hasText(state) || stateAlgorithm == null) {
      return Optional.empty();
    }
    try {
      JWTVerifier verifier = JWT.require(stateAlgorithm).build();
      DecodedJWT jwt = verifier.verify(state);
      String accountBase = jwt.getClaim("account_base").asString();
      String pendingKey = jwt.getClaim("pending_key").asString();
      String chatGuid = jwt.getClaim("chat_guid").asString();
      String messageGuid = jwt.getClaim("message_guid").asString();
      if (!StringUtils.hasText(accountBase) || !StringUtils.hasText(chatGuid)) {
        return Optional.empty();
      }
      if (!StringUtils.hasText(pendingKey)) {
        return Optional.empty();
      }
      return Optional.of(new OauthState(accountBase, pendingKey, chatGuid, messageGuid));
    } catch (JWTVerificationException e) {
      log.warn("Failed to parse OAuth state", e);
      return Optional.empty();
    }
  }

  private Credential getCredential(String accountKey) throws IOException {
    if (!isConfigured()) {
      throw new IOException("Google Calendar client not configured");
    }
    GoogleAuthorizationCodeFlow flow = buildFlow();
    Credential credential = flow.loadCredential(accountKey);
    if (credential == null) {
      throw new IOException("No credentials found for account: " + accountKey);
    }
    return credential;
  }

  private GoogleAuthorizationCodeFlow buildFlow() {
    if (isClientSecretPathConfigured()) {
      try (FileInputStream input = new FileInputStream(clientSecretPath)) {
        GoogleClientSecrets clientSecrets =
            GoogleClientSecrets.load(
                JSON_FACTORY, new InputStreamReader(input, StandardCharsets.UTF_8));
        return buildFlow(clientSecrets);
      } catch (Exception e) {
        log.error("Failed to load Google client secrets via path", e);
        throw new IllegalStateException("Failed to load Google client secrets", e);
      }
    } else if (StringUtils.hasText(clientSecret)) {
      try {
        GoogleClientSecrets clientSecrets =
            GoogleClientSecrets.load(JSON_FACTORY, new StringReader(clientSecret));
        return buildFlow(clientSecrets);
      } catch (Exception e) {
        log.error("Failed to load Google client secrets via direct", e);
        throw new IllegalStateException("Failed to load Google client secrets", e);
      }
    }
    log.warn("Failed to load Google client secrets");
    throw new IllegalStateException("Failed to load Google client secrets");
  }

  private boolean isClientSecretPathConfigured() {
    return StringUtils.hasText(clientSecretPath) && Files.exists(Paths.get(clientSecretPath));
  }

  private GoogleAuthorizationCodeFlow buildFlow(GoogleClientSecrets clientSecrets)
      throws Exception {
    DataStoreFactory dataStoreFactory =
        new PostgresCredentialDataStoreFactory(credentialRepository);
    return new GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
        .setDataStoreFactory(dataStoreFactory)
        .setAccessType("offline")
        .build();
  }

  private String createOauthState(
      String accountBase, String pendingKey, String chatGuid, String messageGuid) {
    if (stateAlgorithm == null) {
      return null;
    }
    if (!StringUtils.hasText(accountBase) || !StringUtils.hasText(chatGuid)) {
      return null;
    }
    try {
      Instant now = Instant.now();
      return JWT.create()
          .withIssuedAt(Date.from(now))
          .withExpiresAt(Date.from(now.plus(OAUTH_STATE_TTL)))
          .withClaim("account_base", accountBase)
          .withClaim("pending_key", pendingKey)
          .withClaim("chat_guid", chatGuid)
          .withClaim("message_guid", messageGuid)
          .sign(stateAlgorithm);
    } catch (Exception e) {
      log.warn("Failed to build OAuth state", e);
      return null;
    }
  }

  public record OauthState(
      String accountBase, String pendingKey, String chatGuid, String messageGuid) {}

  private String resolveAccountId(String accountKey) {
    try {
      Calendar client = getCalendarService(accountKey);
      CalendarListEntry primary = client.calendarList().get("primary").execute();
      if (primary != null && StringUtils.hasText(primary.getId())) {
        return primary.getId();
      }
    } catch (Exception e) {
      log.warn("Failed to resolve primary calendar id", e);
    }
    return null;
  }

  private void migrateCredentialKey(String fromKey, String toKey) {
    try {
      String fromId = STORE_ID + ":" + fromKey;
      String toId = STORE_ID + ":" + toKey;
      AccountKeyParts keyParts = AccountKeyParts.parse(toKey);
      credentialRepository
          .findById(fromId)
          .ifPresent(
              existing -> {
                credentialRepository.save(
                    new GcalCredentialEntity(
                        toId,
                        existing.getStoreId(),
                        toKey,
                        keyParts.accountBase(),
                        keyParts.accountId(),
                        existing.getAccessToken(),
                        existing.getRefreshToken(),
                        existing.getExpirationTimeMs()));
                credentialRepository.deleteById(fromId);
              });
    } catch (Exception e) {
      log.warn("Failed to migrate credential key {} -> {}", fromKey, toKey, e);
    }
  }
}
