package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemoryScopeResolverTest {
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final HindsightMemoryStore memories = mock(HindsightMemoryStore.class);
  private final ConversationMembershipService memberships =
      mock(ConversationMembershipService.class);
  private final MemoryScopeResolver resolver =
      new MemoryScopeResolver(
          store,
          memories,
          memberships,
          true,
          mock(AuthorizedMemoryRetrievalService.class),
          null,
          null);

  @Test
  void linkedIdentitiesUseOneAccountBank() {
    when(memories.personalBank("account-1")).thenReturn("bank-1");
    assertThat(resolver.primaryScope(context(false, "+15555550123", "account-1")))
        .contains("bank-1");
    assertThat(resolver.primaryScope(context(false, "person@example.com", "account-1")))
        .contains("bank-1");
  }

  @Test
  void groupUsesOnlyVerifiedExactAudience() {
    var context = context(true, "alice", "account-1");
    when(store.findEnabledConversationId("bluebubbles", "chat"))
        .thenReturn(Optional.of("conversation"));
    when(memberships.refreshGroupMembership("conversation"))
        .thenReturn(Set.of("account-1", "account-2"));
    when(memories.groupBank("conversation", Set.of("account-1", "account-2")))
        .thenReturn("group-bank");
    assertThat(resolver.primaryScope(context)).contains("group-bank");
    verify(memories, never()).personalBank(anyString());
  }

  @Test
  void unknownRosterAndUnresolvedAccountFailClosed() {
    var context = context(true, "alice", "account-1");
    when(store.findEnabledConversationId("bluebubbles", "chat"))
        .thenReturn(Optional.of("conversation"));
    when(memberships.refreshGroupMembership("conversation"))
        .thenThrow(new ConversationMembershipService.MembershipRefreshException("unavailable"));
    assertThat(resolver.primaryScope(context)).isEmpty();
    assertThat(resolver.primaryScope(context(false, "alice", null))).isEmpty();
    verifyNoInteractions(memories);
  }

  static ToolContext context(boolean group, String sender, String account) {
    IncomingMessage message =
        new IncomingMessage(
            "bluebubbles",
            "chat",
            "message",
            null,
            "Saturday plans",
            false,
            "iMessage",
            sender,
            group,
            Instant.now(),
            List.of(),
            false);
    AgentProfile profile = mock(AgentProfile.class);
    when(profile.resolveCanonicalAccountId(message)).thenReturn(Optional.ofNullable(account));
    return ToolContextFixture.with(message).profile(profile).build();
  }
}
