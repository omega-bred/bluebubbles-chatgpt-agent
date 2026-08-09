package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestWorkClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.SummaryMaterial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationDigestServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private static final Instant FROM = Instant.parse("2026-08-07T00:00:00Z");
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final ConversationDigestService service =
      new ConversationDigestService(
          store, mapper, null, null, Clock.fixed(NOW, ZoneOffset.UTC), "digest-worker");

  @Test
  void combinesCompletedDailyDigestsWithCurrentRollingSegments() {
    AuthorizedGroup group = new AuthorizedGroup("conversation-1", "Trip planning", NOW);
    when(store.findAuthorizedGroups("account-1", FROM, NOW)).thenReturn(List.of(group));
    when(store.findAuthorizedDigests("conversation-1", "account-1", FROM, NOW))
        .thenReturn(
            List.of(
                material(
                    "digest-1",
                    "DIGEST",
                    "Yesterday the group selected a venue.",
                    "[]",
                    FROM,
                    FROM.plusSeconds(86_400),
                    FROM.plusSeconds(86_400))));
    when(store.findAuthorizedSegments("conversation-1", "account-1", FROM, NOW))
        .thenReturn(
            List.of(
                material(
                    "segment-1",
                    "SEGMENT",
                    "Today the group discussed transportation.",
                    "[{\"status\":\"PROVISIONAL\",\"text\":\"Whether to take the train\"}]",
                    Instant.parse("2026-08-08T10:00:00Z"),
                    Instant.parse("2026-08-08T11:00:00Z"),
                    Instant.parse("2026-08-08T11:00:00Z"))));
    when(store.findAuthorizedDecisions("conversation-1", "account-1", FROM, NOW, NOW))
        .thenReturn(List.of("Meet at the station at 6 PM."));

    var result = service.catchUp("account-1", "Trip planning", FROM, NOW);

    assertThat(result.ambiguous()).isFalse();
    assertThat(result.groups())
        .singleElement()
        .satisfies(
            catchup -> {
              assertThat(catchup.keyDevelopments())
                  .containsExactly(
                      "Yesterday the group selected a venue.",
                      "Today the group discussed transportation.");
              assertThat(catchup.decisions()).containsExactly("Meet at the station at 6 PM.");
              assertThat(catchup.openQuestions()).containsExactly("Whether to take the train");
              assertThat(catchup.coverageThrough())
                  .isEqualTo(Instant.parse("2026-08-08T11:00:00Z"));
            });
  }

  @Test
  void asksForDisambiguationWhenAuthorizedGroupNamesCollide() {
    when(store.findAuthorizedGroups("account-1", FROM, NOW))
        .thenReturn(
            List.of(
                new AuthorizedGroup(
                    "conversation-1", "Trip", Instant.parse("2026-08-08T12:00:00Z")),
                new AuthorizedGroup(
                    "conversation-2", "Trip", Instant.parse("2026-08-08T13:00:00Z"))));

    var result = service.catchUp("account-1", "Trip", FROM, NOW);

    assertThat(result.ambiguous()).isTrue();
    assertThat(result.groups()).isEmpty();
    assertThat(result.disambiguationOptions())
        .hasSize(2)
        .allMatch(option -> option.startsWith("Trip"));
  }

  @Test
  void clampsCatchupRangeToThirtyOneDays() {
    Instant oldFrom = NOW.minusSeconds(60L * 24 * 60 * 60);
    when(store.findAuthorizedGroups(any(), any(), any())).thenReturn(List.of());

    service.catchUp("account-1", null, oldFrom, NOW);

    verify(store).findAuthorizedGroups("account-1", NOW.minusSeconds(31L * 24 * 60 * 60), NOW);
  }

  @Test
  void nightlyReconciliationComposesSegmentsAndPersistsCoverage() {
    Instant reconciliationTime = Instant.parse("2026-08-08T03:15:00Z");
    ConversationDigestService nightly =
        new ConversationDigestService(
            store,
            mapper,
            null,
            null,
            Clock.fixed(reconciliationTime, ZoneOffset.UTC),
            "digest-worker");
    Instant periodStart = Instant.parse("2026-08-07T00:00:00Z");
    Instant periodEnd = Instant.parse("2026-08-08T00:00:00Z");
    ConversationRecord conversation =
        new ConversationRecord(
            "conversation-1",
            "bluebubbles",
            "iMessage;+;group-1",
            true,
            "Trip",
            periodStart.minusSeconds(1),
            "account-1",
            periodEnd);
    DigestWorkClaim claim =
        new DigestWorkClaim(
            "conversation-1",
            periodStart,
            periodEnd,
            "digest-worker",
            reconciliationTime.plusSeconds(300));
    JournalMessage message =
        new JournalMessage(
            "message-1",
            "conversation-1",
            "account-1",
            "Meet Friday",
            periodStart.plusSeconds(100),
            false,
            false,
            "hash-1");
    SummaryMaterial segment =
        material(
            "segment-1",
            "SEGMENT",
            "The group selected Friday.",
            "[]",
            periodStart.plusSeconds(50),
            periodStart.plusSeconds(200),
            periodStart.plusSeconds(200));
    when(store.findMemoryEnabledConversations()).thenReturn(List.of(conversation));
    when(store.claimDueDigestWork("digest-worker", reconciliationTime, 20))
        .thenReturn(List.of(claim));
    when(store.findMessages("conversation-1", periodStart, periodEnd)).thenReturn(List.of(message));
    when(store.findSegments("conversation-1", periodStart, periodEnd)).thenReturn(List.of(segment));

    nightly.reconcilePreviousDay();

    verify(store).seedDigestWork("conversation-1", periodStart, periodEnd, reconciliationTime);
    ArgumentCaptor<DigestBatch> batch = ArgumentCaptor.forClass(DigestBatch.class);
    verify(store).saveDigest(org.mockito.ArgumentMatchers.eq(claim), batch.capture());
    assertThat(batch.getValue().summary()).isEqualTo("The group selected Friday.");
    assertThat(batch.getValue().coverageThrough()).isEqualTo(periodStart.plusSeconds(200));
    assertThat(batch.getValue().sourceSegmentIds()).containsExactly("segment-1");
  }

  @Test
  void globalFeatureGuardSkipsReconciliationAndCatchupReads() {
    ConversationDigestService disabled =
        new ConversationDigestService(
            store,
            mapper,
            null,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "digest-worker",
            null,
            false);

    assertThat(disabled.catchUp("account-1", null, FROM, NOW).groups()).isEmpty();
    disabled.reconcilePreviousDay();

    verify(store, never()).findAuthorizedGroups(any(), any(), any());
    verify(store, never()).findMemoryEnabledConversations();
  }

  private static SummaryMaterial material(
      String id,
      String type,
      String summary,
      String payload,
      Instant start,
      Instant end,
      Instant coverage) {
    return new SummaryMaterial(
        id, type, "conversation-1", summary, payload, start, end, coverage, "corpus-" + id);
  }
}
