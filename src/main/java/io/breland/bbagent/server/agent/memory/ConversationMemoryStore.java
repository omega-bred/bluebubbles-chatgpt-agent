package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity.NORMAL;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus.CONFIRMED;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionOperation.UPSERT;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExistingArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCandidate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCheckpoint;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectedArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionOperation;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationMemoryStore {
  private static final Duration EXTRACTION_LEASE = Duration.ofMinutes(5);
  private static final Duration PROJECTION_LEASE = Duration.ofMinutes(5);

  private final JdbcTemplate jdbcTemplate;

  public ConversationMemoryStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public String upsertConversation(
      String transport,
      String externalConversationId,
      boolean group,
      String displayName,
      Instant observedAt) {
    requireText(transport, "transport");
    requireText(externalConversationId, "external conversation id");
    Objects.requireNonNull(observedAt, "observedAt");
    List<String> existingIds =
        jdbcTemplate.query(
            """
            select conversation_id
              from agent_conversations
             where transport = ? and external_conversation_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            transport,
            externalConversationId);
    Instant now = observedAt;
    if (!existingIds.isEmpty()) {
      String conversationId = existingIds.getFirst();
      jdbcTemplate.update(
          """
          update agent_conversations
             set is_group = ?,
                 display_name = coalesce(?, display_name),
                 last_observed_at = ?,
                 updated_at = ?
           where conversation_id = ?
          """,
          group,
          StringUtils.trimToNull(displayName),
          observedAt,
          now,
          conversationId);
      return conversationId;
    }

    String conversationId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        insert into agent_conversations
          (conversation_id, transport, external_conversation_id, is_group, display_name,
           last_observed_at, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        conversationId,
        transport,
        externalConversationId,
        group,
        StringUtils.trimToNull(displayName),
        observedAt,
        now,
        now);
    return conversationId;
  }

  @Transactional(readOnly = true)
  public Optional<String> findConversationId(String transport, String externalConversationId) {
    return jdbcTemplate
        .query(
            """
            select conversation_id from agent_conversations
             where transport = ? and external_conversation_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            transport,
            externalConversationId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<String> findEnabledConversationId(
      String transport, String externalConversationId) {
    return jdbcTemplate
        .query(
            """
            select conversation_id from agent_conversations
             where transport = ? and external_conversation_id = ? and memory_enabled_at is not null
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            transport,
            externalConversationId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<ConversationRecord> findConversation(String conversationId) {
    return jdbcTemplate
        .query(
            """
            select conversation_id, transport, external_conversation_id, is_group, display_name,
                   memory_enabled_at, memory_enabled_by_account_id, last_observed_at
              from agent_conversations where conversation_id = ?
            """,
            (resultSet, rowNumber) ->
                new ConversationRecord(
                    resultSet.getString("conversation_id"),
                    resultSet.getString("transport"),
                    resultSet.getString("external_conversation_id"),
                    resultSet.getBoolean("is_group"),
                    resultSet.getString("display_name"),
                    toInstant(resultSet.getTimestamp("memory_enabled_at")),
                    resultSet.getString("memory_enabled_by_account_id"),
                    resultSet.getTimestamp("last_observed_at").toInstant()),
            conversationId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<ConversationRecord> findConversation(
      String transport, String externalConversationId) {
    return findConversationId(transport, externalConversationId).flatMap(this::findConversation);
  }

  @Transactional
  public void enableMemory(String conversationId, String accountId, Instant enabledAt) {
    requireText(accountId, "account id");
    int updated =
        jdbcTemplate.update(
            """
            update agent_conversations
               set memory_enabled_at = ?, memory_enabled_by_account_id = ?, updated_at = ?
             where conversation_id = ? and is_group = true
            """,
            enabledAt,
            accountId,
            enabledAt,
            conversationId);
    if (updated != 1) {
      throw new IllegalArgumentException("group conversation not found");
    }
    jdbcTemplate.update(
        "delete from agent_conversation_messages where conversation_id = ? and source_timestamp < ?",
        conversationId,
        enabledAt);
    jdbcTemplate.update(
        "delete from conversation_memory_work where conversation_id = ?", conversationId);
  }

  @Transactional
  public void disableMemory(String conversationId, Instant disabledAt) {
    jdbcTemplate.update(
        """
        update agent_conversations
           set memory_enabled_at = null, memory_enabled_by_account_id = null, updated_at = ?
         where conversation_id = ?
        """,
        disabledAt,
        conversationId);
    jdbcTemplate.update(
        "delete from conversation_memory_work where conversation_id = ?", conversationId);
    jdbcTemplate.update(
        "delete from agent_conversation_messages where conversation_id = ?", conversationId);
    jdbcTemplate.update(
        """
        update conversation_memory_artifacts
           set status = 'DELETED', updated_at = ?
         where conversation_id = ? and status <> 'DELETED'
        """,
        disabledAt,
        conversationId);
    jdbcTemplate.update(
        """
        update conversation_memory_projections
           set operation = 'DELETE', state = 'PENDING', available_at = ?, claimed_by = null,
               claimed_until = null, last_error_code = null, updated_at = ?
         where artifact_id in (
           select artifact_id from conversation_memory_artifacts where conversation_id = ?
         )
        """,
        disabledAt,
        disabledAt,
        conversationId);
  }

  @Transactional(readOnly = true)
  public Optional<Instant> extractionAvailableAt(String conversationId) {
    return jdbcTemplate
        .query(
            "select available_at from conversation_memory_work where conversation_id = ?",
            (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
            conversationId)
        .stream()
        .findFirst();
  }

  @Transactional
  public void recordMembership(String conversationId, String accountId, Instant observedAt) {
    requireText(conversationId, "conversation id");
    requireText(accountId, "account id");
    Objects.requireNonNull(observedAt, "observedAt");
    List<String> activeMemberships =
        jdbcTemplate.query(
            """
            select membership_id
              from agent_conversation_memberships
             where conversation_id = ? and account_id = ? and ended_at is null
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            conversationId,
            accountId);
    if (!activeMemberships.isEmpty()) {
      jdbcTemplate.update(
          "update agent_conversation_memberships set updated_at = ? where membership_id = ?",
          observedAt,
          activeMemberships.getFirst());
      return;
    }
    jdbcTemplate.update(
        """
        insert into agent_conversation_memberships
          (membership_id, conversation_id, account_id, started_at, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        conversationId,
        accountId,
        observedAt,
        observedAt,
        observedAt);
  }

  @Transactional
  public void recordMessage(JournalMessage message) {
    Objects.requireNonNull(message, "message");
    requireText(message.messageGuid(), "message guid");
    requireText(message.conversationId(), "conversation id");
    requireText(message.contentHash(), "content hash");
    Objects.requireNonNull(message.sourceTimestamp(), "sourceTimestamp");
    Integer existing =
        jdbcTemplate.queryForObject(
            "select count(*) from agent_conversation_messages where message_guid = ?",
            Integer.class,
            message.messageGuid());
    Instant now = message.sourceTimestamp();
    if (existing != null && existing > 0) {
      jdbcTemplate.update(
          """
          update agent_conversation_messages
             set conversation_id = ?, sender_account_id = ?, message_text = ?, content_hash = ?,
                 source_timestamp = ?, from_agent = ?, system_message = ?, removed = false,
                 updated_at = ?
           where message_guid = ?
          """,
          message.conversationId(),
          StringUtils.trimToNull(message.senderAccountId()),
          message.text(),
          message.contentHash(),
          message.sourceTimestamp(),
          message.fromAgent(),
          message.systemMessage(),
          now,
          message.messageGuid());
      return;
    }
    jdbcTemplate.update(
        """
        insert into agent_conversation_messages
          (message_guid, conversation_id, sender_account_id, message_text, content_hash,
           source_timestamp, from_agent, system_message, removed, first_seen_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?)
        """,
        message.messageGuid(),
        message.conversationId(),
        StringUtils.trimToNull(message.senderAccountId()),
        message.text(),
        message.contentHash(),
        message.sourceTimestamp(),
        message.fromAgent(),
        message.systemMessage(),
        now,
        now);
  }

  @Transactional(readOnly = true)
  public List<JournalMessage> findMessages(
      String conversationId, Instant fromInclusive, Instant toInclusive) {
    return jdbcTemplate.query(
        """
        select message_guid, conversation_id, sender_account_id, message_text, source_timestamp,
               from_agent, system_message, content_hash
          from agent_conversation_messages
         where conversation_id = ? and source_timestamp >= ? and source_timestamp <= ?
           and removed = false
         order by source_timestamp, message_guid
        """,
        (resultSet, rowNumber) ->
            new JournalMessage(
                resultSet.getString("message_guid"),
                resultSet.getString("conversation_id"),
                resultSet.getString("sender_account_id"),
                resultSet.getString("message_text"),
                resultSet.getTimestamp("source_timestamp").toInstant(),
                resultSet.getBoolean("from_agent"),
                resultSet.getBoolean("system_message"),
                resultSet.getString("content_hash")),
        conversationId,
        fromInclusive,
        toInclusive);
  }

  @Transactional(readOnly = true)
  public List<String> activeMembershipAccountIds(String conversationId, Instant at) {
    return jdbcTemplate.query(
        """
        select distinct account_id
          from agent_conversation_memberships
         where conversation_id = ? and started_at <= ?
           and (ended_at is null or ended_at > ?)
         order by account_id
        """,
        (resultSet, rowNumber) -> resultSet.getString(1),
        conversationId,
        at,
        at);
  }

  @Transactional
  public void replaceActiveMemberships(
      String conversationId, Set<String> accountIds, Instant observedAt) {
    Set<String> desiredAccountIds = accountIds == null ? Set.of() : Set.copyOf(accountIds);
    List<ActiveMembership> activeMemberships =
        jdbcTemplate.query(
            """
            select membership_id, account_id from agent_conversation_memberships
             where conversation_id = ? and ended_at is null
            """,
            (resultSet, rowNumber) ->
                new ActiveMembership(
                    resultSet.getString("membership_id"), resultSet.getString("account_id")),
            conversationId);
    Set<String> existingAccountIds = new HashSet<>();
    for (ActiveMembership membership : activeMemberships) {
      if (desiredAccountIds.contains(membership.accountId())) {
        existingAccountIds.add(membership.accountId());
        continue;
      }
      jdbcTemplate.update(
          """
          update agent_conversation_memberships
             set ended_at = ?, updated_at = ?
           where membership_id = ? and ended_at is null
          """,
          observedAt,
          observedAt,
          membership.membershipId());
    }
    for (String accountId : desiredAccountIds) {
      if (!existingAccountIds.contains(accountId)) {
        recordMembership(conversationId, accountId, observedAt);
      }
    }
  }

  @Transactional(readOnly = true)
  public Optional<String> latestSenderAccountId(String conversationId) {
    return jdbcTemplate
        .query(
            """
            select sender_account_id from agent_conversation_messages
             where conversation_id = ? and sender_account_id is not null and removed = false
             order by source_timestamp desc, message_guid desc
             limit 1
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            conversationId)
        .stream()
        .findFirst();
  }

  @Transactional
  public void scheduleExtraction(String conversationId, Instant availableAt) {
    Integer existing =
        jdbcTemplate.queryForObject(
            "select count(*) from conversation_memory_work where conversation_id = ?",
            Integer.class,
            conversationId);
    if (existing != null && existing > 0) {
      jdbcTemplate.update(
          """
          update conversation_memory_work
             set available_at = ?, last_error_code = null, updated_at = ?
           where conversation_id = ?
          """,
          availableAt,
          availableAt,
          conversationId);
      return;
    }
    jdbcTemplate.update(
        """
        insert into conversation_memory_work
          (conversation_id, available_at, attempt_count, updated_at)
        values (?, ?, 0, ?)
        """,
        conversationId,
        availableAt,
        availableAt);
  }

  @Transactional
  public List<WorkClaim> claimDueExtractionWork(String workerId, Instant now, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    Instant claimedUntil = now.plus(EXTRACTION_LEASE);
    List<String> candidates =
        jdbcTemplate.query(
            """
            select conversation_id
              from conversation_memory_work
             where available_at <= ? and (claimed_until is null or claimed_until < ?)
             order by available_at, conversation_id
             limit ?
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            now,
            now,
            limit);
    List<WorkClaim> claims = new ArrayList<>();
    for (String conversationId : candidates) {
      int updated =
          jdbcTemplate.update(
              """
              update conversation_memory_work
                 set claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1,
                     updated_at = ?
               where conversation_id = ? and available_at <= ?
                 and (claimed_until is null or claimed_until < ?)
              """,
              workerId,
              claimedUntil,
              now,
              conversationId,
              now,
              now);
      if (updated == 1) {
        claims.add(new WorkClaim(conversationId, workerId, claimedUntil));
      }
    }
    return List.copyOf(claims);
  }

  @Transactional(readOnly = true)
  public Optional<ExtractionCheckpoint> findCheckpoint(String conversationId) {
    return jdbcTemplate
        .query(
            """
            select last_processed_at, last_processed_message_guid, last_corpus_hash
              from conversation_memory_checkpoints
             where conversation_id = ?
            """,
            (resultSet, rowNumber) ->
                new ExtractionCheckpoint(
                    toInstant(resultSet.getTimestamp("last_processed_at")),
                    resultSet.getString("last_processed_message_guid"),
                    resultSet.getString("last_corpus_hash")),
            conversationId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public List<ExistingArtifact> findActiveArtifacts(String conversationId) {
    return jdbcTemplate.query(
        """
        select artifact_id, kind, artifact_text, status, occurred_at
          from conversation_memory_artifacts
         where conversation_id = ? and status not in ('SUPERSEDED', 'DELETED')
         order by occurred_at, artifact_id
        """,
        (resultSet, rowNumber) ->
            new ExistingArtifact(
                resultSet.getString("artifact_id"),
                ConversationMemoryModels.ArtifactKind.valueOf(resultSet.getString("kind")),
                resultSet.getString("artifact_text"),
                ConversationMemoryModels.ArtifactStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("occurred_at").toInstant()),
        conversationId);
  }

  @Transactional
  public void completeUnchangedExtraction(WorkClaim claim, Instant completedAt) {
    int deleted =
        jdbcTemplate.update(
            """
            delete from conversation_memory_work
             where conversation_id = ? and claimed_by = ? and claimed_until >= ?
               and available_at <= ?
            """,
            claim.conversationId(),
            claim.workerId(),
            completedAt,
            completedAt);
    if (deleted == 0) {
      releaseClaimForFutureWork(claim, completedAt);
    }
  }

  @Transactional
  public void failExtractionWork(WorkClaim claim, Instant failedAt, String errorCode) {
    requireText(errorCode, "extraction error code");
    jdbcTemplate.update(
        """
        update conversation_memory_work
           set available_at = case
                 when available_at > ? then available_at
                 else ?
               end,
               claimed_by = null, claimed_until = null, last_error_code = ?, updated_at = ?
         where conversation_id = ? and claimed_by = ? and claimed_until >= ?
        """,
        failedAt,
        failedAt.plusSeconds(30),
        StringUtils.truncate(errorCode, 64),
        failedAt,
        claim.conversationId(),
        claim.workerId(),
        failedAt);
  }

  @Transactional
  public List<String> saveExtraction(WorkClaim claim, ExtractionBatch batch) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(batch, "batch");
    if (!claim.conversationId().equals(batch.conversationId())) {
      throw new IllegalArgumentException("claim and extraction conversation do not match");
    }
    Integer ownedClaim =
        jdbcTemplate.queryForObject(
            """
            select count(*) from conversation_memory_work
             where conversation_id = ? and claimed_by = ? and claimed_until >= ?
            """,
            Integer.class,
            claim.conversationId(),
            claim.workerId(),
            batch.processedAt());
    if (ownedClaim == null || ownedClaim != 1) {
      throw new IllegalStateException("extraction work lease is not owned by this worker");
    }

    Set<String> submittedMessageGuids = new HashSet<>();
    for (JournalMessage sourceMessage : batch.sourceMessages()) {
      if (!batch.conversationId().equals(sourceMessage.conversationId())) {
        throw new IllegalArgumentException("source message belongs to another conversation");
      }
      submittedMessageGuids.add(sourceMessage.messageGuid());
    }

    List<String> savedArtifactIds = new ArrayList<>();
    for (ExtractionCandidate candidate : batch.candidates()) {
      validateCandidateEvidence(candidate, submittedMessageGuids);
      List<String> existingArtifactIds =
          jdbcTemplate.query(
              """
              select artifact_id from conversation_memory_artifacts
               where conversation_id = ? and content_hash = ? and occurred_at = ?
              """,
              (resultSet, rowNumber) -> resultSet.getString(1),
              batch.conversationId(),
              candidate.contentHash(),
              candidate.occurredAt());
      if (!existingArtifactIds.isEmpty()) {
        savedArtifactIds.add(existingArtifactIds.getFirst());
        continue;
      }

      String artifactId = UUID.randomUUID().toString();
      Instant now = batch.processedAt();
      jdbcTemplate.update(
          """
          insert into conversation_memory_artifacts
            (artifact_id, conversation_id, kind, artifact_text, status, sensitivity, confidence,
             occurred_at, expires_at, superseded_by_artifact_id, content_hash, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?)
          """,
          artifactId,
          batch.conversationId(),
          candidate.kind().name(),
          candidate.text(),
          candidate.status().name(),
          candidate.sensitivity().name(),
          candidate.confidence(),
          candidate.occurredAt(),
          candidate.expiresAt(),
          candidate.contentHash(),
          now,
          now);
      for (String messageGuid : candidate.evidenceMessageGuids()) {
        jdbcTemplate.update(
            "insert into conversation_memory_evidence (artifact_id, message_guid) values (?, ?)",
            artifactId,
            messageGuid);
      }
      List<String> audienceAccountIds =
          activeMembershipAccountIds(batch.conversationId(), candidate.occurredAt());
      for (String accountId : audienceAccountIds) {
        jdbcTemplate.update(
            """
            insert into conversation_memory_audiences (artifact_id, account_id, granted_at)
            values (?, ?, ?)
            """,
            artifactId,
            accountId,
            now);
        if (isProjectionEligible(candidate)) {
          jdbcTemplate.update(
              """
              insert into conversation_memory_projections
                (artifact_id, account_id, operation, state, projection_hash, available_at,
                 attempt_count, updated_at)
              values (?, ?, ?, 'PENDING', ?, ?, 0, ?)
              """,
              artifactId,
              accountId,
              UPSERT.name(),
              candidate.contentHash(),
              now,
              now);
        }
      }
      if (StringUtils.isNotBlank(candidate.supersedesArtifactId())) {
        jdbcTemplate.update(
            """
            update conversation_memory_artifacts
               set status = 'SUPERSEDED', superseded_by_artifact_id = ?, updated_at = ?
             where artifact_id = ? and conversation_id = ?
            """,
            artifactId,
            now,
            candidate.supersedesArtifactId(),
            batch.conversationId());
        jdbcTemplate.update(
            """
            update conversation_memory_projections
               set operation = 'DELETE', state = 'PENDING', available_at = ?,
                   claimed_by = null, claimed_until = null, last_error_code = null, updated_at = ?
             where artifact_id = ?
            """,
            now,
            now,
            candidate.supersedesArtifactId());
      }
      savedArtifactIds.add(artifactId);
    }

    saveSummarySegment(batch);
    saveCheckpoint(batch);
    int deleted =
        jdbcTemplate.update(
            """
            delete from conversation_memory_work
             where conversation_id = ? and claimed_by = ? and available_at <= ?
            """,
            claim.conversationId(),
            claim.workerId(),
            batch.processedAt());
    if (deleted == 0) {
      releaseClaimForFutureWork(claim, batch.processedAt());
    }
    return List.copyOf(savedArtifactIds);
  }

  @Transactional
  public List<ProjectionClaim> claimDueProjections(String workerId, Instant now, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    Instant claimedUntil = now.plus(PROJECTION_LEASE);
    List<ProjectionCandidate> candidates =
        jdbcTemplate.query(
            """
            select artifact_id, account_id, operation, projection_hash
              from conversation_memory_projections
             where state in ('PENDING', 'FAILED') and available_at <= ?
               and (claimed_until is null or claimed_until < ?)
             order by available_at, artifact_id, account_id
             limit ?
            """,
            (resultSet, rowNumber) ->
                new ProjectionCandidate(
                    resultSet.getString("artifact_id"),
                    resultSet.getString("account_id"),
                    ProjectionOperation.valueOf(resultSet.getString("operation")),
                    resultSet.getString("projection_hash")),
            now,
            now,
            limit);
    List<ProjectionClaim> claims = new ArrayList<>();
    for (ProjectionCandidate candidate : candidates) {
      int updated =
          jdbcTemplate.update(
              """
              update conversation_memory_projections
                 set claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1,
                     updated_at = ?
               where artifact_id = ? and account_id = ? and state in ('PENDING', 'FAILED')
                 and available_at <= ? and (claimed_until is null or claimed_until < ?)
              """,
              workerId,
              claimedUntil,
              now,
              candidate.artifactId(),
              candidate.accountId(),
              now,
              now);
      if (updated == 1) {
        claims.add(
            new ProjectionClaim(
                candidate.artifactId(),
                candidate.accountId(),
                candidate.operation(),
                candidate.projectionHash(),
                workerId,
                claimedUntil));
      }
    }
    return List.copyOf(claims);
  }

  @Transactional(readOnly = true)
  public Optional<ProjectionArtifact> findProjectionArtifact(String artifactId) {
    return jdbcTemplate
        .query(
            """
            select a.artifact_id, a.conversation_id, c.display_name, a.kind, a.artifact_text,
                   a.status, a.sensitivity, a.confidence, a.occurred_at, a.expires_at
              from conversation_memory_artifacts a
              join agent_conversations c on c.conversation_id = a.conversation_id
             where a.artifact_id = ?
            """,
            (resultSet, rowNumber) -> projectionArtifact(resultSet),
            artifactId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<ProjectedArtifact> findProjectedArtifact(String mem0MemoryId, String accountId) {
    return jdbcTemplate
        .query(
            """
            select a.artifact_id, p.mem0_memory_id, a.conversation_id, c.display_name, a.kind,
                   a.artifact_text, a.status, a.sensitivity, a.confidence, a.occurred_at,
                   a.expires_at
              from conversation_memory_projections p
              join conversation_memory_artifacts a on a.artifact_id = p.artifact_id
              join agent_conversations c on c.conversation_id = a.conversation_id
             where p.mem0_memory_id = ? and p.account_id = ? and p.operation = 'UPSERT'
               and p.state = 'SUCCEEDED'
            """,
            (resultSet, rowNumber) ->
                new ProjectedArtifact(
                    resultSet.getString("artifact_id"),
                    resultSet.getString("mem0_memory_id"),
                    resultSet.getString("conversation_id"),
                    resultSet.getString("display_name"),
                    ConversationMemoryModels.ArtifactKind.valueOf(resultSet.getString("kind")),
                    resultSet.getString("artifact_text"),
                    ConversationMemoryModels.ArtifactStatus.valueOf(resultSet.getString("status")),
                    ConversationMemoryModels.ArtifactSensitivity.valueOf(
                        resultSet.getString("sensitivity")),
                    resultSet.getDouble("confidence"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    toInstant(resultSet.getTimestamp("expires_at"))),
            mem0MemoryId,
            accountId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<String> projectionMemoryId(String artifactId, String accountId) {
    return jdbcTemplate
        .query(
            """
            select mem0_memory_id from conversation_memory_projections
             where artifact_id = ? and account_id = ? and mem0_memory_id is not null
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            artifactId,
            accountId)
        .stream()
        .findFirst();
  }

  @Transactional
  public void completeProjection(
      ProjectionClaim claim, @Nullable String mem0MemoryId, Instant completedAt) {
    int updated =
        jdbcTemplate.update(
            """
            update conversation_memory_projections
               set state = 'SUCCEEDED', mem0_memory_id = ?, claimed_by = null,
                   claimed_until = null, last_error_code = null, updated_at = ?
             where artifact_id = ? and account_id = ? and claimed_by = ? and claimed_until >= ?
            """,
            StringUtils.trimToNull(mem0MemoryId),
            completedAt,
            claim.artifactId(),
            claim.accountId(),
            claim.workerId(),
            completedAt);
    if (updated != 1) {
      throw new IllegalStateException("projection work lease is not owned by this worker");
    }
  }

  @Transactional
  public void failProjection(ProjectionClaim claim, Instant failedAt, String errorCode) {
    Integer attempts =
        jdbcTemplate.queryForObject(
            """
            select attempt_count from conversation_memory_projections
             where artifact_id = ? and account_id = ? and claimed_by = ? and claimed_until >= ?
            """,
            Integer.class,
            claim.artifactId(),
            claim.accountId(),
            claim.workerId(),
            failedAt);
    if (attempts == null) {
      return;
    }
    Duration retryDelay = projectionRetryDelay(attempts);
    jdbcTemplate.update(
        """
        update conversation_memory_projections
           set state = 'FAILED', available_at = ?, claimed_by = null, claimed_until = null,
               last_error_code = ?, updated_at = ?
         where artifact_id = ? and account_id = ? and claimed_by = ? and claimed_until >= ?
        """,
        failedAt.plus(retryDelay),
        StringUtils.truncate(errorCode, 64),
        failedAt,
        claim.artifactId(),
        claim.accountId(),
        claim.workerId(),
        failedAt);
  }

  @Transactional(readOnly = true)
  public boolean isInArtifactAudience(String artifactId, String accountId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*) from conversation_memory_audiences
             where artifact_id = ? and account_id = ?
            """,
            Integer.class,
            artifactId,
            accountId);
    return count != null && count > 0;
  }

  @Transactional(readOnly = true)
  public boolean isReadOnlyGroupArtifact(String canonicalScope, String identifier) {
    ScopeKey scope = parseCanonicalScope(canonicalScope);
    if (!scope.type().equals("ACCOUNT") || StringUtils.isBlank(identifier)) {
      return false;
    }
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from conversation_memory_audiences audience
              left join conversation_memory_projections projection
                on projection.artifact_id = audience.artifact_id
               and projection.account_id = audience.account_id
             where audience.account_id = ?
               and (audience.artifact_id = ? or projection.mem0_memory_id = ?)
            """,
            Integer.class,
            scope.id(),
            identifier,
            identifier);
    return count != null && count > 0;
  }

  @Transactional
  public void recordCanonicalMemory(
      String canonicalScope, String mem0MemoryId, String contentHash, Instant recordedAt) {
    ScopeKey scope = parseCanonicalScope(canonicalScope);
    requireText(mem0MemoryId, "Mem0 memory id");
    requireText(contentHash, "memory content hash");
    Objects.requireNonNull(recordedAt, "recordedAt");
    List<ScopeKey> existingScopes =
        jdbcTemplate.query(
            "select scope_type, scope_id from canonical_memory_records where mem0_memory_id = ?",
            (resultSet, rowNumber) ->
                new ScopeKey(resultSet.getString("scope_type"), resultSet.getString("scope_id")),
            mem0MemoryId);
    if (!existingScopes.isEmpty()) {
      ScopeKey existing = existingScopes.getFirst();
      if (!existing.equals(scope)) {
        throw new IllegalStateException("memory id is already owned by another canonical scope");
      }
      jdbcTemplate.update(
          """
          update canonical_memory_records
             set content_hash = ?, updated_at = ?
           where mem0_memory_id = ? and scope_type = ? and scope_id = ?
          """,
          contentHash,
          recordedAt,
          mem0MemoryId,
          scope.type(),
          scope.id());
      return;
    }
    jdbcTemplate.update(
        """
        insert into canonical_memory_records
          (memory_record_id, scope_type, scope_id, mem0_memory_id, content_hash, created_at,
           updated_at)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        scope.type(),
        scope.id(),
        mem0MemoryId,
        contentHash,
        recordedAt,
        recordedAt);
  }

  @Transactional(readOnly = true)
  public boolean ownsCanonicalMemory(String canonicalScope, String mem0MemoryId) {
    ScopeKey scope = parseCanonicalScope(canonicalScope);
    if (StringUtils.isBlank(mem0MemoryId)) {
      return false;
    }
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*) from canonical_memory_records
             where scope_type = ? and scope_id = ? and mem0_memory_id = ?
            """,
            Integer.class,
            scope.type(),
            scope.id(),
            mem0MemoryId);
    return count != null && count > 0;
  }

  @Transactional
  public void updateCanonicalMemory(
      String canonicalScope, String mem0MemoryId, String contentHash, Instant updatedAt) {
    ScopeKey scope = parseCanonicalScope(canonicalScope);
    int updated =
        jdbcTemplate.update(
            """
            update canonical_memory_records set content_hash = ?, updated_at = ?
             where scope_type = ? and scope_id = ? and mem0_memory_id = ?
            """,
            contentHash,
            updatedAt,
            scope.type(),
            scope.id(),
            mem0MemoryId);
    if (updated != 1) {
      throw new IllegalStateException("canonical memory ownership changed");
    }
  }

  @Transactional
  public void deleteCanonicalMemory(String canonicalScope, String mem0MemoryId) {
    ScopeKey scope = parseCanonicalScope(canonicalScope);
    jdbcTemplate.update(
        """
        delete from canonical_memory_records
         where scope_type = ? and scope_id = ? and mem0_memory_id = ?
        """,
        scope.type(),
        scope.id(),
        mem0MemoryId);
  }

  private void validateCandidateEvidence(
      ExtractionCandidate candidate, Set<String> submittedMessageGuids) {
    Objects.requireNonNull(candidate, "candidate");
    requireText(candidate.text(), "artifact text");
    requireText(candidate.contentHash(), "artifact content hash");
    if (candidate.evidenceMessageGuids().isEmpty()
        || !submittedMessageGuids.containsAll(candidate.evidenceMessageGuids())) {
      throw new IllegalArgumentException("artifact evidence is outside the submitted batch");
    }
  }

  private boolean isProjectionEligible(ExtractionCandidate candidate) {
    return candidate.status() == CONFIRMED
        && candidate.sensitivity() == NORMAL
        && candidate.confidence() >= 0.85;
  }

  private void saveSummarySegment(ExtractionBatch batch) {
    if (StringUtils.isBlank(batch.summary()) || batch.sourceMessages().isEmpty()) {
      return;
    }
    Integer existing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from conversation_summary_segments
             where conversation_id = ? and corpus_hash = ?
            """,
            Integer.class,
            batch.conversationId(),
            batch.corpusHash());
    if (existing != null && existing > 0) {
      return;
    }
    Instant windowStart =
        batch.sourceMessages().stream()
            .map(JournalMessage::sourceTimestamp)
            .min(Instant::compareTo)
            .orElse(batch.processedAt());
    Instant windowEnd =
        batch.sourceMessages().stream()
            .map(JournalMessage::sourceTimestamp)
            .max(Instant::compareTo)
            .orElse(batch.processedAt());
    String segmentId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        insert into conversation_summary_segments
          (segment_id, conversation_id, window_start, window_end, summary_text, item_payload,
           corpus_hash, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        segmentId,
        batch.conversationId(),
        windowStart,
        windowEnd,
        batch.summary(),
        StringUtils.defaultString(batch.itemPayload(), "[]"),
        batch.corpusHash(),
        batch.processedAt());
    Set<String> audienceAccountIds = new HashSet<>();
    for (JournalMessage sourceMessage : batch.sourceMessages()) {
      audienceAccountIds.addAll(
          activeMembershipAccountIds(batch.conversationId(), sourceMessage.sourceTimestamp()));
    }
    for (String accountId : audienceAccountIds) {
      jdbcTemplate.update(
          """
          insert into conversation_summary_audiences
            (summary_type, summary_id, account_id, granted_at)
          values ('SEGMENT', ?, ?, ?)
          """,
          segmentId,
          accountId,
          batch.processedAt());
    }
  }

  private void saveCheckpoint(ExtractionBatch batch) {
    JournalMessage lastMessage =
        batch.sourceMessages().stream()
            .max(
                java.util.Comparator.comparing(JournalMessage::sourceTimestamp)
                    .thenComparing(JournalMessage::messageGuid))
            .orElse(null);
    Instant lastProcessedAt =
        lastMessage == null ? batch.processedAt() : lastMessage.sourceTimestamp();
    String lastMessageGuid = lastMessage == null ? null : lastMessage.messageGuid();
    Integer existing =
        jdbcTemplate.queryForObject(
            "select count(*) from conversation_memory_checkpoints where conversation_id = ?",
            Integer.class,
            batch.conversationId());
    if (existing != null && existing > 0) {
      jdbcTemplate.update(
          """
          update conversation_memory_checkpoints
             set last_processed_at = ?, last_processed_message_guid = ?, last_corpus_hash = ?,
                 updated_at = ?
           where conversation_id = ?
          """,
          lastProcessedAt,
          lastMessageGuid,
          batch.corpusHash(),
          batch.processedAt(),
          batch.conversationId());
      return;
    }
    jdbcTemplate.update(
        """
        insert into conversation_memory_checkpoints
          (conversation_id, last_processed_at, last_processed_message_guid, last_corpus_hash,
           updated_at)
        values (?, ?, ?, ?, ?)
        """,
        batch.conversationId(),
        lastProcessedAt,
        lastMessageGuid,
        batch.corpusHash(),
        batch.processedAt());
  }

  private void releaseClaimForFutureWork(WorkClaim claim, Instant releasedAt) {
    jdbcTemplate.update(
        """
        update conversation_memory_work
           set claimed_by = null, claimed_until = null, updated_at = ?
         where conversation_id = ? and claimed_by = ? and claimed_until >= ?
        """,
        releasedAt,
        claim.conversationId(),
        claim.workerId(),
        releasedAt);
  }

  private ProjectionArtifact projectionArtifact(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new ProjectionArtifact(
        resultSet.getString("artifact_id"),
        resultSet.getString("conversation_id"),
        resultSet.getString("display_name"),
        ConversationMemoryModels.ArtifactKind.valueOf(resultSet.getString("kind")),
        resultSet.getString("artifact_text"),
        ConversationMemoryModels.ArtifactStatus.valueOf(resultSet.getString("status")),
        ConversationMemoryModels.ArtifactSensitivity.valueOf(resultSet.getString("sensitivity")),
        resultSet.getDouble("confidence"),
        resultSet.getTimestamp("occurred_at").toInstant(),
        toInstant(resultSet.getTimestamp("expires_at")));
  }

  private Duration projectionRetryDelay(int attempts) {
    if (attempts <= 1) {
      return Duration.ofSeconds(30);
    }
    if (attempts == 2) {
      return Duration.ofMinutes(2);
    }
    if (attempts == 3) {
      return Duration.ofMinutes(10);
    }
    return Duration.ofHours(1);
  }

  private void requireText(String value, String label) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException("missing " + label);
    }
  }

  private ScopeKey parseCanonicalScope(String canonicalScope) {
    requireText(canonicalScope, "canonical scope");
    String[] parts = canonicalScope.split(":", 2);
    if (parts.length != 2 || StringUtils.isBlank(parts[1])) {
      throw new IllegalArgumentException("invalid canonical memory scope");
    }
    String type =
        switch (parts[0]) {
          case "account" -> "ACCOUNT";
          case "conversation" -> "CONVERSATION";
          default -> throw new IllegalArgumentException("invalid canonical memory scope");
        };
    return new ScopeKey(type, parts[1]);
  }

  private Instant toInstant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record ProjectionCandidate(
      String artifactId, String accountId, ProjectionOperation operation, String projectionHash) {}

  private record ActiveMembership(String membershipId, String accountId) {}

  private record ScopeKey(String type, String id) {}
}
