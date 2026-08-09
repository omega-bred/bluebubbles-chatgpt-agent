package io.breland.bbagent.server.agent.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DirectConversationRoute;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProactiveDelivery;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ProactiveCatchupServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T23:30:00Z");

  private final ConversationMemoryStore store = Mockito.mock(ConversationMemoryStore.class);
  private final ConversationDigestService digestService =
      Mockito.mock(ConversationDigestService.class);
  private final BBHttpClientWrapper blueBubbles = Mockito.mock(BBHttpClientWrapper.class);
  private final MessageResponseRateLimitService responseQuota =
      Mockito.mock(MessageResponseRateLimitService.class);
  private final OperationalMetricsService metrics = Mockito.mock(OperationalMetricsService.class);

  @Test
  void globalDefaultOffDoesNotClaimPreferences() {
    service(false).processDueCatchups();

    verify(store, never()).claimDueCatchupPreferences(any(), any(), any(Integer.class));
  }

  @Test
  void quietHoursDeferUntilQuietEnd() {
    CatchupPreferenceClaim claim = claim("UTC", "22:00", "08:00");
    when(store.claimDueCatchupPreferences("proactive-worker", NOW, 20)).thenReturn(List.of(claim));

    service(true).processDueCatchups();

    verify(store)
        .completeCatchupPreferenceClaim(
            claim, Instant.parse("2026-08-09T08:00:00Z"), NOW, "quiet_hours");
    verify(digestService, never()).catchUpForConversation(any(), any(), any(), any());
  }

  @Test
  void successfulSendConsumesQuotaAndRecordsSentExactlyOnce() {
    CatchupPreferenceClaim claim = claim("America/Los_Angeles", "22:00", "08:00");
    Instant lastCoverage = NOW.minusSeconds(7200);
    CatchupGroup catchup =
        new CatchupGroup(
            "Project Chat",
            "Decision reached.",
            List.of("Decision reached."),
            List.of("Ship on Monday."),
            List.of("Who will send the note?"),
            lastCoverage,
            NOW,
            NOW.minusSeconds(60));
    when(store.claimDueCatchupPreferences("proactive-worker", NOW, 20)).thenReturn(List.of(claim));
    when(store.latestSuccessfulCatchupCoverage("account-1", "group-1"))
        .thenReturn(Optional.of(lastCoverage));
    when(digestService.catchUpForConversation("account-1", "group-1", lastCoverage, NOW))
        .thenReturn(new CatchupResult(List.of(catchup), List.of()));
    when(store.findPreferredDirectConversation("account-1", NOW))
        .thenReturn(Optional.of(new DirectConversationRoute("direct-1", "iMessage;-;person")));
    when(responseQuota.tryConsumeForAccountId("account-1"))
        .thenReturn(new RateLimitDecision(null, true, 1));
    when(store.createCatchupDelivery(
            eq(claim), eq("direct-1"), any(), eq(NOW.minusSeconds(60)), any(), any(), eq(NOW)))
        .thenReturn(
            Optional.of(
                new ProactiveDelivery(
                    "delivery-1",
                    "account-1",
                    "group-1",
                    "direct-1",
                    "digest-hash",
                    NOW.minusSeconds(60))));
    when(blueBubbles.sendTextDirect(any())).thenReturn(true);

    service(true).processDueCatchups();

    ArgumentCaptor<ApiV1MessageTextPostRequest> request =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);
    verify(blueBubbles).sendTextDirect(request.capture());
    org.assertj.core.api.Assertions.assertThat(request.getValue().getChatGuid())
        .isEqualTo("iMessage;-;person");
    org.assertj.core.api.Assertions.assertThat(request.getValue().getMessage())
        .contains("Developments since your last catch-up", "Ship on Monday", "Open question");
    verify(store).completeCatchupDelivery("delivery-1", "SENT", NOW);
    verify(store)
        .completeCatchupPreferenceClaim(
            claim, NOW.plus(java.time.Duration.ofMinutes(15)), NOW, null);
    verify(metrics).recordMemoryProactiveDelivery("scheduled", true, null, Duration.ZERO);
  }

  @Test
  void unconfirmedSendBecomesUnknownAndIsNotRetried() {
    CatchupPreferenceClaim claim = claim("UTC", "00:00", "00:00");
    CatchupGroup catchup =
        new CatchupGroup(
            "Project Chat",
            "Decision reached.",
            List.of(),
            List.of("Ship on Monday."),
            List.of(),
            NOW.minusSeconds(3600),
            NOW,
            NOW.minusSeconds(60));
    when(store.claimDueCatchupPreferences("proactive-worker", NOW, 20)).thenReturn(List.of(claim));
    when(store.latestSuccessfulCatchupCoverage("account-1", "group-1"))
        .thenReturn(Optional.empty());
    when(digestService.catchUpForConversation(
            "account-1", "group-1", NOW.minus(java.time.Duration.ofHours(24)), NOW))
        .thenReturn(new CatchupResult(List.of(catchup), List.of()));
    when(store.findPreferredDirectConversation("account-1", NOW))
        .thenReturn(Optional.of(new DirectConversationRoute("direct-1", "iMessage;-;person")));
    when(responseQuota.tryConsumeForAccountId("account-1"))
        .thenReturn(new RateLimitDecision(null, true, 1));
    when(store.createCatchupDelivery(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new ProactiveDelivery(
                    "delivery-1",
                    "account-1",
                    "group-1",
                    "direct-1",
                    "digest-hash",
                    NOW.minusSeconds(60))));
    when(blueBubbles.sendTextDirect(any())).thenReturn(false);

    service(true).processDueCatchups();

    verify(store).completeCatchupDelivery("delivery-1", "UNKNOWN", NOW);
    verify(blueBubbles).sendTextDirect(any());
    verify(metrics)
        .recordMemoryProactiveDelivery("scheduled", false, "send_unconfirmed", Duration.ZERO);
  }

  private ProactiveCatchupService service(boolean globallyEnabled) {
    return new ProactiveCatchupService(
        store,
        digestService,
        blueBubbles,
        responseQuota,
        new ObjectMapper(),
        metrics,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "proactive-worker",
        globallyEnabled);
  }

  private CatchupPreferenceClaim claim(String timezone, String quietStart, String quietEnd) {
    return new CatchupPreferenceClaim(
        "account-1",
        "group-1",
        "Project Chat",
        timezone,
        quietStart,
        quietEnd,
        "proactive-worker",
        NOW.plusSeconds(300));
  }
}
