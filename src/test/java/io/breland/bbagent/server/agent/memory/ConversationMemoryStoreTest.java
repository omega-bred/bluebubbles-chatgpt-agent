package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactKind.GROUP_DECISION;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity.NORMAL;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCandidate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationMemoryStoreTest {
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-08T17:03:00Z");

  @Autowired private ConversationMemoryStore store;
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private DataSource dataSource;

  @Test
  void extractionWorkUsesPostgresCompatibleTimestampArguments() {
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;postgres-time", true, "Postgres", OBSERVED_AT);
    ConversationMemoryStore postgresStrictStore =
        new ConversationMemoryStore(
            new JdbcTemplate(dataSource) {
              @Override
              protected PreparedStatementSetter newArgPreparedStatementSetter(Object[] args) {
                for (Object argument : args) {
                  if (argument instanceof Instant) {
                    throw new IllegalArgumentException(
                        "PostgreSQL cannot infer the JDBC type for java.time.Instant");
                  }
                }
                return super.newArgPreparedStatementSetter(args);
              }
            });

    postgresStrictStore.scheduleExtraction(conversationId, OBSERVED_AT);

    assertThat(postgresStrictStore.claimDueExtractionWork("postgres-worker", OBSERVED_AT, 1))
        .singleElement()
        .extracting(WorkClaim::conversationId)
        .isEqualTo(conversationId);
  }

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
  void digestAudienceIsIntersectionOfSourceSegmentAudiences() {
    String originalAccountId = createAccount("digest-original@example.com");
    String laterAccountId = createAccount("digest-later@example.com");
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;digest-audience", true, "Digest", OBSERVED_AT);
    store.recordMembership(conversationId, originalAccountId, OBSERVED_AT.minusSeconds(30));

    JournalMessage firstSource =
        new JournalMessage(
            "digest-source-1",
            conversationId,
            originalAccountId,
            "First development",
            OBSERVED_AT,
            false,
            false,
            "digest-source-hash-1");
    store.recordMessage(firstSource);
    store.scheduleExtraction(conversationId, OBSERVED_AT);
    WorkClaim firstClaim =
        store.claimDueExtractionWork("digest-worker-1", OBSERVED_AT, 10).getFirst();
    store.saveExtraction(
        firstClaim,
        new ExtractionBatch(
            conversationId,
            List.of(firstSource),
            List.of(),
            "First development.",
            "[]",
            "digest-corpus-1",
            OBSERVED_AT));

    Instant laterAt = OBSERVED_AT.plusSeconds(120);
    store.recordMembership(conversationId, laterAccountId, laterAt);
    JournalMessage secondSource =
        new JournalMessage(
            "digest-source-2",
            conversationId,
            originalAccountId,
            "Second development",
            laterAt.plusSeconds(1),
            false,
            false,
            "digest-source-hash-2");
    store.recordMessage(secondSource);
    store.scheduleExtraction(conversationId, laterAt.plusSeconds(1));
    WorkClaim secondClaim =
        store.claimDueExtractionWork("digest-worker-2", laterAt.plusSeconds(1), 10).getFirst();
    store.saveExtraction(
        secondClaim,
        new ExtractionBatch(
            conversationId,
            List.of(secondSource),
            List.of(),
            "Second development.",
            "[]",
            "digest-corpus-2",
            laterAt.plusSeconds(1)));

    Instant periodStart = OBSERVED_AT.minusSeconds(60);
    Instant periodEnd = laterAt.plusSeconds(60);
    var segments = store.findSegments(conversationId, periodStart, periodEnd);
    store.seedDigestWork(conversationId, periodStart, periodEnd, periodEnd);
    var digestClaim = store.claimDueDigestWork("digest-worker", periodEnd, 10).getFirst();
    store.saveDigest(
        digestClaim,
        new DigestBatch(
            conversationId,
            periodStart,
            periodEnd,
            "Both developments.",
            "[]",
            "daily-corpus-hash",
            laterAt.plusSeconds(1),
            segments.stream().map(ConversationMemoryModels.SummaryMaterial::summaryId).toList(),
            periodEnd));

    assertThat(
            store.findAuthorizedDigests(conversationId, originalAccountId, periodStart, periodEnd))
        .hasSize(1);
    assertThat(store.findAuthorizedDigests(conversationId, laterAccountId, periodStart, periodEnd))
        .isEmpty();
  }

  @Test
  void cleanupClearsRawTextDeletesCoveredSegmentsAndQueuesExpiredArtifactDeletion() {
    String accountId = createAccount("retention@example.com");
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;retention", true, "Retention", OBSERVED_AT);
    Instant oldAt = OBSERVED_AT.minus(Duration.ofDays(100));
    store.recordMembership(conversationId, accountId, oldAt.minusSeconds(1));
    JournalMessage oldSource =
        new JournalMessage(
            "retention-old",
            conversationId,
            accountId,
            "Old source text",
            oldAt,
            false,
            false,
            "retention-old-hash");
    store.recordMessage(oldSource);
    store.scheduleExtraction(conversationId, oldAt);
    WorkClaim extractionClaim =
        store.claimDueExtractionWork("retention-extract", oldAt, 10).getFirst();
    String artifactId =
        store
            .saveExtraction(
                extractionClaim,
                new ExtractionBatch(
                    conversationId,
                    List.of(oldSource),
                    List.of(
                        new ExtractionCandidate(
                            GROUP_DECISION,
                            "The old decision expired.",
                            CONFIRMED,
                            NORMAL,
                            0.95,
                            oldAt,
                            OBSERVED_AT.minusSeconds(1),
                            List.of(oldSource.messageGuid()),
                            null,
                            "retention-artifact-hash")),
                    "An old summary.",
                    "[]",
                    "retention-segment-hash",
                    oldAt))
            .getFirst();
    store.recordMessage(
        new JournalMessage(
            "retention-new",
            conversationId,
            accountId,
            "Recent source text",
            OBSERVED_AT,
            false,
            false,
            "retention-new-hash"));

    Instant periodStart = oldAt.minusSeconds(60);
    Instant periodEnd = oldAt.plusSeconds(60);
    var oldSegments = store.findSegments(conversationId, periodStart, periodEnd);
    store.seedDigestWork(conversationId, periodStart, periodEnd, OBSERVED_AT);
    var digestClaim = store.claimDueDigestWork("retention-digest", OBSERVED_AT, 10).getFirst();
    store.saveDigest(
        digestClaim,
        new DigestBatch(
            conversationId,
            periodStart,
            periodEnd,
            "Preserved daily digest.",
            "[]",
            "retention-digest-hash",
            oldAt,
            oldSegments.stream().map(ConversationMemoryModels.SummaryMaterial::summaryId).toList(),
            OBSERVED_AT));

    var result =
        store.cleanupMemory(
            OBSERVED_AT,
            OBSERVED_AT.minus(Duration.ofDays(30)),
            OBSERVED_AT.minus(Duration.ofDays(90)));

    assertThat(result.rawMessagesCleared()).isEqualTo(1);
    assertThat(result.segmentsDeleted()).isEqualTo(1);
    assertThat(result.artifactsExpired()).isEqualTo(1);
    assertThat(store.findMessages(conversationId, oldAt.minusSeconds(1), oldAt.plusSeconds(1)))
        .singleElement()
        .extracting(JournalMessage::text)
        .isNull();
    assertThat(
            store.findMessages(
                conversationId, OBSERVED_AT.minusSeconds(1), OBSERVED_AT.plusSeconds(1)))
        .singleElement()
        .extracting(JournalMessage::text)
        .isEqualTo("Recent source text");
    assertThat(store.findSegments(conversationId, periodStart, periodEnd)).isEmpty();
    assertThat(store.findProjectionArtifact(artifactId))
        .hasValueSatisfying(
            artifact ->
                assertThat(artifact.status())
                    .isEqualTo(ConversationMemoryModels.ArtifactStatus.DELETED));
    assertThat(store.claimDueProjections("retention-project", OBSERVED_AT, 10))
        .singleElement()
        .satisfies(
            claim -> {
              assertThat(claim.artifactId()).isEqualTo(artifactId);
              assertThat(claim.operation())
                  .isEqualTo(ConversationMemoryModels.ProjectionOperation.DELETE);
            });
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
  void proactiveCatchupsAreDefaultOffPreferLatestDirectRouteAndDeduplicateDaily() {
    String accountId = createAccount("proactive@example.com");
    String groupConversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;proactive-group", true, "Project", OBSERVED_AT);
    store.recordMembership(groupConversationId, accountId, OBSERVED_AT.minusSeconds(60));
    store.enableMemory(groupConversationId, accountId, OBSERVED_AT.minusSeconds(30));

    assertThat(store.findCatchupPreference(accountId, groupConversationId)).isEmpty();
    store.saveCatchupPreference(
        accountId, groupConversationId, false, "UTC", "22:00", "08:00", OBSERVED_AT, OBSERVED_AT);
    assertThat(store.claimDueCatchupPreferences("disabled-worker", OBSERVED_AT, 10)).isEmpty();

    store.saveCatchupPreference(
        accountId, groupConversationId, true, "UTC", "22:00", "08:00", OBSERVED_AT, OBSERVED_AT);
    var claim = store.claimDueCatchupPreferences("proactive-worker", OBSERVED_AT, 10).getFirst();

    String olderDirect =
        store.upsertConversation(
            "bluebubbles", "iMessage;-;older", false, "Older", OBSERVED_AT.minusSeconds(20));
    store.recordMembership(olderDirect, accountId, OBSERVED_AT.minusSeconds(20));
    String newerDirect =
        store.upsertConversation(
            "bluebubbles", "iMessage;-;newer", false, "Newer", OBSERVED_AT.minusSeconds(10));
    store.recordMembership(newerDirect, accountId, OBSERVED_AT.minusSeconds(10));

    assertThat(store.findPreferredDirectConversation(accountId, OBSERVED_AT))
        .get()
        .satisfies(
            route -> {
              assertThat(route.conversationId()).isEqualTo(newerDirect);
              assertThat(route.externalConversationId()).isEqualTo("iMessage;-;newer");
            });

    Instant dayStart = OBSERVED_AT.minusSeconds(3600);
    Instant dayEnd = OBSERVED_AT.plusSeconds(23 * 3600);
    Instant coverage = OBSERVED_AT.minusSeconds(5);
    var delivery =
        store.createCatchupDelivery(
            claim, newerDirect, "proactive-digest-hash", coverage, dayStart, dayEnd, OBSERVED_AT);
    assertThat(delivery).isPresent();
    assertThat(
            store.createCatchupDelivery(
                claim,
                newerDirect,
                "different-hash-same-day",
                coverage,
                dayStart,
                dayEnd,
                OBSERVED_AT))
        .isEmpty();

    store.completeCatchupDelivery(delivery.orElseThrow().deliveryId(), "SENT", OBSERVED_AT);
    assertThat(store.latestSuccessfulCatchupCoverage(accountId, groupConversationId))
        .contains(coverage);
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
  void supersedingArtifactQueuesDeletionOfExistingProjections() {
    String accountId = createAccount("supersedes@example.com");
    String conversationId =
        store.upsertConversation(
            "bluebubbles", "iMessage;+;group-supersedes", true, "Plans", OBSERVED_AT);
    store.recordMembership(conversationId, accountId, OBSERVED_AT.minusSeconds(1));
    JournalMessage firstSource = message("message-first", conversationId, accountId, "Meet Friday");
    store.recordMessage(firstSource);
    store.scheduleExtraction(conversationId, OBSERVED_AT);
    WorkClaim firstClaim =
        store.claimDueExtractionWork("extract-first", OBSERVED_AT, 10).getFirst();
    String firstArtifactId =
        store
            .saveExtraction(
                firstClaim,
                new ExtractionBatch(
                    conversationId,
                    List.of(firstSource),
                    List.of(
                        new ExtractionCandidate(
                            GROUP_DECISION,
                            "Meet Friday.",
                            CONFIRMED,
                            NORMAL,
                            0.95,
                            OBSERVED_AT,
                            null,
                            List.of(firstSource.messageGuid()),
                            null,
                            "first-artifact-hash")),
                    "Friday was selected.",
                    "[]",
                    "first-corpus-hash",
                    OBSERVED_AT))
            .getFirst();
    var firstProjection = store.claimDueProjections("project-first", OBSERVED_AT, 10).getFirst();
    store.completeProjection(firstProjection, "memory-first", OBSERVED_AT);

    JournalMessage secondSource =
        message("message-second", conversationId, accountId, "Actually meet Saturday");
    store.recordMessage(secondSource);
    Instant secondAt = OBSERVED_AT.plusSeconds(1);
    store.scheduleExtraction(conversationId, secondAt);
    WorkClaim secondClaim = store.claimDueExtractionWork("extract-second", secondAt, 10).getFirst();
    store.saveExtraction(
        secondClaim,
        new ExtractionBatch(
            conversationId,
            List.of(secondSource),
            List.of(
                new ExtractionCandidate(
                    GROUP_DECISION,
                    "Meet Saturday.",
                    CONFIRMED,
                    NORMAL,
                    0.96,
                    secondAt,
                    null,
                    List.of(secondSource.messageGuid()),
                    firstArtifactId,
                    "second-artifact-hash")),
            "Saturday replaced Friday.",
            "[]",
            "second-corpus-hash",
            secondAt));

    assertThat(store.claimDueProjections("project-delete", secondAt, 10))
        .anySatisfy(
            claim -> {
              assertThat(claim.artifactId()).isEqualTo(firstArtifactId);
              assertThat(claim.operation())
                  .isEqualTo(ConversationMemoryModels.ProjectionOperation.DELETE);
            });
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
