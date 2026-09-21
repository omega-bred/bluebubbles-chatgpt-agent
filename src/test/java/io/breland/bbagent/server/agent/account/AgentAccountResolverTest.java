package io.breland.bbagent.server.agent.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactKind;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCandidate;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryStore;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AgentAccountResolverTest {
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private AgentAccountRepository accountRepository;
  @Autowired private ConversationMemoryStore memoryStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void linkingWebsiteAccountMergesExistingWebsiteAndPhoneAccounts() {
    accountRepository.deleteAll();
    Jwt jwt = jwt("keycloak-sub", "alex.agent@example.com");
    String websiteAccountId = accountResolver.upsertWebsiteAccount(jwt).getAccountId();
    String phoneAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+1 (212) 555-0199")
            .orElseThrow()
            .account()
            .getAccountId();

    accountResolver.linkWebsiteAccount(phoneAccountId, jwt);

    assertEquals(1, accountRepository.count());
    var merged = accountRepository.findById(phoneAccountId).orElseThrow();
    assertEquals("keycloak-sub", merged.getWebsiteSubject());
    assertEquals("alex.agent@example.com", merged.getWebsiteEmail());
    assertTrue(accountRepository.findById(websiteAccountId).isEmpty());
    assertEquals(
        2,
        accountResolver.identitiesForAccount(phoneAccountId).stream()
            .map(identity -> identity.getIdentityType() + ":" + identity.getNormalizedIdentifier())
            .filter(
                value ->
                    value.equals("imessage_phone:+12125550199")
                        || value.equals("imessage_email:alex.agent@example.com"))
            .count());
  }

  @Test
  void acceptsTermsForResolvedMessageAccount() {
    accountRepository.deleteAll();
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;chat-terms",
            "msg-terms",
            null,
            "YES",
            false,
            "iMessage",
            "+1 (212) 555-0199",
            false,
            Instant.now(),
            java.util.List.of(),
            false);

    String accountId =
        accountResolver.resolveOrCreate(message).orElseThrow().account().getAccountId();

    accountResolver.acceptTerms(message);

    assertNotNull(accountRepository.findById(accountId).orElseThrow().getTermsAcceptedAt());
  }

  @Test
  void mergingAccountsPreservesTermsAcceptance() {
    accountRepository.deleteAll();
    String acceptedEmailAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "alex.agent@example.com")
            .orElseThrow()
            .account()
            .getAccountId();
    var acceptedEmailAccount = accountRepository.findById(acceptedEmailAccountId).orElseThrow();
    acceptedEmailAccount.setTermsAcceptedAt(Instant.parse("2026-05-18T00:00:00Z"));
    acceptedEmailAccount.setUpdatedAt(Instant.now());
    accountRepository.save(acceptedEmailAccount);
    String phoneAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+1 (212) 555-0199")
            .orElseThrow()
            .account()
            .getAccountId();

    accountResolver.linkWebsiteAccount(
        phoneAccountId, jwt("keycloak-sub", "alex.agent@example.com"));

    assertNotNull(accountRepository.findById(phoneAccountId).orElseThrow().getTermsAcceptedAt());
    assertTrue(accountRepository.findById(acceptedEmailAccountId).isEmpty());
  }

  @Test
  void mergingAccountsPreservesConversationMemoryAudienceAndProjection() {
    accountRepository.deleteAll();
    Instant now = Instant.parse("2026-08-08T17:03:00Z");
    Jwt jwt = jwt("memory-subject", "memory-source@example.com");
    String sourceAccountId = accountResolver.upsertWebsiteAccount(jwt).getAccountId();
    String targetAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+1 (415) 555-0123")
            .orElseThrow()
            .account()
            .getAccountId();
    String conversationId =
        memoryStore.upsertConversation(
            "bluebubbles", "iMessage;+;merge-memory", true, "Memory merge", now);
    memoryStore.recordMembership(conversationId, sourceAccountId, now.minusSeconds(10));
    memoryStore.recordMembership(conversationId, targetAccountId, now.minusSeconds(10));
    JournalMessage message =
        new JournalMessage(
            "merge-message",
            conversationId,
            sourceAccountId,
            "The group chose Monday",
            now,
            false,
            false,
            "merge-message-hash");
    memoryStore.recordMessage(message);
    memoryStore.scheduleExtraction(conversationId, now);
    jdbcTemplate.update(
        "update agent_conversations set roster_verified_since = ? where conversation_id = ?",
        now,
        conversationId);
    String artifactId =
        memoryStore
            .saveExtraction(
                memoryStore.claimDueExtractionWork("merge-worker", now, 1).getFirst(),
                new ExtractionBatch(
                    conversationId,
                    List.of(message),
                    List.of(
                        new ExtractionCandidate(
                            ArtifactKind.GROUP_DECISION,
                            "The group decided on Monday.",
                            ArtifactStatus.CONFIRMED,
                            ArtifactSensitivity.NORMAL,
                            0.95,
                            now,
                            null,
                            List.of(message.messageGuid()),
                            null,
                            "merge-artifact-hash")),
                    "The group chose Monday.",
                    "[]",
                    "merge-corpus-hash",
                    now))
            .getFirst();
    var hindsight = new io.breland.bbagent.server.agent.memory.HindsightMemoryStore(jdbcTemplate);
    String sourceBank = hindsight.personalBank(sourceAccountId);
    hindsight.save(sourceBank, "merge-document", null, "Likes tea", now);
    String groupBank =
        hindsight.groupBank(conversationId, java.util.Set.of(sourceAccountId, targetAccountId));
    memoryStore.saveCatchupPreference(
        targetAccountId,
        conversationId,
        false,
        "America/Los_Angeles",
        "21:00",
        "07:00",
        now.plusSeconds(3600),
        now);
    memoryStore.saveCatchupPreference(
        sourceAccountId, conversationId, true, "UTC", "22:00", "08:00", now.plusSeconds(7200), now);
    jdbcTemplate.update(
        """
        insert into group_catchup_deliveries
          (delivery_id, account_id, conversation_id, direct_conversation_id, digest_hash,
           coverage_through, state, created_at)
        values (?, ?, ?, ?, ?, ?, 'SENT', ?)
        """,
        UUID.randomUUID().toString(),
        targetAccountId,
        conversationId,
        conversationId,
        "shared-digest",
        now,
        now);
    jdbcTemplate.update(
        """
        insert into group_catchup_deliveries
          (delivery_id, account_id, conversation_id, direct_conversation_id, digest_hash,
           coverage_through, state, created_at)
        values (?, ?, ?, ?, ?, ?, 'SENT', ?), (?, ?, ?, ?, ?, ?, 'SENT', ?)
        """,
        UUID.randomUUID().toString(),
        sourceAccountId,
        conversationId,
        conversationId,
        "shared-digest",
        now,
        now,
        UUID.randomUUID().toString(),
        sourceAccountId,
        conversationId,
        conversationId,
        "source-only-digest",
        now,
        now);

    accountResolver.linkWebsiteAccount(targetAccountId, jwt);

    assertTrue(accountRepository.findById(sourceAccountId).isEmpty());
    assertEquals(
        1,
        rowCount(
            "agent_conversation_memberships", "conversation_id", conversationId, targetAccountId));
    assertEquals(
        1, rowCount("conversation_memory_audiences", "artifact_id", artifactId, targetAccountId));
    assertEquals(1, rowCount("hindsight_bank_audiences", "bank_id", groupBank, targetAccountId));
    assertEquals(targetAccountId, hindsight.bank(sourceBank).orElseThrow().accountId());
    assertTrue(hindsight.owns(hindsight.personalBank(targetAccountId), "merge-document"));
    var mergedPreference =
        memoryStore.findCatchupPreference(targetAccountId, conversationId).orElseThrow();
    assertTrue(mergedPreference.enabled());
    assertEquals("America/Los_Angeles", mergedPreference.timezone());
    assertEquals(now.plusSeconds(7200), mergedPreference.nextDeliveryAt());
    assertEquals(
        2,
        rowCount("group_catchup_deliveries", "conversation_id", conversationId, targetAccountId));
  }

  private int rowCount(String table, String idColumn, String id, String accountId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + idColumn + " = ? and account_id = ?",
            Integer.class,
            id,
            accountId);
    return count == null ? 0 : count;
  }

  private Jwt jwt(String subject, String email) {
    return new Jwt(
        "token", null, null, Map.of("alg", "none"), Map.of("sub", subject, "email", email));
  }
}
