package io.breland.bbagent.server.agent.tools.gcal;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GcalClient {
  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static final Collection<String> SCOPES = List.of(CalendarScopes.CALENDAR);
  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String clientSecretPath;
  private final String tokenDirectory;
  private final String redirectUri;
  private final String applicationName;
  private final String stateSecret;
  private final Algorithm stateAlgorithm;

  public GcalClient(
      @Value("${gcal.oauth.client_secret_path:}") String clientSecretPath,
      @Value("${gcal.oauth.token_dir:/mnt/data}") String tokenDirectory,
      @Value("${gcal.oauth.redirect_uri:}") String redirectUri,
      @Value("${gcal.oauth.state_secret:}") String stateSecret,
      @Value("${gcal.application_name:newsies}") String applicationName) {
    this.clientSecretPath = clientSecretPath;
    this.tokenDirectory = tokenDirectory;
    this.redirectUri = redirectUri;
    this.stateSecret = stateSecret;
    this.stateAlgorithm =
        stateSecret == null || stateSecret.isBlank() ? null : Algorithm.HMAC256(stateSecret);
    this.applicationName = applicationName;
    ensureTokenDir();
  }

  public boolean isConfigured() {
    return clientSecretPath != null
        && !clientSecretPath.isBlank()
        && Files.exists(Paths.get(clientSecretPath));
  }

  public String getAuthUrl(String accountKey, String chatGuid, String messageGuid) {
    if (!isConfigured()) {
      return null;
    }
    String state = createOauthState(accountKey, chatGuid, messageGuid);
    if (state == null) {
      return null;
    }
    GoogleAuthorizationCodeFlow flow = buildFlow(accountKey);
    return flow.newAuthorizationUrl().setRedirectUri(redirectUri).setState(state).build();
  }

  public boolean exchangeCode(String accountKey, String code) {
    if (!isConfigured()) {
      return false;
    }
    try {
      GoogleAuthorizationCodeFlow flow = buildFlow(accountKey);
      TokenResponse tokenResponse =
          flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
      flow.createAndStoreCredential(tokenResponse, accountKey);
      writeAccountKey(accountKey);
      return true;
    } catch (Exception e) {
      log.warn("Failed to exchange code", e);
      return false;
    }
  }

  public List<String> listAccounts() {
    List<String> results = new ArrayList<>();
    Path base = Paths.get(tokenDirectory);
    if (!Files.exists(base)) {
      return results;
    }
    try (var stream = Files.list(base)) {
      stream
          .filter(Files::isDirectory)
          .forEach(
              dir -> {
                Path keyFile = dir.resolve("account_key.txt");
                if (Files.exists(keyFile)) {
                  try {
                    results.add(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                    return;
                  } catch (IOException ignored) {
                    // fall through
                  }
                }
                results.add(dir.getFileName().toString());
              });
    } catch (IOException e) {
      log.warn("Failed to list accounts", e);
    }
    return results;
  }

  public boolean revokeAccount(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return false;
    }
    Path dir = accountDir(accountKey);
    if (!Files.exists(dir)) {
      return false;
    }
    try (var stream = Files.walk(dir)) {
      stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
      return true;
    } catch (IOException e) {
      log.warn("Failed to revoke account", e);
      return false;
    }
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
    if (value == null || value.isBlank()) {
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
    if (state == null || state.isBlank() || stateAlgorithm == null) {
      return Optional.empty();
    }
    try {
      JWTVerifier verifier = JWT.require(stateAlgorithm).build();
      DecodedJWT jwt = verifier.verify(state);
      String accountKey = jwt.getClaim("account_key").asString();
      String chatGuid = jwt.getClaim("chat_guid").asString();
      String messageGuid = jwt.getClaim("message_guid").asString();
      if (accountKey == null || accountKey.isBlank() || chatGuid == null || chatGuid.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new OauthState(accountKey, chatGuid, messageGuid));
    } catch (JWTVerificationException e) {
      log.warn("Failed to parse OAuth state", e);
      return Optional.empty();
    }
  }

  private Credential getCredential(String accountKey) throws IOException {
    if (!isConfigured()) {
      throw new IOException("Google Calendar client not configured");
    }
    GoogleAuthorizationCodeFlow flow = buildFlow(accountKey);
    Credential credential = flow.loadCredential(accountKey);
    if (credential == null) {
      throw new IOException("No credentials found for account: " + accountKey);
    }
    return credential;
  }

  private GoogleAuthorizationCodeFlow buildFlow(String accountKey) {
    try (FileInputStream input = new FileInputStream(clientSecretPath)) {
      GoogleClientSecrets clientSecrets =
          GoogleClientSecrets.load(
              JSON_FACTORY, new InputStreamReader(input, StandardCharsets.UTF_8));
      return new GoogleAuthorizationCodeFlow.Builder(
              GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
          .setDataStoreFactory(new FileDataStoreFactory(accountDir(accountKey).toFile()))
          .setAccessType("offline")
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load Google client secrets", e);
    }
  }

  private Path accountDir(String accountKey) {
    String safe = sanitize(accountKey);
    return Paths.get(tokenDirectory, safe);
  }

  private void ensureTokenDir() {
    try {
      Files.createDirectories(Paths.get(tokenDirectory));
    } catch (IOException e) {
      log.warn("Failed to create token dir {}", tokenDirectory, e);
    }
  }

  private String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private void writeAccountKey(String accountKey) {
    try {
      Files.createDirectories(accountDir(accountKey));
      Files.writeString(
          accountDir(accountKey).resolve("account_key.txt"),
          Optional.ofNullable(accountKey).orElse(""),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("Failed to store account key", e);
    }
  }

  private String createOauthState(String accountKey, String chatGuid, String messageGuid) {
    if (stateAlgorithm == null) {
      return null;
    }
    if (accountKey == null || accountKey.isBlank() || chatGuid == null || chatGuid.isBlank()) {
      return null;
    }
    try {
      Instant now = Instant.now();
      return JWT.create()
          .withIssuedAt(Date.from(now))
          .withExpiresAt(Date.from(now.plus(OAUTH_STATE_TTL)))
          .withClaim("account_key", accountKey)
          .withClaim("chat_guid", chatGuid)
          .withClaim("message_guid", messageGuid)
          .sign(stateAlgorithm);
    } catch (Exception e) {
      log.warn("Failed to build OAuth state", e);
      return null;
    }
  }

  public record OauthState(String accountKey, String chatGuid, String messageGuid) {}
}
