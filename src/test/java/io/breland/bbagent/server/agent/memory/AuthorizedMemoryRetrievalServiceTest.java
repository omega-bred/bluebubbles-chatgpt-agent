package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactKind;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectedArtifact;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorizedMemoryRetrievalServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final Mem0Client mem0Client = mock(Mem0Client.class);
  private final ToolContext context = mock(ToolContext.class);
  private final AuthorizedMemoryRetrievalService service =
      new AuthorizedMemoryRetrievalService(store, mem0Client, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void returnsAuthorizedGroupArtifactsAsReadOnlyWithoutAMemoryId() {
    when(context.canonicalAccountId()).thenReturn(Optional.of("account-1"));
    when(mem0Client.searchMemories("account:account-1", "Saturday"))
        .thenReturn(List.of(new Mem0Client.StoredMemory("memory-1", "projected text")));
    when(store.findProjectedArtifact("memory-1", "account-1"))
        .thenReturn(Optional.of(projectedArtifact()));
    when(store.isInArtifactAudience("artifact-1", "account-1")).thenReturn(true);

    var results = service.search(context, "Saturday");

    assertThat(results)
        .singleElement()
        .satisfies(
            memory -> {
              assertThat(memory.artifactId()).isEqualTo("artifact-1");
              assertThat(memory.memory()).isEqualTo("Meet Saturday at 6 PM.");
              assertThat(memory.sourceGroup()).isEqualTo("Trip planning");
              assertThat(memory.readOnly()).isTrue();
              assertThat(memory.memoryId()).isNull();
            });
  }

  @Test
  void laterJoinerCannotHydrateAnEarlierProjectedHit() {
    when(context.canonicalAccountId()).thenReturn(Optional.of("account-later"));
    when(mem0Client.searchMemories("account:account-later", "Saturday"))
        .thenReturn(List.of(new Mem0Client.StoredMemory("memory-1", "projected text")));
    when(store.findProjectedArtifact("memory-1", "account-later"))
        .thenReturn(Optional.of(projectedArtifact()));
    when(store.isInArtifactAudience("artifact-1", "account-later")).thenReturn(false);

    assertThat(service.search(context, "Saturday")).isEmpty();
  }

  @Test
  void ordinaryPersonalMemoryKeepsItsMutableMemoryId() {
    when(context.canonicalAccountId()).thenReturn(Optional.of("account-1"));
    when(mem0Client.searchMemories("account:account-1", "tea"))
        .thenReturn(List.of(new Mem0Client.StoredMemory("personal-1", "Likes tea")));
    when(store.findProjectedArtifact("personal-1", "account-1")).thenReturn(Optional.empty());
    when(store.ownsCanonicalMemory("account:account-1", "personal-1")).thenReturn(true);

    var results = service.search(context, "tea");

    assertThat(results)
        .singleElement()
        .satisfies(
            memory -> {
              assertThat(memory.memoryId()).isEqualTo("personal-1");
              assertThat(memory.readOnly()).isFalse();
            });
  }

  @Test
  void globalFeatureGuardPreventsMemoryReads() {
    AuthorizedMemoryRetrievalService disabled =
        new AuthorizedMemoryRetrievalService(
            store, mem0Client, Clock.fixed(NOW, ZoneOffset.UTC), 0.85, false);

    assertThat(disabled.search(context, "Saturday")).isEmpty();

    verify(mem0Client, never()).searchMemories(anyString(), anyString());
  }

  private static ProjectedArtifact projectedArtifact() {
    return new ProjectedArtifact(
        "artifact-1",
        "memory-1",
        "conversation-1",
        "Trip planning",
        ArtifactKind.GROUP_DECISION,
        "Meet Saturday at 6 PM.",
        ArtifactStatus.CONFIRMED,
        ArtifactSensitivity.NORMAL,
        0.96,
        NOW.minusSeconds(60),
        null);
  }
}
