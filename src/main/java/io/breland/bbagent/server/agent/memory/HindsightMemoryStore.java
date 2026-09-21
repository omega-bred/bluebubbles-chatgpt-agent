package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.TimeSupport.offset;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Local authorization and durable document outbox. No provider fact ID grants access. */
@Repository
public class HindsightMemoryStore {
  private final JdbcTemplate jdbc;

  public HindsightMemoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Bank(String bankId, String accountId, String conversationId) {
    public boolean group() {
      return conversationId != null;
    }
  }

  public record Document(
      String documentId,
      String bankId,
      String artifactId,
      String text,
      String hash,
      Instant occurredAt,
      String operationId,
      String operation,
      String state,
      int attempts,
      Instant createdAt) {}

  private static final RowMapper<Document> DOCUMENT =
      (rs, row) ->
          new Document(
              rs.getString("document_id"),
              rs.getString("bank_id"),
              rs.getString("artifact_id"),
              rs.getString("content_text"),
              rs.getString("content_hash"),
              rs.getTimestamp("occurred_at").toInstant(),
              rs.getString("operation_id"),
              rs.getString("operation"),
              rs.getString("state"),
              rs.getInt("attempt_count"),
              rs.getTimestamp("created_at").toInstant());
  private static final RowMapper<Bank> BANK =
      (rs, row) ->
          new Bank(
              rs.getString("bank_id"), rs.getString("account_id"), rs.getString("conversation_id"));

  @Transactional
  public String personalBank(String accountId) {
    // A merge preserves all source banks; new writes use the surviving account's bank.
    query(
        "select account_id from agent_accounts where account_id = ? for update",
        (rs, row) -> rs.getString(1),
        accountId);
    String bank = "bluechat-account-" + accountId;
    ensureBank(bank, accountId, null, Set.of());
    return bank;
  }

  @Transactional
  public String groupBank(String conversationId, Set<String> audience) {
    if (audience.isEmpty()) throw new IllegalArgumentException("Verified group audience required");
    query(
        "select conversation_id from agent_conversations where conversation_id = ? for update",
        (rs, row) -> rs.getString(1),
        conversationId);
    String bank =
        "bluechat-group-"
            + conversationId
            + "-audience-"
            + DigestUtils.sha256Hex(String.join("\n", new TreeSet<>(audience)));
    ensureBank(bank, null, conversationId, audience);
    return bank;
  }

  private void ensureBank(String bank, String account, String conversation, Set<String> audience) {
    if (!query("select * from hindsight_memory_banks where bank_id = ?", BANK, bank).isEmpty())
      return;
    update(
        "insert into hindsight_memory_banks(bank_id, account_id, conversation_id, created_at) values (?, ?, ?, ?)",
        bank,
        account,
        conversation,
        Instant.now());
    for (String id : audience)
      update("insert into hindsight_bank_audiences(bank_id, account_id) values (?, ?)", bank, id);
  }

  @Transactional(readOnly = true)
  public List<Bank> readableBanks(
      String accountId,
      String conversationId,
      Set<String> recipients,
      boolean includeGroups,
      int limit) {
    if (conversationId != null && (recipients.isEmpty() || !recipients.contains(accountId)))
      return List.of();
    List<Bank> candidates =
        conversationId == null
            ? query(
                """
            select b.* from hindsight_memory_banks b where b.account_id = ?
              or (? = true and exists (select 1 from hindsight_bank_audiences a where a.bank_id = b.bank_id and a.account_id = ?)
                  and exists (select 1 from agent_conversations c where c.conversation_id = b.conversation_id and c.memory_enabled_at is not null))
            order by case when b.account_id is not null then 0 else 1 end, b.created_at desc, b.bank_id
            """,
                BANK,
                accountId,
                includeGroups,
                accountId)
            : query(
                "select * from hindsight_memory_banks where conversation_id = ? order by created_at desc, bank_id",
                BANK,
                conversationId);
    return candidates.stream()
        .filter(b -> conversationId == null || audience(b.bankId()).containsAll(recipients))
        .limit(limit)
        .toList();
  }

  public Set<String> audience(String bank) {
    return Set.copyOf(
        query(
            "select account_id from hindsight_bank_audiences where bank_id = ?",
            (rs, row) -> rs.getString(1),
            bank));
  }

  public Optional<Bank> bank(String bank) {
    return query("select * from hindsight_memory_banks where bank_id = ?", BANK, bank).stream()
        .findFirst();
  }

  @Transactional
  public String save(String bank, String id, String artifactId, String text, Instant occurredAt) {
    return save(bank, id, artifactId, text, occurredAt, Instant.now());
  }

