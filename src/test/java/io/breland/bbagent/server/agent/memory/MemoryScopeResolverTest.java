package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryScopeResolverTest {

  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);

  @Test
  void mergedPhoneAndEmailIdentitiesUseTheSameCanonicalAccountScope() {
    MemoryScopeResolver resolver = new MemoryScopeResolver(store, true);

    assertThat(resolver.primaryScope(context(direct("+15555550123"), "account-1")))
        .contains("account:account-1");
    assertThat(resolver.primaryScope(context(direct("person@example.com"), "account-1")))
        .contains("account:account-1");
  }

  @Test
  void enabledGroupUsesTheAuthoritativeConversationScope() {
    IncomingMessage message = group("iMessage;+;group-1");
    when(store.findEnabledConversationId(message.transportOrDefault(), message.chatGuid()))
        .thenReturn(Optional.of("conversation-1"));
    MemoryScopeResolver resolver = new MemoryScopeResolver(store, true);

    assertThat(resolver.primaryScope(context(message, "account-1")))
        .contains("conversation:conversation-1");
  }

  @Test
  void disabledGroupAndUnresolvedDirectMessageHaveNoWritableScope() {
    IncomingMessage group = group("iMessage;+;group-1");
    when(store.findEnabledConversationId(group.transportOrDefault(), group.chatGuid()))
        .thenReturn(Optional.empty());
    MemoryScopeResolver resolver = new MemoryScopeResolver(store, true);

    assertThat(resolver.primaryScope(context(group, "account-1"))).isEmpty();
    assertThat(resolver.primaryScope(context(direct("+15555550123"), null))).isEmpty();
  }

  @Test
  void legacyReadScopeIsLimitedToTheCurrentRawIdentity() {
    MemoryScopeResolver resolver = new MemoryScopeResolver(store, true);

    assertThat(resolver.legacyReadScope(context(direct("+15555550123"), "account-1")))
        .contains("+15555550123");
    assertThat(resolver.legacyReadScope(context(group("iMessage;+;group-1"), "account-1")))
        .contains("iMessage;+;group-1");
    assertThat(new MemoryScopeResolver(store, false).legacyReadScope(context(direct("+1"), "a")))
        .isEmpty();
  }

  @Test
  void ownershipUsesSha256ContentHash() {
    MemoryScopeResolver resolver = new MemoryScopeResolver(store, true);

    resolver.recordOwnership("account:1", "memory-1", "hello");
    resolver.updateOwnership("account:1", "memory-1", "hello");

    String expectedHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    verify(store)
        .recordCanonicalMemory(
            eq("account:1"), eq("memory-1"), eq(expectedHash), any(Instant.class));
    verify(store)
        .updateCanonicalMemory(
            eq("account:1"), eq("memory-1"), eq(expectedHash), any(Instant.class));
  }

  private static ToolContext context(IncomingMessage message, String canonicalAccountId) {
    AgentProfile profile = mock(AgentProfile.class);
    when(profile.resolveCanonicalAccountId(message))
        .thenReturn(Optional.ofNullable(canonicalAccountId));
    return ToolContextFixture.with(message).profile(profile).build();
  }

  private static IncomingMessage direct(String sender) {
    return message("iMessage;-;" + sender, sender, false);
  }

  private static IncomingMessage group(String chatGuid) {
    return message(chatGuid, "+15555550123", true);
  }

  private static IncomingMessage message(String chatGuid, String sender, boolean group) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        "message-1",
        null,
        "hello",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        sender,
        group,
        Instant.parse("2026-08-08T00:00:00Z"),
        List.of(),
        false);
  }
}
