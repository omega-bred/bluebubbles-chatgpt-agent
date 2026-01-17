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
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import io.breland.bbagent.server.agent.persistence.GcalCalendarAccessEntity;
import io.breland.bbagent.server.agent.persistence.GcalCalendarAccessRepository;
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
  private static final String STORE_ID = StoredCredential.DEFAULT_DATA_STORE_ID;
  private static final String ACCESS_NONE_SENTINEL = "__none__";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String clientSecretPath;
  private final String clientSecret;
  private final String redirectUri;
  private final String applicationName;
  private final Algorithm stateAlgorithm;
  private final GcalCredentialRepository credentialRepository;
  private final GcalCalendarAccessRepository calendarAccessRepository;

  public GcalClient(
      @Value("${gcal.oauth.client_secret_path:}") String clientSecretPath,
      @Value("${gcal.oauth.client_secret:}") String clientSecret,
      @Value("${gcal.oauth.redirect_uri:}") String redirectUri,
      @Value("${gcal.oauth.state_secret:}") String stateSecret,
      @Value("${gcal.application_name:iMessage + ChatGPT}") String applicationName,
      GcalCredentialRepository credentialRepository,
      GcalCalendarAccessRepository calendarAccessRepository) {
    this.clientSecretPath = clientSecretPath;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.stateAlgorithm =
        stateSecret == null || stateSecret.isBlank() ? null : Algorithm.HMAC256(stateSecret);
    this.applicationName = applicationName;
    this.credentialRepository = credentialRepository;
    this.calendarAccessRepository = calendarAccessRepository;
  }

  public boolean isConfigured() {
    boolean configuredPath =
        clientSecretPath != null
            && !clientSecretPath.isBlank()
            && Files.exists(Paths.get(clientSecretPath));
    boolean directSecretConfigured = clientSecret != null && !clientSecret.isBlank();
    boolean configured = configuredPath || directSecretConfigured;
    if (!configured) {
      log.warn(
          "Gcal client is not configured, no direct secret, path does not exist: {}",
          clientSecretPath);
    }
    return configured;
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
      return true;
    } catch (Exception e) {
      log.warn("Failed to exchange code", e);
      return false;
    }
  }

  public List<String> listAccounts() {
    return credentialRepository.findAllAccountKeysByStoreId(STORE_ID);
  }

  public List<CalendarInfo> listCalendars(String accountKey) throws IOException {
    Calendar client = getCalendarService(accountKey);
    CalendarList list = client.calendarList().list().execute();
    List<CalendarInfo> calendars = new ArrayList<>();
    if (list.getItems() != null) {
      for (CalendarListEntry entry : list.getItems()) {
        calendars.add(
            new CalendarInfo(
                entry.getId(),
                entry.getSummary(),
                entry.getPrimary(),
                entry.getTimeZone(),
                entry.getAccessRole()));
      }
    }
    return calendars;
  }

  public boolean revokeAccount(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return false;
    }
    calendarAccessRepository.deleteByAccountKey(accountKey);
    return credentialRepository.deleteByStoreIdAndAccountKey(STORE_ID, accountKey) > 0;
  }

  public CalendarAccessConfig getCalendarAccessConfig(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return new CalendarAccessConfig(false, Map.of());
    }
    boolean configured = calendarAccessRepository.countByAccountKey(accountKey) > 0;
    if (!configured) {
      return new CalendarAccessConfig(false, Map.of());
    }
    List<GcalCalendarAccessEntity> entries =
        calendarAccessRepository.findAllByAccountKey(accountKey);
    Map<String, CalendarAccessMode> result = new LinkedHashMap<>();
    for (GcalCalendarAccessEntity entry : entries) {
      if (ACCESS_NONE_SENTINEL.equals(entry.getCalendarId())) {
        continue;
      }
      CalendarAccessMode mode = CalendarAccessMode.fromDbValue(entry.getMode());
      if (mode != null) {
        result.put(entry.getCalendarId(), mode);
      }
    }
    return new CalendarAccessConfig(true, result);
  }

  public Map<String, CalendarAccessMode> getCalendarAccessMap(String accountKey) {
    return getCalendarAccessConfig(accountKey).accessMap();
  }

  public void saveCalendarAccess(String accountKey, List<CalendarAccess> calendars) {
    if (accountKey == null || accountKey.isBlank()) {
      return;
    }
    calendarAccessRepository.deleteByAccountKey(accountKey);
    if (calendars == null || calendars.isEmpty()) {
      String id = accountKey + ":" + ACCESS_NONE_SENTINEL;
      calendarAccessRepository.save(
          new GcalCalendarAccessEntity(
              id, accountKey, ACCESS_NONE_SENTINEL, CalendarAccessMode.READ_ONLY.dbValue));
      return;
    }
    List<GcalCalendarAccessEntity> entities = new ArrayList<>();
    for (CalendarAccess access : calendars) {
      if (access == null || access.calendarId() == null || access.calendarId().isBlank()) {
        continue;
      }
      CalendarAccessMode mode =
          access.mode() != null ? access.mode() : CalendarAccessMode.READ_ONLY;
      String id = accountKey + ":" + access.calendarId();
      entities.add(new GcalCalendarAccessEntity(id, accountKey, access.calendarId(), mode.dbValue));
    }
    calendarAccessRepository.saveAll(entities);
  }

  public boolean canReadCalendar(String accountKey, String calendarId) {
    return checkAccess(accountKey, calendarId, AccessRequirement.READ);
  }

  public boolean canWriteCalendar(String accountKey, String calendarId) {
    return checkAccess(accountKey, calendarId, AccessRequirement.WRITE);
  }

  public boolean canFreeBusy(String accountKey, String calendarId) {
    return checkAccess(accountKey, calendarId, AccessRequirement.FREE_BUSY);
  }

  private boolean checkAccess(String accountKey, String calendarId, AccessRequirement requirement) {
    if (accountKey == null || accountKey.isBlank()) {
      return false;
    }
    if (calendarId == null || calendarId.isBlank()) {
      return false;
    }
    CalendarAccessConfig config = getCalendarAccessConfig(accountKey);
    if (!config.configured()) {
      return true;
    }
    Map<String, CalendarAccessMode> accessMap = config.accessMap();
    if (accessMap.isEmpty()) {
      return false;
    }
    CalendarAccessMode mode = accessMap.get(calendarId);
    if (mode == null) {
      return false;
    }
    return switch (requirement) {
      case READ -> mode == CalendarAccessMode.FULL || mode == CalendarAccessMode.READ_ONLY;
      case WRITE -> mode == CalendarAccessMode.FULL;
      case FREE_BUSY -> true;
    };
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
    if (clientSecretPath != null
        && !clientSecretPath.isBlank()
        && Files.exists(Paths.get(clientSecretPath))) {
      try (FileInputStream input = new FileInputStream(clientSecretPath)) {
        GoogleClientSecrets clientSecrets =
            GoogleClientSecrets.load(
                JSON_FACTORY, new InputStreamReader(input, StandardCharsets.UTF_8));
        DataStoreFactory dataStoreFactory =
            new PostgresCredentialDataStoreFactory(credentialRepository);
        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(dataStoreFactory)
            .setAccessType("offline")
            .build();
      } catch (Exception e) {
        log.error("Failed to load Google client secrets via path", e);
        throw new IllegalStateException("Failed to load Google client secrets", e);
      }
    } else if (clientSecret != null && !clientSecret.isBlank()) {
      try {
        GoogleClientSecrets clientSecrets =
            GoogleClientSecrets.load(JSON_FACTORY, new StringReader(clientSecret));
        DataStoreFactory dataStoreFactory =
            new PostgresCredentialDataStoreFactory(credentialRepository);
        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(dataStoreFactory)
            .setAccessType("offline")
            .build();
      } catch (Exception e) {
        log.error("Failed to load Google client secrets via direct", e);
        throw new IllegalStateException("Failed to load Google client secrets", e);
      }
    }
    log.warn("Failed to load Google client secrets");
    throw new IllegalStateException("Failed to load Google client secrets");
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

  public record CalendarAccess(String calendarId, CalendarAccessMode mode) {}

  public record CalendarAccessConfig(
      boolean configured, Map<String, CalendarAccessMode> accessMap) {}

  public record CalendarInfo(
      String calendarId, String summary, Boolean primary, String timeZone, String accessRole) {}

  public enum CalendarAccessMode {
    FULL("full"),
    READ_ONLY("read_only"),
    FREE_BUSY("free_busy");

    private final String dbValue;

    CalendarAccessMode(String dbValue) {
      this.dbValue = dbValue;
    }

    public static CalendarAccessMode fromDbValue(String value) {
      if (value == null) {
        return null;
      }
      return switch (value) {
        case "full" -> FULL;
        case "read_only" -> READ_ONLY;
        case "free_busy" -> FREE_BUSY;
        default -> null;
      };
    }

    public static CalendarAccessMode fromApiValue(
        io.breland.bbagent.generated.model.GcalCalendarAccessMode value) {
      if (value == null) {
        return null;
      }
      return switch (value) {
        case FULL -> FULL;
        case READ_ONLY -> READ_ONLY;
        case FREE_BUSY -> FREE_BUSY;
      };
    }
  }

  private enum AccessRequirement {
    READ,
    WRITE,
    FREE_BUSY
  }
}
