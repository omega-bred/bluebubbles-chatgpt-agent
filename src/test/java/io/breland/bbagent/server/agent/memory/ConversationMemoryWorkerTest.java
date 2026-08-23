package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCheckpoint;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ModelExtraction;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationMemoryWorkerTest {
  private static final Instant NOW = Instant.parse("2026-08-08T17:05:00Z");
  private static final String CONVERSATION_ID = "conversation-1";
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final ConversationMembershipService membershipService =
      mock(ConversationMembershipService.class);
  private final ConversationMemoryModelClient modelClient =
      mock(ConversationMemoryModelClient.class);
  private final OperationalMetricsService metrics = mock(OperationalMetricsService.class);
  private final WorkClaim claim = new WorkClaim(CONVERSATION_ID, "worker-1", NOW.plusSeconds(300));
  private final ConversationMemoryWorker worker =
      new ConversationMemoryWorker(
          store,
          membershipService,
          modelClient,
          metrics,
          Clock.fixed(NOW, ZoneOffset.UTC),
          "worker-1");

  @BeforeEach
  void setUp() {
    when(store.findConversation(CONVERSATION_ID))
        .thenReturn(
            Optional.of(
                new ConversationRecord(
                    CONVERSATION_ID,
                    "bluebubbles",
                    "iMessage;+;group-1",
                    true,
                    "Trip",
                    NOW.minusSeconds(300),
                    "account-1",
                    NOW)));
    when(store.findCheckpoint(CONVERSATION_ID)).thenReturn(Optional.empty());
    when(store.findActiveArtifacts(CONVERSATION_ID)).thenReturn(List.of());
  }

  @Test
  void doesNothingBeforeTheDebouncedWorkBecomesDue() {
    when(store.claimDueExtractionWork("worker-1", NOW, 10)).thenReturn(List.of());

    worker.processDueConversationMemory();

    verifyNoInteractions(membershipService, modelClient);
  }

  @Test
  void extractsTwoCloseMessagesAsOneBatch() {
    List<JournalMessage> messages = messages();
    when(store.claimDueExtractionWork("worker-1", NOW, 10)).thenReturn(List.of(claim));
    when(store.findMessages(CONVERSATION_ID, NOW.minusSeconds(300), NOW)).thenReturn(messages);
    when(modelClient.extract(messages, List.of()))
        .thenReturn(new ModelExtraction("Saturday was chosen.", List.of(), "[]"));

    worker.processDueConversationMemory();

    ArgumentCaptor<ExtractionBatch> batchCaptor = ArgumentCaptor.forClass(ExtractionBatch.class);
    verify(store).saveExtraction(org.mockito.ArgumentMatchers.eq(claim), batchCaptor.capture());
    assertThat(batchCaptor.getValue().sourceMessages())
        .extracting(JournalMessage::messageGuid)
        .containsExactly("message-1", "message-2");
    verify(membershipService).refreshGroupMembership(CONVERSATION_ID);
  }

  @Test
  void membershipFailureReleasesTheClaimForRetryWithoutCallingTheModel() {
    when(store.claimDueExtractionWork("worker-1", NOW, 10)).thenReturn(List.of(claim));
    org.mockito.Mockito.doThrow(
            new ConversationMembershipService.MembershipRefreshException("unavailable"))
        .when(membershipService)
        .refreshGroupMembership(CONVERSATION_ID);

    worker.processDueConversationMemory();

    verify(store)
        .failExtractionWork(
            org.mockito.ArgumentMatchers.eq(claim),
            org.mockito.ArgumentMatchers.eq(NOW),
            org.mockito.ArgumentMatchers.eq("membership_refresh"));
    verifyNoInteractions(modelClient);
    verify(store, never()).saveExtraction(any(), any());
  }

  @Test
  void unchangedCorpusCompletesWorkWithoutRerunningExtraction() {
    List<JournalMessage> messages = messages();
    String corpusHash = ConversationMemoryModels.corpusHash(messages);
    when(store.claimDueExtractionWork("worker-1", NOW, 10)).thenReturn(List.of(claim));
    when(store.findCheckpoint(CONVERSATION_ID))
        .thenReturn(
            Optional.of(new ExtractionCheckpoint(NOW.minusSeconds(30), "message-2", corpusHash)));
    when(store.findMessages(CONVERSATION_ID, NOW.minusSeconds(630), NOW)).thenReturn(messages);

    worker.processDueConversationMemory();

    verify(store).completeUnchangedExtraction(claim, NOW);
    verifyNoInteractions(modelClient);
    verify(store, never()).saveExtraction(any(), any());
  }

  @Test
  void persistenceFailureDoesNotCompleteTheClaimOrAdvanceThroughAnotherPath() {
    List<JournalMessage> messages = messages();
    when(store.claimDueExtractionWork("worker-1", NOW, 10)).thenReturn(List.of(claim));
    when(store.findMessages(CONVERSATION_ID, NOW.minusSeconds(300), NOW)).thenReturn(messages);
    when(modelClient.extract(messages, List.of()))
        .thenReturn(new ModelExtraction("Saturday was chosen.", List.of(), "[]"));
    when(store.saveExtraction(any(), any())).thenThrow(new IllegalStateException("db failed"));

    worker.processDueConversationMemory();

    verify(store).failExtractionWork(claim, NOW, "invalid_response");
    verify(store, never()).completeUnchangedExtraction(any(), any());
  }

  private static List<JournalMessage> messages() {
    return List.of(
        message("message-1", "Friday?", NOW.minusSeconds(90), "hash-1"),
        message("message-2", "Saturday at six", NOW.minusSeconds(60), "hash-2"));
  }

  private static JournalMessage message(String guid, String text, Instant timestamp, String hash) {
    return new JournalMessage(
        guid, CONVERSATION_ID, "account-1", text, timestamp, false, false, hash);
  }
}
