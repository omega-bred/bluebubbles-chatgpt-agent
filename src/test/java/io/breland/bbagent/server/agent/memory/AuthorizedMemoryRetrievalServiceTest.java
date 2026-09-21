package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.memory.HindsightMemoryStore.*;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthorizedMemoryRetrievalServiceTest {
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final HindsightMemoryStore memories = mock(HindsightMemoryStore.class);
  private final ConversationMembershipService memberships =
      mock(ConversationMembershipService.class);
  private final HindsightClient client = mock(HindsightClient.class);

  private AuthorizedMemoryRetrievalService service(boolean group) {
    when(client.isConfigured()).thenReturn(true);
    return new AuthorizedMemoryRetrievalService(
        store, memories, memberships, client, .85, group, 12);
  }

  private Document document(String state, String operation) {
    return new Document(
        "doc",
        "personal",
        null,
        "Likes tea",
        "hash",
        Instant.now(),
        "op",
        operation,
        state,
        0,
        Instant.now());
  }

  @Test
  void personalMemoryWorksWithGroupFeatureDisabled() {
    var service = service(false);
    var context = MemoryScopeResolverTest.context(false, "alice", "account");
    when(memories.readableBanks("account", null, Set.of(), false, 12))
        .thenReturn(List.of(new Bank("personal", "account", null)));
    when(client.recall("personal", "tea"))
        .thenReturn(List.of(new HindsightClient.RecalledMemory("doc", "extracted tea", "hash")));
    when(memories.document("doc")).thenReturn(Optional.of(document("SUCCEEDED", "UPSERT")));
    assertThat(service.search(context, "tea"))
        .singleElement()
        .satisfies(m -> assertThat(m.memory()).isEqualTo("Likes tea"));
    verifyNoInteractions(memberships);
  }

  @Test
  void deletedPendingAndStaleFactsAreNeverReturned() {
    var service = service(false);
    var context = MemoryScopeResolverTest.context(false, "alice", "account");
    when(memories.readableBanks("account", null, Set.of(), false, 12))
        .thenReturn(List.of(new Bank("personal", "account", null)));
    when(client.recall("personal", "tea"))
        .thenReturn(List.of(new HindsightClient.RecalledMemory("doc", "old fact", "hash")));
    when(memories.document("doc"))
        .thenReturn(
            Optional.of(document("SUCCEEDED", "DELETE")),
            Optional.of(document("PENDING", "UPSERT")));
    assertThat(service.search(context, "tea")).isEmpty();
    assertThat(service.search(context, "tea")).isEmpty();
    when(memories.document("doc")).thenReturn(Optional.of(document("SUCCEEDED", "UPSERT")));
    when(client.recall("personal", "tea"))
        .thenReturn(List.of(new HindsightClient.RecalledMemory("doc", "old fact", "stale")));
    assertThat(service.search(context, "tea")).isEmpty();
  }

  @Test
  void groupSearchCannotFallBackToPersonalOrOtherGroups() {
    var service = service(true);
    var context = MemoryScopeResolverTest.context(true, "alice", "account");
    when(store.findEnabledConversationId("bluebubbles", "chat")).thenReturn(Optional.of("group"));
    when(memberships.refreshGroupMembership("group")).thenReturn(Set.of("account", "new-member"));
    when(memories.readableBanks("account", "group", Set.of("account", "new-member"), true, 12))
        .thenReturn(List.of(new Bank("audience", null, "group")));
    service.search(context, "tea");
    verify(client).recall("audience", "tea");
    verify(client, never()).recall("personal", "tea");
    verify(memories).readableBanks("account", "group", Set.of("account", "new-member"), true, 12);
  }

  @Test
  void unavailableRosterOrDisabledProviderMakesNoRecall() {
    var service = service(true);
    var context = MemoryScopeResolverTest.context(true, "alice", "account");
    when(store.findEnabledConversationId("bluebubbles", "chat")).thenReturn(Optional.of("group"));
    when(memberships.refreshGroupMembership("group"))
        .thenThrow(new ConversationMembershipService.MembershipRefreshException("offline"));
    assertThat(service.search(context, "tea")).isEmpty();
    when(client.isConfigured()).thenReturn(false);
    assertThat(service.search(MemoryScopeResolverTest.context(false, "alice", "account"), "tea"))
        .isEmpty();
    verify(client, never()).recall(anyString(), anyString());
  }
}
