package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class HindsightMemoryStoreTest {
  @Autowired HindsightMemoryStore memories;
  @Autowired ConversationMemoryStore conversations;
  private final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

  @Test
  void exactAudienceBanksPreventJoinerAndCrossGroupLeaks() {
    String group =
        conversations.upsertConversation("bluebubbles", "audience-test", true, "Group", now);
    conversations.enableMemory(group, "alice", now);
    String old = memories.groupBank(group, Set.of("alice", "bob"));
    String current = memories.groupBank(group, Set.of("alice", "bob", "carol"));
    String other = memories.groupBank("other", Set.of("alice", "carol"));
    memories.personalBank("alice");
    assertThat(memories.groupBank(group, Set.of("bob", "alice"))).isEqualTo(old);
    assertThat(memories.readableBanks("alice", group, Set.of("alice", "bob", "carol"), true, 12))
        .extracting(HindsightMemoryStore.Bank::bankId)
        .containsExactly(current)
        .doesNotContain(old, other, "bluechat-account-alice");
    assertThat(memories.readableBanks("alice", group, Set.of(), true, 12)).isEmpty();
    assertThat(memories.readableBanks("carol", null, Set.of(), true, 12))
        .extracting(HindsightMemoryStore.Bank::bankId)
        .doesNotContain(old);
    assertThat(memories.readableBanks("alice", null, Set.of(), true, 12))
        .extracting(HindsightMemoryStore.Bank::bankId)
        .contains(old, current);
  }

  @Test
  void outboxIsIdempotentAndDeletionInvalidatesImmediatelyEvenDuringRetain() {
    String bank = memories.personalBank("alice");
    memories.save(bank, "doc", null, "Likes tea", now, now);
    String operation = memories.document("doc").orElseThrow().operationId();
    memories.save(bank, "doc", null, "Likes tea", now, now);
    assertThat(memories.document("doc").orElseThrow().operationId()).isEqualTo(operation);
    var first = memories.claim("worker", now, 1, true).getFirst();
    assertThat(memories.claim("worker-2", now, 1, true)).isEmpty();
    assertThat(memories.replace(bank, "doc", "Likes coffee")).isFalse();
    assertThat(memories.delete(bank, "doc")).isTrue();
    assertThat(memories.owns(bank, "doc")).isFalse();
    memories.finish(first, "worker", "SUCCEEDED", null, now);
    var deletion = memories.claim("worker-2", Instant.now().plusSeconds(1), 1, true).getFirst();
    assertThat(deletion.operation()).isEqualTo("DELETE");
    assertThat(deletion.operationId()).isEqualTo(operation);
    assertThat(deletion.state()).isEqualTo("PENDING");
  }

  @Test
  void replacementKeepsDocumentChangesOperationAndHidesOldVersion() {
    String bank = memories.personalBank("alice");
    memories.save(bank, "replace-doc", null, "Likes tea", now, now);
    var doc = memories.claim("worker", now, 1, true).getFirst();
    memories.finish(doc, "worker", "SUCCEEDED", null, now);
    assertThat(memories.replace(bank, "replace-doc", "Likes coffee")).isTrue();
    var changed = memories.document("replace-doc").orElseThrow();
    assertThat(changed.documentId()).isEqualTo(doc.documentId());
    assertThat(changed.operationId()).isNotEqualTo(doc.operationId());
    assertThat(changed.hash()).isNotEqualTo(doc.hash());
    assertThat(changed.state()).isEqualTo("PENDING");
  }

  @Test
  void retriesAreBoundedAndDisabledGroupsLeavePersonalWorkAvailable() {
    String bank = memories.personalBank("alice");
    memories.save(bank, "retry-doc", null, "Likes tea", now, now);
    memories.save(
        memories.groupBank("group", Set.of("alice")),
        "group-doc",
        "artifact",
        "A commitment",
        now,
        now);
    for (int i = 0; i < 8; i++) {
      var at = now.plusSeconds(i * 4000L);
      var claims = memories.claim("worker", at, 10, false);
      assertThat(claims)
          .singleElement()
          .satisfies(d -> assertThat(d.documentId()).isEqualTo("retry-doc"));
      memories.finish(claims.getFirst(), "worker", "FAILED", "network", at);
    }
    assertThat(memories.document("retry-doc").orElseThrow().state()).isEqualTo("EXHAUSTED");
    assertThat(memories.claim("worker", now.plusSeconds(40000), 10, false)).isEmpty();
  }
}