  @Transactional
  public String save(
      String bank, String id, String artifactId, String text, Instant occurredAt, Instant now) {
    query(
        "select bank_id from hindsight_memory_banks where bank_id = ? for update",
        (rs, row) -> rs.getString(1),
        bank);
    if (document(id).isPresent()) {
      Document existing = document(id).orElseThrow();
      if (!existing.bankId().equals(bank))
        throw new IllegalArgumentException("Document belongs to another bank");
      if (existing.operation().equals("DELETE") || existing.state().equals("EXHAUSTED"))
        throw new IllegalStateException("Memory is deleted or processing failed");
      return id;
    }
    if (artifactId == null) {
      var duplicates =
          query(
              "select * from hindsight_memory_documents where bank_id = ? and content_hash = ? and artifact_id is null and operation = 'UPSERT' and state <> 'EXHAUSTED'",
              DOCUMENT,
              bank,
              DigestUtils.sha256Hex(text));
      if (!duplicates.isEmpty()) return duplicates.getFirst().documentId();
    }
    update(
        """
        insert into hindsight_memory_documents(document_id, bank_id, artifact_id, content_text, content_hash,
          occurred_at, operation_id, operation, state, available_at, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 'UPSERT', 'PENDING', ?, ?, ?)
        """,
        id,
        bank,
        artifactId,
        text,
        DigestUtils.sha256Hex(text),
        occurredAt,
        UUID.randomUUID().toString(),
        now,
        now,
        now);
    return id;
  }

  public Optional<Document> document(String id) {
    return query("select * from hindsight_memory_documents where document_id = ?", DOCUMENT, id)
        .stream()
        .findFirst();
  }

  public boolean owns(String primaryBank, String documentId) {
    Optional<Bank> current = bank(primaryBank);
    Optional<Document> doc = document(documentId);
    if (current.isEmpty()
        || doc.isEmpty()
        || doc.get().artifactId() != null
        || doc.get().operation().equals("DELETE")) return false;
    Optional<Bank> source = bank(doc.get().bankId());
    return source.isPresent()
        && (source.get().bankId().equals(primaryBank)
            || (!current.get().group()
                && current.get().accountId().equals(source.get().accountId())));
  }

  @Transactional
  public boolean replace(String primaryBank, String id, String text) {
    if (!owns(primaryBank, id)) return false;
    return update(
            """
        update hindsight_memory_documents set content_text = ?, content_hash = ?, operation_id = ?,
          state = 'PENDING', available_at = ?, created_at = ?, updated_at = ?, attempt_count = 0, last_error_code = null
        where document_id = ? and operation = 'UPSERT' and state = 'SUCCEEDED'
          and claimed_by is null
        """,
            text,
            DigestUtils.sha256Hex(text),
            UUID.randomUUID().toString(),
            Instant.now(),
            Instant.now(),
            Instant.now(),
            id)
        == 1;
  }

  @Transactional
  public boolean delete(String primaryBank, String id) {
    if (!owns(primaryBank, id)) return false;
    // Preserve the old operation ID: the worker waits for retain before deleting, preventing
    // resurrection.
    return update(
            """
        update hindsight_memory_documents set operation = 'DELETE', state = 'PENDING',
          available_at = ?, updated_at = ?, attempt_count = 0, last_error_code = null
        where document_id = ?
        """,
            Instant.now(),
            Instant.now(),
            id)
        == 1;
  }

  @Transactional
  public List<Document> claim(String worker, Instant now, int limit, boolean groupsEnabled) {
    List<Document> candidates =
        query(
            """
        select d.* from hindsight_memory_documents d join hindsight_memory_banks b on b.bank_id = d.bank_id
        where d.state in ('PENDING', 'PROCESSING', 'FAILED') and d.available_at <= ?
          and (d.claimed_until is null or d.claimed_until < ?)
          and (? = true or b.conversation_id is null or d.operation = 'DELETE')
        order by d.available_at, d.document_id limit ?
        """,
            DOCUMENT,
            now,
            now,
            groupsEnabled,
            limit);
    List<Document> claimed = new ArrayList<>();
    for (Document doc : candidates)
      if (update(
              """
        update hindsight_memory_documents set claimed_by = ?, claimed_until = ?
        where document_id = ? and operation_id = ? and operation = ? and state in ('PENDING', 'PROCESSING', 'FAILED')
          and (claimed_until is null or claimed_until < ?)
        """,
              worker,
              now.plusSeconds(60),
              doc.documentId(),
              doc.operationId(),
              doc.operation(),
              now)
          == 1) claimed.add(doc);
    return List.copyOf(claimed);
  }

  @Transactional
  public void finish(Document doc, String worker, String state, String error, Instant now) {
    int attempts = error == null ? doc.attempts() : doc.attempts() + 1;
    if (attempts >= 8) state = "EXHAUSTED";
    Duration delay =
        error == null
            ? Duration.ofSeconds(15)
            : Duration.ofSeconds(Math.min(3600, 30L << Math.min(attempts, 6)));
    update(
        """
        update hindsight_memory_documents set state = ?, last_error_code = ?, attempt_count = ?,
          available_at = ?, updated_at = ?, claimed_by = null, claimed_until = null
        where document_id = ? and operation_id = ? and operation = ? and claimed_by = ?
        """,
        state,
        error,
        attempts,
        now.plus(delay),
        now,
        doc.documentId(),
        doc.operationId(),
        doc.operation(),
        worker);
    // A delete requested during an in-flight retain keeps its own state and only releases the
    // lease.
    update(
        "update hindsight_memory_documents set claimed_by = null, claimed_until = null where document_id = ? and claimed_by = ?",
        doc.documentId(),
        worker);
  }

  private Object[] args(Object[] args) {
    return Arrays.stream(args).map(v -> v instanceof Instant i ? offset(i) : v).toArray();
  }

  private int update(String sql, Object... args) {
    return jdbc.update(sql, args(args));
  }

  private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
    return jdbc.query(sql, mapper, args(args));
  }
}
