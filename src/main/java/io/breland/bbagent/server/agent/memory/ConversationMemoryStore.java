package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.TimeSupport.offset;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity.NORMAL;
import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus.CONFIRMED;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreference;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestWorkClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DirectConversationRoute;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExistingArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCandidate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCheckpoint;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryBacklog;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryCleanupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProactiveDelivery;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.SummaryMaterial;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.MembershipInterval;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationMemoryStore {
  private static final Duration EXTRACTION_LEASE = Duration.ofMinutes(5);
  private static final Duration CATCHUP_LEASE = Duration.ofMinutes(5);
  private static final int MAX_JOURNAL_PAGE_SIZE = 500;
  private static final RowMapper<ConversationRecord> CONVERSATION_ROW_MAPPER =
      (resultSet, rowNumber) ->
          new ConversationRecord(
              resultSet.getString("conversation_id"),
              resultSet.getString("transport"),
              resultSet.getString("external_conversation_id"),
              resultSet.getBoolean("is_group"),
              resultSet.getString("display_name"),
              toInstant(resultSet.getTimestamp("memory_enabled_at")),
              resultSet.getString("memory_enabled_by_account_id"),
              resultSet.getTimestamp("last_observed_at").toInstant());
  private static final RowMapper<JournalMessage> JOURNAL_MESSAGE_ROW_MAPPER =
      (resultSet, rowNumber) ->
          new JournalMessage(
              resultSet.getString("message_guid"),
              resultSet.getString("conversation_id"),
              resultSet.getString("sender_account_id"),
              resultSet.getString("message_text"),
              resultSet.getTimestamp("source_timestamp").toInstant(),
              resultSet.getBoolean("from_agent"),
              resultSet.getBoolean("system_message"),
              resultSet.getString("content_hash"));

  private final PostgresCompatibleJdbcTemplate jdbcTemplate;
  private final double minimumConfidence;
  private final HindsightMemoryStore hindsight;

  @Autowired
  public ConversationMemoryStore(
      JdbcTemplate jdbcTemplate,
      @Value("${bbagent.memory.group.minimum-confidence:0.85}") double minimumConfidence) {
    this.jdbcTemplate = new PostgresCompatibleJdbcTemplate(jdbcTemplate);
    this.minimumConfidence = minimumConfidence;
    this.hindsight = new HindsightMemoryStore(jdbcTemplate);
  }

  public ConversationMemoryStore(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, 0.85);
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
            CONVERSATION_ROW_MAPPER,
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
        update hindsight_memory_documents
           set operation = 'DELETE', state = 'PENDING', available_at = ?, attempt_count = 0, last_error_code = null, updated_at = ?
         where bank_id in (
           select bank_id from hindsight_memory_banks where conversation_id = ?
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
        JOURNAL_MESSAGE_ROW_MAPPER,
        conversationId,
        fromInclusive,
        toInclusive);
  }

  @Transactional(readOnly = true)
  public List<JournalMessage> findMessagePageDescending(
      String conversationId,
      Instant fromInclusive,
      Instant toExclusive,
      @Nullable Instant beforeTimestamp,
      @Nullable String beforeMessageGuid,
      int limit,
      Duration remaining) {
    requireText(conversationId, "conversation id");
    if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("journal page range is invalid");
    }
    String normalizedBeforeGuid = StringUtils.trimToNull(beforeMessageGuid);
    if ((beforeTimestamp == null) != (normalizedBeforeGuid == null)) {
      throw new IllegalArgumentException("journal page cursor is incomplete");
    }
    if (beforeTimestamp != null
        && (beforeTimestamp.isBefore(fromInclusive) || !beforeTimestamp.isBefore(toExclusive))) {
      throw new IllegalArgumentException("journal page cursor is outside the requested range");
    }
    if (limit < 1 || limit > MAX_JOURNAL_PAGE_SIZE) {
      throw new IllegalArgumentException("journal page limit must be between 1 and 500");
    }
    if (remaining == null || remaining.isZero() || remaining.isNegative()) {
      throw new IllegalArgumentException("journal page remaining time must be positive");
    }

    if (beforeTimestamp == null) {
      return jdbcTemplate.query(
          """
          select message_guid, conversation_id, sender_account_id, message_text, source_timestamp,
                 from_agent, system_message, content_hash
            from agent_conversation_messages
           where conversation_id = ? and source_timestamp >= ? and source_timestamp < ?
             and removed = false
           order by source_timestamp desc, message_guid desc
           limit ?
          """,
          remaining,
          JOURNAL_MESSAGE_ROW_MAPPER,
          conversationId,
          fromInclusive,
          toExclusive,
          limit);
    }
    return jdbcTemplate.query(
        """
        select message_guid, conversation_id, sender_account_id, message_text, source_timestamp,
               from_agent, system_message, content_hash
          from agent_conversation_messages
         where conversation_id = ? and source_timestamp >= ? and source_timestamp < ?
           and (source_timestamp < ? or (source_timestamp = ? and message_guid < ?))
           and removed = false
         order by source_timestamp desc, message_guid desc
         limit ?
        """,
        remaining,
        JOURNAL_MESSAGE_ROW_MAPPER,
        conversationId,
        fromInclusive,
        toExclusive,
        beforeTimestamp,
        beforeTimestamp,
        normalizedBeforeGuid,
        limit);
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

  @Transactional(readOnly = true)
  public List<MembershipInterval> findMembershipIntervals(
      String conversationId, String accountId, Instant from, Instant to) {
    return jdbcTemplate.query(
        """
        select started_at, ended_at
          from agent_conversation_memberships
         where conversation_id = ? and account_id = ?
           and started_at < ? and (ended_at is null or ended_at > ?)
         order by started_at, membership_id
        """,
        (resultSet, rowNumber) ->
            new MembershipInterval(
                resultSet.getTimestamp("started_at").toInstant(),
                toInstant(resultSet.getTimestamp("ended_at"))),
        conversationId,
        accountId,
        to,
        from);
  }

  @Transactional
  public void replaceActiveMemberships(
      String conversationId, Set<String> accountIds, Instant observedAt) {
    Set<String> desiredAccountIds = accountIds == null ? Set.of() : Set.copyOf(accountIds);
    jdbcTemplate.update(
        "update agent_conversations set roster_verified_since = coalesce(roster_verified_since, ?) where conversation_id = ?",
        observedAt,
        conversationId);
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
               where conversation_id = ? and (
                 (content_hash = ? and occurred_at = ?)
                 or (? = 'GROUP_FACT' and kind = 'GROUP_FACT'
                     and lower(trim(artifact_text)) = lower(trim(?))
                     and status = ? and sensitivity = ?
                     and (confidence >= ? or ? < ?)
                     and status not in ('SUPERSEDED', 'DELETED')
                     and (expires_at is null or expires_at > ?)
                     and ? = true))
              """,
              (resultSet, rowNumber) -> resultSet.getString(1),
              batch.conversationId(),
              candidate.contentHash(),
              candidate.occurredAt(),
              candidate.kind().name(),
              candidate.text(),
              candidate.status().name(),
              candidate.sensitivity().name(),
              minimumConfidence,
              candidate.confidence(),
              minimumConfidence,
              batch.processedAt(),
              candidate.supersedesArtifactId() == null);
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
          verifiedWindowAudience(
              batch.conversationId(), batch.sourceMessages(), batch.processedAt());
      for (String accountId : audienceAccountIds) {
        jdbcTemplate.update(
            """
            insert into conversation_memory_audiences (artifact_id, account_id, granted_at)
            values (?, ?, ?)
            """,
            artifactId,
            accountId,
            now);
      }
      if (isProjectionEligible(candidate) && !audienceAccountIds.isEmpty()) {
        String bank = hindsight.groupBank(batch.conversationId(), Set.copyOf(audienceAccountIds));
        hindsight.save(
            bank,
            artifactId,
            artifactId,
            candidate.text(),
            candidate.occurredAt(),
            batch.processedAt());
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
            update hindsight_memory_documents
               set operation = 'DELETE', state = 'PENDING', available_at = ?,
                   attempt_count = 0, last_error_code = null, updated_at = ?
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
  public List<AuthorizedGroup> findAuthorizedGroups(
      String accountId, Instant fromInclusive, Instant toExclusive) {
    return jdbcTemplate.query(
        """
        select c.conversation_id, c.display_name, max(c.last_observed_at) as last_activity_at
          from agent_conversations c
          join agent_conversation_memberships membership
            on membership.conversation_id = c.conversation_id
         where c.is_group = true and c.memory_enabled_at is not null
           and membership.account_id = ? and membership.started_at < ?
           and (membership.ended_at is null or membership.ended_at > ?)
         group by c.conversation_id, c.display_name
         order by last_activity_at desc, c.conversation_id
        """,
        (resultSet, rowNumber) ->
            new AuthorizedGroup(
                resultSet.getString("conversation_id"),
                resultSet.getString("display_name"),
                resultSet.getTimestamp("last_activity_at").toInstant()),
        accountId,
        toExclusive,
        fromInclusive);
  }

  @Transactional(readOnly = true)
  public List<AuthorizedGroup> findCurrentlyAuthorizedGroups(String accountId, Instant now) {
    return jdbcTemplate.query(
        """
        select c.conversation_id, c.display_name, c.last_observed_at
          from agent_conversations c
          join agent_conversation_memberships membership
            on membership.conversation_id = c.conversation_id
         where c.is_group = true and c.memory_enabled_at is not null
           and membership.account_id = ? and membership.started_at <= ?
           and (membership.ended_at is null or membership.ended_at > ?)
         order by c.last_observed_at desc, c.conversation_id
        """,
        (resultSet, rowNumber) ->
            new AuthorizedGroup(
                resultSet.getString("conversation_id"),
                resultSet.getString("display_name"),
                resultSet.getTimestamp("last_observed_at").toInstant()),
        accountId,
        now,
        now);
  }

  @Transactional(readOnly = true)
  public Optional<AuthorizedGroup> findCurrentlyAuthorizedGroup(
      String accountId, String transport, String externalConversationId, Instant now) {
    return jdbcTemplate
        .query(
            """
            select c.conversation_id, c.display_name, c.last_observed_at
              from agent_conversations c
              join agent_conversation_memberships membership
                on membership.conversation_id = c.conversation_id
             where c.transport = ? and c.external_conversation_id = ?
               and c.is_group = true and c.memory_enabled_at is not null
               and membership.account_id = ? and membership.started_at <= ?
               and (membership.ended_at is null or membership.ended_at > ?)
             order by c.last_observed_at desc
            """,
            (resultSet, rowNumber) ->
                new AuthorizedGroup(
                    resultSet.getString("conversation_id"),
                    resultSet.getString("display_name"),
                    resultSet.getTimestamp("last_observed_at").toInstant()),
            transport,
            externalConversationId,
            accountId,
            now,
            now)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public List<SummaryMaterial> findAuthorizedDigests(
      String conversationId, String accountId, Instant fromInclusive, Instant toExclusive) {
    return jdbcTemplate.query(
        """
        select digest.digest_id, digest.conversation_id, digest.summary_text,
               digest.item_payload, digest.period_start, digest.period_end,
               digest.coverage_through, digest.corpus_hash
          from conversation_daily_digests digest
          join conversation_summary_audiences audience
            on audience.summary_type = 'DIGEST' and audience.summary_id = digest.digest_id
         where digest.conversation_id = ? and audience.account_id = ?
           and digest.period_end > ? and digest.period_start < ?
         order by digest.period_start, digest.digest_id
        """,
        (resultSet, rowNumber) ->
            summaryMaterial(resultSet, "DIGEST", "digest_id", "period_start", "period_end"),
        conversationId,
        accountId,
        fromInclusive,
        toExclusive);
  }

  @Transactional(readOnly = true)
  public List<SummaryMaterial> findAuthorizedSegments(
      String conversationId, String accountId, Instant fromInclusive, Instant toExclusive) {
    return jdbcTemplate.query(
        """
        select segment.segment_id, segment.conversation_id, segment.summary_text,
               segment.item_payload, segment.window_start, segment.window_end,
               segment.window_end as coverage_through, segment.corpus_hash
          from conversation_summary_segments segment
          join conversation_summary_audiences audience
            on audience.summary_type = 'SEGMENT' and audience.summary_id = segment.segment_id
         where segment.conversation_id = ? and audience.account_id = ?
           and segment.window_end >= ? and segment.window_start < ?
         order by segment.window_start, segment.segment_id
        """,
        (resultSet, rowNumber) ->
            summaryMaterial(resultSet, "SEGMENT", "segment_id", "window_start", "window_end"),
        conversationId,
        accountId,
        fromInclusive,
        toExclusive);
  }

  @Transactional(readOnly = true)
  public List<String> findAuthorizedDecisions(
      String conversationId,
      String accountId,
      Instant fromInclusive,
      Instant toExclusive,
      Instant now) {
    return jdbcTemplate.query(
        """
        select artifact.artifact_text
          from conversation_memory_artifacts artifact
          join conversation_memory_audiences audience
            on audience.artifact_id = artifact.artifact_id
         where artifact.conversation_id = ? and audience.account_id = ?
           and artifact.kind = 'GROUP_DECISION' and artifact.status = 'CONFIRMED'
           and artifact.sensitivity = 'NORMAL' and artifact.confidence >= ?
           and artifact.occurred_at >= ? and artifact.occurred_at < ?
           and (artifact.expires_at is null or artifact.expires_at > ?)
         order by artifact.occurred_at, artifact.artifact_id
        """,
        (resultSet, rowNumber) -> resultSet.getString(1),
        conversationId,
        accountId,
        minimumConfidence,
        fromInclusive,
        toExclusive,
        now);
  }

  @Transactional(readOnly = true)
  public List<ConversationRecord> findMemoryEnabledConversations() {
    return jdbcTemplate.query(
        """
        select conversation_id, transport, external_conversation_id, is_group, display_name,
               memory_enabled_at, memory_enabled_by_account_id, last_observed_at
          from agent_conversations
         where is_group = true and memory_enabled_at is not null
         order by conversation_id
        """,
        CONVERSATION_ROW_MAPPER);
  }

  @Transactional(readOnly = true)
  public List<SummaryMaterial> findSegments(
      String conversationId, Instant periodStart, Instant periodEnd) {
    return jdbcTemplate.query(
        """
        select segment_id, conversation_id, summary_text, item_payload, window_start, window_end,
               window_end as coverage_through, corpus_hash
          from conversation_summary_segments
         where conversation_id = ? and window_end >= ? and window_start < ?
         order by window_start, segment_id
        """,
        (resultSet, rowNumber) ->
            summaryMaterial(resultSet, "SEGMENT", "segment_id", "window_start", "window_end"),
        conversationId,
        periodStart,
        periodEnd);
  }

  @Transactional
  public void seedDigestWork(
      String conversationId, Instant periodStart, Instant periodEnd, Instant availableAt) {
    Integer existing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from conversation_digest_work
             where conversation_id = ? and period_start = ? and period_end = ?
            """,
            Integer.class,
            conversationId,
            periodStart,
            periodEnd);
    if (existing != null && existing > 0) {
      return;
    }
    jdbcTemplate.update(
        """
        insert into conversation_digest_work
          (conversation_id, period_start, period_end, available_at, attempt_count, updated_at)
        values (?, ?, ?, ?, 0, ?)
        """,
        conversationId,
        periodStart,
        periodEnd,
        availableAt,
        availableAt);
  }

  @Transactional
  public List<DigestWorkClaim> claimDueDigestWork(String workerId, Instant now, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    Instant claimedUntil = now.plus(Duration.ofMinutes(5));
    List<DigestKey> candidates =
        jdbcTemplate.query(
            """
            select conversation_id, period_start, period_end
              from conversation_digest_work
             where available_at <= ? and (claimed_until is null or claimed_until < ?)
             order by available_at, conversation_id, period_start
             limit ?
            """,
            (resultSet, rowNumber) ->
                new DigestKey(
                    resultSet.getString("conversation_id"),
                    resultSet.getTimestamp("period_start").toInstant(),
                    resultSet.getTimestamp("period_end").toInstant()),
            now,
            now,
            limit);
    List<DigestWorkClaim> claims = new ArrayList<>();
    for (DigestKey candidate : candidates) {
      int updated =
          jdbcTemplate.update(
              """
              update conversation_digest_work
                 set claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1,
                     updated_at = ?
               where conversation_id = ? and period_start = ? and period_end = ?
                 and available_at <= ? and (claimed_until is null or claimed_until < ?)
              """,
              workerId,
              claimedUntil,
              now,
              candidate.conversationId(),
              candidate.periodStart(),
              candidate.periodEnd(),
              now,
              now);
      if (updated == 1) {
        claims.add(
            new DigestWorkClaim(
                candidate.conversationId(),
                candidate.periodStart(),
                candidate.periodEnd(),
                workerId,
                claimedUntil));
      }
    }
    return List.copyOf(claims);
  }

  @Transactional
  public void saveDigest(DigestWorkClaim claim, DigestBatch batch) {
    if (!claim.conversationId().equals(batch.conversationId())
        || !claim.periodStart().equals(batch.periodStart())
        || !claim.periodEnd().equals(batch.periodEnd())) {
      throw new IllegalArgumentException("digest claim and batch do not match");
    }
    Integer owned =
        jdbcTemplate.queryForObject(
            """
            select count(*) from conversation_digest_work
             where conversation_id = ? and period_start = ? and period_end = ?
               and claimed_by = ? and claimed_until >= ?
            """,
            Integer.class,
            claim.conversationId(),
            claim.periodStart(),
            claim.periodEnd(),
            claim.workerId(),
            batch.processedAt());
    if (owned == null || owned != 1) {
      throw new IllegalStateException("digest work lease is not owned by this worker");
    }
    List<String> existingIds =
        jdbcTemplate.query(
            """
            select digest_id from conversation_daily_digests
             where conversation_id = ? and period_start = ? and period_end = ?
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            batch.conversationId(),
            batch.periodStart(),
            batch.periodEnd());
    String digestId = existingIds.isEmpty() ? UUID.randomUUID().toString() : existingIds.getFirst();
    if (existingIds.isEmpty()) {
      jdbcTemplate.update(
          """
          insert into conversation_daily_digests
            (digest_id, conversation_id, period_start, period_end, summary_text, item_payload,
             corpus_hash, coverage_through, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          digestId,
          batch.conversationId(),
          batch.periodStart(),
          batch.periodEnd(),
          batch.summary(),
          batch.itemPayload(),
          batch.corpusHash(),
          batch.coverageThrough(),
          batch.processedAt(),
          batch.processedAt());
    } else {
      jdbcTemplate.update(
          """
          update conversation_daily_digests
             set summary_text = ?, item_payload = ?, corpus_hash = ?, coverage_through = ?,
                 updated_at = ?
           where digest_id = ?
          """,
          batch.summary(),
          batch.itemPayload(),
          batch.corpusHash(),
          batch.coverageThrough(),
          batch.processedAt(),
          digestId);
      jdbcTemplate.update(
          "delete from conversation_summary_audiences where summary_type = 'DIGEST' and summary_id = ?",
          digestId);
    }
    for (String accountId : intersectSegmentAudiences(batch.sourceSegmentIds())) {
      jdbcTemplate.update(
          """
          insert into conversation_summary_audiences
            (summary_type, summary_id, account_id, granted_at)
          values ('DIGEST', ?, ?, ?)
          """,
          digestId,
          accountId,
          batch.processedAt());
    }
    jdbcTemplate.update(
        """
        delete from conversation_digest_work
         where conversation_id = ? and period_start = ? and period_end = ? and claimed_by = ?
        """,
        claim.conversationId(),
        claim.periodStart(),
        claim.periodEnd(),
        claim.workerId());
  }

  @Transactional
  public void failDigestWork(DigestWorkClaim claim, Instant failedAt, String errorCode) {
    jdbcTemplate.update(
        """
        update conversation_digest_work
           set available_at = ?, claimed_by = null, claimed_until = null, last_error_code = ?,
               updated_at = ?
         where conversation_id = ? and period_start = ? and period_end = ? and claimed_by = ?
           and claimed_until >= ?
        """,
        failedAt.plus(Duration.ofMinutes(10)),
        StringUtils.truncate(errorCode, 64),
        failedAt,
        claim.conversationId(),
        claim.periodStart(),
        claim.periodEnd(),
        claim.workerId(),
        failedAt);
  }

  @Transactional
  public MemoryCleanupResult cleanupMemory(
      Instant now, Instant rawMessageBefore, Instant segmentBefore) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(rawMessageBefore, "rawMessageBefore");
    Objects.requireNonNull(segmentBefore, "segmentBefore");
    int rawMessagesCleared =
        jdbcTemplate.update(
            """
            update agent_conversation_messages
               set message_text = null, updated_at = ?
             where message_text is not null and source_timestamp < ?
            """,
            now,
            rawMessageBefore);
    jdbcTemplate.update(
        """
        delete from conversation_summary_audiences
         where summary_type = 'SEGMENT' and summary_id in (
           select segment.segment_id
             from conversation_summary_segments segment
            where segment.window_end < ? and exists (
              select 1 from conversation_daily_digests digest
               where digest.conversation_id = segment.conversation_id
                 and digest.period_start <= segment.window_start
                 and digest.period_end >= segment.window_end
            )
         )
        """,
        segmentBefore);
    int segmentsDeleted =
        jdbcTemplate.update(
            """
            delete from conversation_summary_segments
             where segment_id in (
               select segment.segment_id
                 from conversation_summary_segments segment
                where segment.window_end < ? and exists (
                  select 1 from conversation_daily_digests digest
                   where digest.conversation_id = segment.conversation_id
                     and digest.period_start <= segment.window_start
                     and digest.period_end >= segment.window_end
                )
             )
            """,
            segmentBefore);
    List<String> expiredArtifactIds =
        jdbcTemplate.query(
            """
            select artifact_id from conversation_memory_artifacts
             where expires_at is not null and expires_at <= ?
               and status not in ('DELETED', 'SUPERSEDED')
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            now);
    int artifactsExpired = 0;
    if (!expiredArtifactIds.isEmpty()) {
      String placeholders =
          String.join(",", java.util.Collections.nCopies(expiredArtifactIds.size(), "?"));
      List<Object> artifactUpdateArguments = new ArrayList<>();
      artifactUpdateArguments.add(now);
      artifactUpdateArguments.addAll(expiredArtifactIds);
      artifactsExpired =
          jdbcTemplate.update(
              "update conversation_memory_artifacts set status = 'DELETED', updated_at = ? "
                  + "where artifact_id in ("
                  + placeholders
                  + ")",
              artifactUpdateArguments.toArray());
      List<Object> projectionArguments = new ArrayList<>();
      projectionArguments.add(now);
      projectionArguments.add(now);
      projectionArguments.addAll(expiredArtifactIds);
      jdbcTemplate.update(
          "update hindsight_memory_documents set operation = 'DELETE', state = 'PENDING', "
              + "available_at = ?, attempt_count = 0, "
              + "last_error_code = null, updated_at = ? where artifact_id in ("
              + placeholders
              + ")",
          projectionArguments.toArray());
    }
    return new MemoryCleanupResult(rawMessagesCleared, segmentsDeleted, artifactsExpired);
  }

  @Transactional(readOnly = true)
  public MemoryBacklog memoryBacklog(Instant now) {
    Objects.requireNonNull(now, "now");
    List<Instant> extractionRows =
        jdbcTemplate.query(
            "select min(available_at) from conversation_memory_work where available_at <= ?",
            (resultSet, rowNumber) -> toInstant(resultSet.getTimestamp(1)),
            now);
    Instant oldestExtraction = extractionRows.isEmpty() ? null : extractionRows.getFirst();
    List<Instant> projectionRows =
        jdbcTemplate.query(
            """
            select min(available_at) from hindsight_memory_documents
             where state in ('PENDING', 'FAILED') and available_at <= ?
            """,
            (resultSet, rowNumber) -> toInstant(resultSet.getTimestamp(1)),
            now);
    Instant oldestProjection = projectionRows.isEmpty() ? null : projectionRows.getFirst();
    Long failedWorkCount =
        jdbcTemplate.queryForObject(
            """
            select
              (select count(*) from conversation_memory_work where last_error_code is not null)
              + (select count(*) from hindsight_memory_documents where state in ('FAILED', 'EXHAUSTED'))
              + (select count(*) from conversation_digest_work where last_error_code is not null)
            """,
            Long.class);
    return new MemoryBacklog(
        backlogAge(oldestExtraction, now),
        backlogAge(oldestProjection, now),
        failedWorkCount == null ? 0L : failedWorkCount);
  }

  @Transactional(readOnly = true)
  public Optional<CatchupPreference> findCatchupPreference(
      String accountId, String conversationId) {
    return jdbcTemplate
        .query(
            """
            select preference.account_id, preference.conversation_id, conversation.display_name,
                   preference.proactive_enabled, preference.timezone, preference.quiet_start,
                   preference.quiet_end, preference.next_delivery_at
              from group_catchup_preferences preference
              join agent_conversations conversation
                on conversation.conversation_id = preference.conversation_id
             where preference.account_id = ? and preference.conversation_id = ?
            """,
            (resultSet, rowNumber) -> catchupPreference(resultSet),
            accountId,
            conversationId)
        .stream()
        .findFirst();
  }

  @Transactional
  public CatchupPreference saveCatchupPreference(
      String accountId,
      String conversationId,
      boolean enabled,
      String timezone,
      String quietStart,
      String quietEnd,
      Instant nextDeliveryAt,
      Instant now) {
    Integer existing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from group_catchup_preferences
             where account_id = ? and conversation_id = ?
            """,
            Integer.class,
            accountId,
            conversationId);
    if (existing != null && existing > 0) {
      jdbcTemplate.update(
          """
          update group_catchup_preferences
             set proactive_enabled = ?, timezone = ?, quiet_start = ?, quiet_end = ?,
                 next_delivery_at = ?, claimed_by = null, claimed_until = null, updated_at = ?
           where account_id = ? and conversation_id = ?
          """,
          enabled,
          timezone,
          quietStart,
          quietEnd,
          enabled ? nextDeliveryAt : null,
          now,
          accountId,
          conversationId);
    } else {
      jdbcTemplate.update(
          """
          insert into group_catchup_preferences
            (account_id, conversation_id, proactive_enabled, timezone, quiet_start, quiet_end,
             next_delivery_at, claimed_by, claimed_until, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, null, null, ?)
          """,
          accountId,
          conversationId,
          enabled,
          timezone,
          quietStart,
          quietEnd,
          enabled ? nextDeliveryAt : null,
          now);
    }
    return findCatchupPreference(accountId, conversationId).orElseThrow();
  }

  @Transactional
  public List<CatchupPreferenceClaim> claimDueCatchupPreferences(
      String workerId, Instant now, int limit) {
    Instant claimedUntil = now.plus(CATCHUP_LEASE);
    List<CatchupPreferenceKey> candidates =
        jdbcTemplate.query(
            """
            select preference.account_id, preference.conversation_id
              from group_catchup_preferences preference
              join agent_conversations conversation
                on conversation.conversation_id = preference.conversation_id
             where preference.proactive_enabled = true and preference.next_delivery_at <= ?
               and (preference.claimed_until is null or preference.claimed_until < ?)
               and conversation.memory_enabled_at is not null
               and exists (
                 select 1 from agent_conversation_memberships membership
                  where membership.conversation_id = preference.conversation_id
                    and membership.account_id = preference.account_id
                    and membership.started_at <= ?
                    and (membership.ended_at is null or membership.ended_at > ?)
               )
             order by preference.next_delivery_at, preference.account_id,
                      preference.conversation_id
             limit ?
            """,
            (resultSet, rowNumber) ->
                new CatchupPreferenceKey(
                    resultSet.getString("account_id"), resultSet.getString("conversation_id")),
            now,
            now,
            now,
            now,
            limit);
    List<CatchupPreferenceClaim> claims = new ArrayList<>();
    for (CatchupPreferenceKey key : candidates) {
      int updated =
          jdbcTemplate.update(
              """
              update group_catchup_preferences
                 set claimed_by = ?, claimed_until = ?, updated_at = ?
               where account_id = ? and conversation_id = ? and proactive_enabled = true
                 and next_delivery_at <= ?
                 and (claimed_until is null or claimed_until < ?)
              """,
              workerId,
              claimedUntil,
              now,
              key.accountId(),
              key.conversationId(),
              now,
              now);
      if (updated == 1) {
        findCatchupPreference(key.accountId(), key.conversationId())
            .ifPresent(
                preference ->
                    claims.add(
                        new CatchupPreferenceClaim(
                            preference.accountId(),
                            preference.conversationId(),
                            preference.groupDisplayName(),
                            preference.timezone(),
                            preference.quietStart(),
                            preference.quietEnd(),
                            workerId,
                            claimedUntil)));
      }
    }
    return List.copyOf(claims);
  }

  @Transactional
  public void completeCatchupPreferenceClaim(
      CatchupPreferenceClaim claim, Instant nextDeliveryAt, Instant completedAt) {
    jdbcTemplate.update(
        """
        update group_catchup_preferences
           set next_delivery_at = ?, claimed_by = null, claimed_until = null, updated_at = ?
         where account_id = ? and conversation_id = ? and claimed_by = ? and claimed_until >= ?
        """,
        nextDeliveryAt,
        completedAt,
        claim.accountId(),
        claim.conversationId(),
        claim.workerId(),
        completedAt);
  }

  @Transactional(readOnly = true)
  public Optional<Instant> latestSuccessfulCatchupCoverage(
      String accountId, String conversationId) {
    return jdbcTemplate
        .query(
            """
            select max(coverage_through) as coverage_through
              from group_catchup_deliveries
             where account_id = ? and conversation_id = ? and state = 'SENT'
            """,
            (resultSet, rowNumber) -> toInstant(resultSet.getTimestamp("coverage_through")),
            accountId,
            conversationId)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<DirectConversationRoute> findPreferredDirectConversation(
      String accountId, Instant now) {
    return jdbcTemplate
        .query(
            """
            select conversation.conversation_id, conversation.external_conversation_id
              from agent_conversations conversation
              join agent_conversation_memberships membership
                on membership.conversation_id = conversation.conversation_id
             where conversation.is_group = false and conversation.transport = 'bluebubbles'
               and membership.account_id = ? and membership.started_at <= ?
               and (membership.ended_at is null or membership.ended_at > ?)
             order by conversation.last_observed_at desc, conversation.conversation_id
            """,
            (resultSet, rowNumber) ->
                new DirectConversationRoute(
                    resultSet.getString("conversation_id"),
                    resultSet.getString("external_conversation_id")),
            accountId,
            now,
            now)
        .stream()
        .findFirst();
  }

  @Transactional
  public Optional<ProactiveDelivery> createCatchupDelivery(
      CatchupPreferenceClaim claim,
      String directConversationId,
      String digestHash,
      Instant coverageThrough,
      Instant localDayStart,
      Instant localDayEnd,
      Instant now) {
    Integer owned =
        jdbcTemplate.queryForObject(
            """
            select count(*) from group_catchup_preferences
             where account_id = ? and conversation_id = ? and proactive_enabled = true
               and claimed_by = ? and claimed_until >= ?
            """,
            Integer.class,
            claim.accountId(),
            claim.conversationId(),
            claim.workerId(),
            now);
    if (owned == null || owned != 1) {
      return Optional.empty();
    }
    Integer existing =
        jdbcTemplate.queryForObject(
            """
            select count(*) from group_catchup_deliveries
             where account_id = ? and conversation_id = ?
               and (digest_hash = ? or (created_at >= ? and created_at < ?))
            """,
            Integer.class,
            claim.accountId(),
            claim.conversationId(),
            digestHash,
            localDayStart,
            localDayEnd);
    if (existing != null && existing > 0) {
      return Optional.empty();
    }
    String deliveryId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        insert into group_catchup_deliveries
          (delivery_id, account_id, conversation_id, direct_conversation_id, digest_hash,
           coverage_through, state, created_at, sent_at)
        values (?, ?, ?, ?, ?, ?, 'PENDING', ?, null)
        """,
        deliveryId,
        claim.accountId(),
        claim.conversationId(),
        directConversationId,
        digestHash,
        coverageThrough,
        now);
    return Optional.of(
        new ProactiveDelivery(
            deliveryId,
            claim.accountId(),
            claim.conversationId(),
            directConversationId,
            digestHash,
            coverageThrough));
  }

  @Transactional
  public void completeCatchupDelivery(String deliveryId, String state, Instant completedAt) {
    jdbcTemplate.update(
        """
        update group_catchup_deliveries set state = ?, sent_at = ? where delivery_id = ?
        """,
        state,
        completedAt,
        deliveryId);
  }

  private List<String> verifiedWindowAudience(
      String conversationId, List<JournalMessage> messages, Instant processedAt) {
    if (messages.isEmpty()) return List.of();
    Instant earliest =
        messages.stream()
            .map(JournalMessage::sourceTimestamp)
            .min(Instant::compareTo)
            .orElseThrow();
    Integer verified =
        jdbcTemplate.queryForObject(
            "select count(*) from agent_conversations where conversation_id = ? and roster_verified_since <= ?",
            Integer.class,
            conversationId,
            earliest);
    if (verified == null || verified == 0) return List.of();
    Set<String> audience = new HashSet<>(activeMembershipAccountIds(conversationId, earliest));
    for (JournalMessage message : messages)
      audience.retainAll(activeMembershipAccountIds(conversationId, message.sourceTimestamp()));
    audience.retainAll(activeMembershipAccountIds(conversationId, processedAt));
    return audience.stream().sorted().toList();
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
    return GroupMemoryRetentionPolicy.isRetainable(candidate.text())
        && candidate.status() == CONFIRMED
        && candidate.sensitivity() == NORMAL
        && candidate.confidence() >= minimumConfidence;
  }

  private Duration backlogAge(@Nullable Instant availableAt, Instant now) {
    return availableAt == null || availableAt.isAfter(now)
        ? Duration.ZERO
        : Duration.between(availableAt, now);
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
    Set<String> audienceAccountIds =
        new HashSet<>(activeMembershipAccountIds(batch.conversationId(), windowEnd));
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

  private SummaryMaterial summaryMaterial(
      java.sql.ResultSet resultSet,
      String summaryType,
      String idColumn,
      String startColumn,
      String endColumn)
      throws java.sql.SQLException {
    return new SummaryMaterial(
        resultSet.getString(idColumn),
        summaryType,
        resultSet.getString("conversation_id"),
        resultSet.getString("summary_text"),
        resultSet.getString("item_payload"),
        resultSet.getTimestamp(startColumn).toInstant(),
        resultSet.getTimestamp(endColumn).toInstant(),
        resultSet.getTimestamp("coverage_through").toInstant(),
        resultSet.getString("corpus_hash"));
  }

  private CatchupPreference catchupPreference(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new CatchupPreference(
        resultSet.getString("account_id"),
        resultSet.getString("conversation_id"),
        resultSet.getString("display_name"),
        resultSet.getBoolean("proactive_enabled"),
        resultSet.getString("timezone"),
        resultSet.getString("quiet_start"),
        resultSet.getString("quiet_end"),
        toInstant(resultSet.getTimestamp("next_delivery_at")));
  }

  private Set<String> intersectSegmentAudiences(List<String> segmentIds) {
    Set<String> intersection = null;
    for (String segmentId : segmentIds) {
      Set<String> audience =
          new HashSet<>(
              jdbcTemplate.query(
                  """
                  select account_id from conversation_summary_audiences
                   where summary_type = 'SEGMENT' and summary_id = ?
                  """,
                  (resultSet, rowNumber) -> resultSet.getString(1),
                  segmentId));
      if (intersection == null) {
        intersection = audience;
      } else {
        intersection.retainAll(audience);
      }
    }
    return intersection == null ? Set.of() : Set.copyOf(intersection);
  }

  private void requireText(String value, String label) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException("missing " + label);
    }
  }

  private static Instant toInstant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static final class PostgresCompatibleJdbcTemplate {
    private final JdbcTemplate delegate;

    private PostgresCompatibleJdbcTemplate(JdbcTemplate delegate) {
      this.delegate = Objects.requireNonNull(delegate, "jdbcTemplate");
    }

    private int update(String sql, Object... args) {
      return delegate.update(sql, postgresArguments(args));
    }

    private <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      return delegate.query(sql, rowMapper, postgresArguments(args));
    }

    private <T> List<T> query(
        String sql, Duration remaining, RowMapper<T> rowMapper, Object... args) {
      Object[] converted = postgresArguments(args);
      int requestQueryTimeoutSeconds = queryTimeoutSeconds(remaining);
      ArgumentPreparedStatementSetter argumentSetter =
          new ArgumentPreparedStatementSetter(converted);
      return delegate.query(
          sql,
          statement -> {
            argumentSetter.setValues(statement);
            int appliedTimeoutSeconds = statement.getQueryTimeout();
            statement.setQueryTimeout(
                appliedTimeoutSeconds > 0
                    ? Math.min(requestQueryTimeoutSeconds, appliedTimeoutSeconds)
                    : requestQueryTimeoutSeconds);
          },
          rowMapper);
    }

    private <T> @Nullable T queryForObject(String sql, Class<T> requiredType, Object... args) {
      return delegate.queryForObject(sql, requiredType, postgresArguments(args));
    }

    private Object[] postgresArguments(Object[] args) {
      Object[] converted = args.clone();
      for (int index = 0; index < converted.length; index++) {
        if (converted[index] instanceof Instant instant) {
          converted[index] = offset(instant);
        }
      }
      return converted;
    }

    private int queryTimeoutSeconds(Duration remaining) {
      if (remaining == null || remaining.isZero() || remaining.isNegative()) {
        throw new IllegalArgumentException("query remaining time must be positive");
      }
      long seconds = remaining.getSeconds();
      if (remaining.getNano() > 0 && seconds < Long.MAX_VALUE) {
        seconds++;
      }
      return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
  }

  private record ActiveMembership(String membershipId, String accountId) {}

  private record DigestKey(String conversationId, Instant periodStart, Instant periodEnd) {}

  private record CatchupPreferenceKey(String accountId, String conversationId) {}
}
