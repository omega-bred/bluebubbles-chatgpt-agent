package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreference;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceSetting;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceUpdate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DirectConversationRoute;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProactiveDelivery;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ProactiveCatchupService {
  private static final int CLAIM_LIMIT = 20;
  private static final Duration POLL_INTERVAL = Duration.ofMinutes(15);
  private static final Duration INITIAL_LOOKBACK = Duration.ofHours(24);
  private static final String DEFAULT_TIMEZONE = "UTC";
  private static final String DEFAULT_QUIET_START = "22:00";
  private static final String DEFAULT_QUIET_END = "08:00";
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

  private final ConversationMemoryStore store;
  private final ConversationDigestService digestService;
  private final BBHttpClientWrapper blueBubbles;
  private final MessageResponseRateLimitService responseQuota;
  private final @Nullable OperationalMetricsService operationalMetricsService;
  private final Clock clock;
  private final String workerId;
  private final boolean globallyEnabled;

  @Autowired
  public ProactiveCatchupService(
      ConversationMemoryStore store,
      ConversationDigestService digestService,
      BBHttpClientWrapper blueBubbles,
      MessageResponseRateLimitService responseQuota,
      @Nullable OperationalMetricsService operationalMetricsService,
      @Nullable Clock clock,
      @Value("${bbagent.memory.group.enabled:false}") boolean groupMemoryEnabled,
      @Value("${bbagent.memory.group.proactive-enabled:false}") boolean proactiveEnabled) {
    this(
        store,
        digestService,
        blueBubbles,
        responseQuota,
        operationalMetricsService,
        clock == null ? Clock.systemUTC() : clock,
        UUID.randomUUID().toString(),
        groupMemoryEnabled && proactiveEnabled);
  }

  ProactiveCatchupService(
      ConversationMemoryStore store,
      ConversationDigestService digestService,
      BBHttpClientWrapper blueBubbles,
      MessageResponseRateLimitService responseQuota,
      @Nullable OperationalMetricsService operationalMetricsService,
      Clock clock,
      String workerId,
      boolean globallyEnabled) {
    this.store = store;
    this.digestService = digestService;
    this.blueBubbles = blueBubbles;
    this.responseQuota = responseQuota;
    this.operationalMetricsService = operationalMetricsService;
    this.clock = clock;
    this.workerId = workerId;
    this.globallyEnabled = globallyEnabled;
  }

  public CatchupPreferenceSetting preferenceForChat(String accountId, String chatGuid) {
    Instant now = clock.instant();
    Optional<AuthorizedGroup> group =
        store.findCurrentlyAuthorizedGroup(
            accountId, IncomingMessage.TRANSPORT_BLUEBUBBLES, chatGuid, now);
    return group.map(value -> setting(accountId, value)).orElseGet(this::unavailableSetting);
  }

  public CatchupPreferenceSetting updateForChat(
      String accountId,
      String chatGuid,
      boolean enabled,
      @Nullable String timezone,
      @Nullable String quietStart,
      @Nullable String quietEnd) {
    Instant now = clock.instant();
    AuthorizedGroup group =
        store
            .findCurrentlyAuthorizedGroup(
                accountId, IncomingMessage.TRANSPORT_BLUEBUBBLES, chatGuid, now)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Personal catch-ups are unavailable for this conversation"));
    return save(accountId, group, enabled, timezone, quietStart, quietEnd, now);
  }

  public CatchupPreferenceUpdate updateForGroup(
      String accountId,
      @Nullable String groupHint,
      boolean enabled,
      @Nullable String timezone,
      @Nullable String quietStart,
      @Nullable String quietEnd) {
    Instant now = clock.instant();
    List<AuthorizedGroup> groups =
        selectGroups(store.findCurrentlyAuthorizedGroups(accountId, now), groupHint);
    if (groups.size() != 1) {
      List<String> options = groups.stream().map(this::disambiguationLabel).toList();
      return new CatchupPreferenceUpdate(unavailableSetting(), options);
    }
    return new CatchupPreferenceUpdate(
        save(accountId, groups.getFirst(), enabled, timezone, quietStart, quietEnd, now),
        List.of());
  }

  @Scheduled(
      fixedDelayString = "${bbagent.memory.group.proactive-poll-interval:PT15M}",
      initialDelayString = "${bbagent.memory.group.worker-initial-delay:PT15S}")
  public void processDueCatchups() {
    if (!globallyEnabled) {
      return;
    }
    Instant now = clock.instant();
    for (CatchupPreferenceClaim claim :
        store.claimDueCatchupPreferences(workerId, now, CLAIM_LIMIT)) {
      processClaim(claim, now);
    }
  }

  private void processClaim(CatchupPreferenceClaim claim, Instant now) {
    Instant quietEnd = quietEndIfActive(claim, now);
    if (quietEnd != null) {
      store.completeCatchupPreferenceClaim(claim, quietEnd, now);
      return;
    }
    try {
      Instant from =
          store
              .latestSuccessfulCatchupCoverage(claim.accountId(), claim.conversationId())
              .orElse(now.minus(INITIAL_LOOKBACK));
      CatchupResult result =
          digestService.catchUpForConversation(
              claim.accountId(), claim.conversationId(), from, now);
      CatchupGroup group = result.groups().stream().findFirst().orElse(null);
      if (group == null || (group.decisions().isEmpty() && group.openQuestions().isEmpty())) {
        completeClaim(claim, now, POLL_INTERVAL);
        return;
      }
      Optional<DirectConversationRoute> directRoute =
          store.findPreferredDirectConversation(claim.accountId(), now);
      if (directRoute.isEmpty()) {
        completeClaim(claim, now, Duration.ofHours(1));
        return;
      }
      String message = deliveryText(group);
      String digestHash = sha256(message);
      ZoneId zone = ZoneId.of(claim.timezone());
      ZonedDateTime zonedNow = now.atZone(zone);
      Instant dayStart = zonedNow.toLocalDate().atStartOfDay(zone).toInstant();
      Instant dayEnd = zonedNow.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      Optional<ProactiveDelivery> delivery =
          store.createCatchupDelivery(
              claim,
              directRoute.get().conversationId(),
              digestHash,
              group.coverageThrough(),
              dayStart,
              dayEnd,
              now);
      if (delivery.isEmpty()) {
        store.completeCatchupPreferenceClaim(claim, dayEnd.plus(POLL_INTERVAL), now);
        return;
      }
      if (!responseQuota.tryConsumeForAccountId(claim.accountId()).allowed()) {
        store.completeCatchupDelivery(delivery.get().deliveryId(), "SKIPPED", now);
        store.completeCatchupPreferenceClaim(claim, dayEnd.plus(POLL_INTERVAL), now);
        return;
      }

      ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
      request.setChatGuid(directRoute.get().externalConversationId());
      request.setMessage(message);
      request.setTempGuid(UUID.randomUUID().toString());
      Instant deliveryStartedAt = clock.instant();
      boolean sent;
      try {
        sent = blueBubbles.sendTextDirect(request);
        if (operationalMetricsService != null) {
          operationalMetricsService.recordMemoryProactiveDelivery(
              "scheduled",
              sent,
              sent ? null : "send_unconfirmed",
              Duration.between(deliveryStartedAt, clock.instant()));
        }
      } catch (RuntimeException e) {
        if (operationalMetricsService != null) {
          operationalMetricsService.recordMemoryProactiveDelivery(
              "scheduled",
              false,
              OperationalMetricsService.failureType(e),
              Duration.between(deliveryStartedAt, clock.instant()));
        }
        throw e;
      }
      store.completeCatchupDelivery(delivery.get().deliveryId(), sent ? "SENT" : "UNKNOWN", now);
      completeClaim(claim, now, POLL_INTERVAL);
    } catch (RuntimeException e) {
      completeClaim(claim, now, POLL_INTERVAL);
    }
  }

  private CatchupPreferenceSetting save(
      String accountId,
      AuthorizedGroup group,
      boolean enabled,
      @Nullable String timezone,
      @Nullable String quietStart,
      @Nullable String quietEnd,
      Instant now) {
    CatchupPreference current =
        store.findCatchupPreference(accountId, group.conversationId()).orElse(null);
    String resolvedTimezone =
        validateTimezone(
            StringUtils.defaultIfBlank(
                timezone, current == null ? DEFAULT_TIMEZONE : current.timezone()));
    String resolvedQuietStart =
        validateTime(
            StringUtils.defaultIfBlank(
                quietStart, current == null ? DEFAULT_QUIET_START : current.quietStart()));
    String resolvedQuietEnd =
        validateTime(
            StringUtils.defaultIfBlank(
                quietEnd, current == null ? DEFAULT_QUIET_END : current.quietEnd()));
    CatchupPreference preference =
        store.saveCatchupPreference(
            accountId,
            group.conversationId(),
            enabled,
            resolvedTimezone,
            resolvedQuietStart,
            resolvedQuietEnd,
            now,
            now);
    return setting(preference);
  }

  private CatchupPreferenceSetting setting(String accountId, AuthorizedGroup group) {
    return store
        .findCatchupPreference(accountId, group.conversationId())
        .map(this::setting)
        .orElse(
            new CatchupPreferenceSetting(
                true,
                false,
                DEFAULT_TIMEZONE,
                DEFAULT_QUIET_START,
                DEFAULT_QUIET_END,
                null,
                StringUtils.defaultIfBlank(group.displayName(), "Group conversation")));
  }

  private CatchupPreferenceSetting setting(CatchupPreference preference) {
    return new CatchupPreferenceSetting(
        true,
        preference.enabled(),
        preference.timezone(),
        preference.quietStart(),
        preference.quietEnd(),
        preference.nextDeliveryAt(),
        StringUtils.defaultIfBlank(preference.groupDisplayName(), "Group conversation"));
  }

  private CatchupPreferenceSetting unavailableSetting() {
    return new CatchupPreferenceSetting(
        false, false, DEFAULT_TIMEZONE, DEFAULT_QUIET_START, DEFAULT_QUIET_END, null, null);
  }

  private List<AuthorizedGroup> selectGroups(
      List<AuthorizedGroup> authorizedGroups, @Nullable String groupHint) {
    String hint = StringUtils.trimToNull(groupHint);
    if (hint == null) {
      return authorizedGroups;
    }
    int suffix = hint.indexOf(" (last active ");
    if (suffix > 0) {
      hint = hint.substring(0, suffix);
    }
    String normalized = hint.toLowerCase(Locale.ROOT);
    List<AuthorizedGroup> exact =
        authorizedGroups.stream()
            .filter(group -> normalizedName(group.displayName()).equals(normalized))
            .toList();
    return exact.isEmpty()
        ? authorizedGroups.stream()
            .filter(group -> normalizedName(group.displayName()).contains(normalized))
            .toList()
        : exact;
  }

  private String normalizedName(@Nullable String value) {
    return StringUtils.defaultIfBlank(value, "Group conversation").trim().toLowerCase(Locale.ROOT);
  }

  private String disambiguationLabel(AuthorizedGroup group) {
    return StringUtils.defaultIfBlank(group.displayName(), "Group conversation")
        + " (last active "
        + group.lastActivityAt()
        + ")";
  }

  private Instant quietEndIfActive(CatchupPreferenceClaim claim, Instant now) {
    ZoneId zone = ZoneId.of(claim.timezone());
    LocalTime start = parseTime(claim.quietStart());
    LocalTime end = parseTime(claim.quietEnd());
    if (start.equals(end)) {
      return null;
    }
    ZonedDateTime zonedNow = now.atZone(zone);
    LocalTime current = zonedNow.toLocalTime();
    boolean quiet =
        start.isBefore(end)
            ? !current.isBefore(start) && current.isBefore(end)
            : !current.isBefore(start) || current.isBefore(end);
    if (!quiet) {
      return null;
    }
    LocalDate endDate = zonedNow.toLocalDate();
    if (!start.isBefore(end) && !current.isBefore(start)) {
      endDate = endDate.plusDays(1);
    }
    return endDate.atTime(end).atZone(zone).toInstant();
  }

  private String deliveryText(CatchupGroup group) {
    List<String> lines = new ArrayList<>();
    lines.add(
        "Developments since your last catch-up in "
            + StringUtils.defaultIfBlank(group.group(), "your group")
            + ":");
    group.decisions().stream().limit(5).forEach(decision -> lines.add("• " + decision));
    group.openQuestions().stream()
        .limit(5)
        .forEach(question -> lines.add("• Open question: " + question));
    return StringUtils.truncate(String.join("\n", lines), 1_400);
  }

  private void completeClaim(CatchupPreferenceClaim claim, Instant now, Duration delay) {
    store.completeCatchupPreferenceClaim(claim, now.plus(delay), now);
  }

  private String validateTimezone(String timezone) {
    try {
      return ZoneId.of(timezone).getId();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid IANA timezone", e);
    }
  }

  private String validateTime(String value) {
    parseTime(value);
    return value;
  }

  private LocalTime parseTime(String value) {
    try {
      return LocalTime.parse(value, TIME_FORMATTER);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Quiet hours must use HH:mm", e);
    }
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
