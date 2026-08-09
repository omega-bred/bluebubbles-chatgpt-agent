package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactKind.GROUP_DECISION;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity.NORMAL;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCandidate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationMemoryStoreTest {
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-08T17:03:00Z");

  @Autowired private ConversationMemoryStore store;
  @Autowired private AgentAccountResolver accountResolver;

  @Test
  void persistsConversationMembershipAndMessage() {
    String accountId = createAccount("alex@example.com");
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;group-1", true, "Trip planning", OBSERVED_AT);
    store.recordMembership(conversationId, accountId, OBSERVED_AT);
    store.recordMessage(
        message("message-1", conversationId, accountId, "Let's meet Saturday at 6"));

    assertThat(
            store.findMessages(
                conversationId, OBSERVED_AT.minusSeconds(1), OBSERVED_AT.plusSeconds(1)))
        .extracting(JournalMessage::messageGuid)
        .containsExactly("message-1");
    assertThat(store.activeMembershipAccountIds(conversationId, OBSERVED_AT))
        .containsExactly(accountId);
  }

  @Test
  void repeatedAndEditedMessageGuidUsesOneJournalRowWithLatestContent() {
    String accountId = createAccount("casey@example.com");
    String conversationId =
        store.upsertConversation("bluebubbles", "iMessage;+;group-2", true, "Dinner", OBSERVED_AT);
    store.recordMessage(message("message-edit", conversationId, accountId, "Friday at six"));
    store.recordMessage(message("message-edit", conversationId, accountId, "Saturday at six"));

    assertThat(
            store.findMessages(
                conversationId, OBSERVED_AT.minusSeconds(1), OBSERVED_AT.plusSeconds(1)))
        .singleElement()
        .satisfies(
            journalMessage -> {
              assertThat(journalMessage.text()).isEqualTo("Saturday at six");
              assertThat(journalMessage.contentHash()).isEqualTo("hash-Saturday-at-six");
            });
  }

  @Test
  void artifactAudienceExcludesAccountsThatJoinLater() {
    String originalAccountId = createAccount("original@example.com");
    String laterAccountId = createAccount("later@example.com");
    String conversationId =
        store.upsertConversation("bluebubbles", "iMessage;+;group-3", true, "Launch", OBSERVED_AT);
    store.recordMembership(conversationId, originalAccountId, OBSERVED_AT.minusSeconds(30));
    JournalMessage source =
        message("message-decision", conversationId, originalAccountId, "Ship on Monday");
    store.recordMessage(source);
    store.scheduleExtraction(conversationId, OBSERVED_AT);
    WorkClaim claim = store.claimDueExtractionWork("worker-1", OBSERVED_AT, 10).getFirst();

    List<String> artifactIds =
        store.saveExtraction(
            claim,
            new ExtractionBatch(
                conversationId,
                List.of(source),
                List.of(
                    new ExtractionCandidate(
                        GROUP_DECISION,
                        "The group decided to ship on Monday.",
                        CONFIRMED,
                        NORMAL,
                        0.96,
                        OBSERVED_AT,
                        null,
                        List.of(source.messageGuid()),
                        null,
                        "artifact-hash")),
                "The group settled on a Monday launch.",
                "[]",
                "corpus-hash",
                OBSERVED_AT));
    String artifactId = artifactIds.getFirst();
    store.recordMembership(conversationId, laterAccountId, OBSERVED_AT.plusSeconds(1));

    assertThat(store.isInArtifactAudience(artifactId, originalAccountId)).isTrue();
    assertThat(store.isInArtifactAudience(artifactId, laterAccountId)).isFalse();
  }

  @Test
  void onlyOneWorkerCanClaimDueConversation() {
    String conversationId =
        store.upsertConversation("bluebubbles", "iMessage;+;group-4", true, "Claim", OBSERVED_AT);
    store.scheduleExtraction(conversationId, OBSERVED_AT);

    List<WorkClaim> firstClaims = store.claimDueExtractionWork("worker-1", OBSERVED_AT, 10);
    List<WorkClaim> secondClaims = store.claimDueExtractionWork("worker-2", OBSERVED_AT, 10);

    assertThat(firstClaims)
        .singleElement()
        .extracting(WorkClaim::conversationId)
        .isEqualTo(conversationId);
    assertThat(firstClaims.getFirst().claimedUntil())
        .isAfter(OBSERVED_AT.plus(Duration.ofMinutes(1)));
    assertThat(secondClaims).isEmpty();
  }

  @Test
  void extractionCheckpointUsesTheLastSourceMessageAndExposesActiveArtifacts() {
    String accountId = createAccount("checkpoint@example.com");
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;group-checkpoint", true, "Checkpoint", OBSERVED_AT);
    store.recordMembership(conversationId, accountId, OBSERVED_AT.minusSeconds(30));
    JournalMessage source =
        message("message-checkpoint", conversationId, accountId, "Saturday at six");
    store.recordMessage(source);
    store.scheduleExtraction(conversationId, OBSERVED_AT.plusSeconds(30));
    WorkClaim claim =
        store
            .claimDueExtractionWork("worker-checkpoint", OBSERVED_AT.plusSeconds(30), 10)
            .getFirst();

    store.saveExtraction(
        claim,
        new ExtractionBatch(
            conversationId,
            List.of(source),
            List.of(
                new ExtractionCandidate(
                    GROUP_DECISION,
                    "The group chose Saturday at six.",
                    CONFIRMED,
                    NORMAL,
                    0.95,
                    OBSERVED_AT,
                    null,
                    List.of(source.messageGuid()),
                    null,
                    "checkpoint-artifact-hash")),
            "Saturday was selected.",
            "[]",
            "checkpoint-corpus-hash",
            OBSERVED_AT.plusSeconds(30)));

    assertThat(store.findCheckpoint(conversationId))
        .hasValueSatisfying(
            checkpoint -> {
              assertThat(checkpoint.lastProcessedAt()).isEqualTo(OBSERVED_AT);
              assertThat(checkpoint.lastProcessedMessageGuid()).isEqualTo("message-checkpoint");
              assertThat(checkpoint.lastCorpusHash()).isEqualTo("checkpoint-corpus-hash");
            });
    assertThat(store.findActiveArtifacts(conversationId))
        .singleElement()
        .satisfies(
            artifact -> assertThat(artifact.text()).isEqualTo("The group chose Saturday at six."));
  }

  @Test
  void extractionCompletionPreservesMessagesScheduledWhileTheLeaseIsHeld() {
    String conversationId =
        store.upsertConversation("bluebubbles", "iMessage;+;group-race", true, "Race", OBSERVED_AT);
    store.scheduleExtraction(conversationId, OBSERVED_AT);
    WorkClaim claim = store.claimDueExtractionWork("worker-race", OBSERVED_AT, 10).getFirst();
    Instant futureAvailableAt = OBSERVED_AT.plusSeconds(90);
    store.scheduleExtraction(conversationId, futureAvailableAt);

    store.saveExtraction(
        claim,
        new ExtractionBatch(
            conversationId,
            List.of(),
            List.of(),
            "",
            "[]",
            "empty-corpus-hash",
            OBSERVED_AT.plusSeconds(1)));

    assertThat(store.extractionAvailableAt(conversationId)).contains(futureAvailableAt);
    assertThat(store.claimDueExtractionWork("worker-early", OBSERVED_AT.plusSeconds(30), 10))
        .isEmpty();
    assertThat(store.claimDueExtractionWork("worker-later", futureAvailableAt, 10))
        .singleElement()
        .extracting(WorkClaim::conversationId)
        .isEqualTo(conversationId);
  }

  @Test
  void failedExtractionBecomesRetryableAfterTheBoundedDelay() {
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;group-retry", true, "Retry", OBSERVED_AT);
    store.scheduleExtraction(conversationId, OBSERVED_AT);
    WorkClaim claim = store.claimDueExtractionWork("worker-failed", OBSERVED_AT, 10).getFirst();

    store.failExtractionWork(claim, OBSERVED_AT, "invalid_response");

    assertThat(store.claimDueExtractionWork("worker-early", OBSERVED_AT.plusSeconds(29), 10))
        .isEmpty();
    assertThat(store.claimDueExtractionWork("worker-retry", OBSERVED_AT.plusSeconds(30), 10))
        .singleElement()
        .extracting(WorkClaim::conversationId)
        .isEqualTo(conversationId);
  }

  @Test
  void canonicalMemoryOwnershipCannotMoveAcrossScopes() {
    String accountId = createAccount("memory-owner@example.com");
    String canonicalScope = "account:" + accountId;
    store.recordCanonicalMemory(canonicalScope, "memory-1", "hash-1", OBSERVED_AT);

    assertThat(store.ownsCanonicalMemory(canonicalScope, "memory-1")).isTrue();
    assertThat(store.ownsCanonicalMemory("account:another-account", "memory-1")).isFalse();
    assertThatThrownBy(
            () ->
                store.recordCanonicalMemory(
                    "account:another-account", "memory-1", "hash-2", OBSERVED_AT))
        .hasMessageContaining("already owned by another canonical scope");

    store.updateCanonicalMemory(canonicalScope, "memory-1", "hash-2", OBSERVED_AT.plusSeconds(1));
    store.deleteCanonicalMemory(canonicalScope, "memory-1");
    assertThat(store.ownsCanonicalMemory(canonicalScope, "memory-1")).isFalse();
  }

  private String createAccount(String email) {
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, email)
        .orElseThrow()
        .account()
        .getAccountId();
  }

  private JournalMessage message(
      String messageGuid, String conversationId, String accountId, String text) {
    return new JournalMessage(
        messageGuid,
        conversationId,
        accountId,
        text,
        OBSERVED_AT,
        false,
        false,
        "hash-" + text.replace(' ', '-'));
  }
}
