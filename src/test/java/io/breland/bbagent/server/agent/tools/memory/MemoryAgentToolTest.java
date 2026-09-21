package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.*;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedMemory;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemoryAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final HindsightClient client = mock(HindsightClient.class);
  private final MemoryScopeResolver scope = mock(MemoryScopeResolver.class);
  private final IncomingMessage message =
      new IncomingMessage(
          "bluebubbles",
          "chat",
          "message",
          null,
          "tea",
          false,
          "iMessage",
          "alice",
          false,
          Instant.now(),
          List.of(),
          false);
  private final io.breland.bbagent.server.agent.tools.ToolContext context =
      ToolContextFixture.with(message).build();

  @Test
  void saveQueuesSourceAndDoesNotClaimImmediateRetention() throws Exception {
    when(client.isConfigured()).thenReturn(true);
    when(scope.primaryScope(context)).thenReturn(Optional.of("bank"));
    when(scope.save("bank", "Likes tea", message)).thenReturn("document");
    String result =
        new MemorySaveAgentTool(client, scope)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory\":\"Likes tea\"}"));
    assertThat(result).contains("queued", "document");
    verify(client, never())
        .retain(anyString(), anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void retrievalHasNoLegacyOrUnauthorizedFallback() throws Exception {
    when(client.isConfigured()).thenReturn(true);
    var retrieval = mock(AuthorizedMemoryRetrievalService.class);
    when(scope.authorizedRetrievalService()).thenReturn(Optional.of(retrieval));
    when(retrieval.search(context, "tea")).thenReturn(List.of());
    assertThat(
            new MemoryGetAgentTool(client, scope)
                .getTool()
                .handler()
                .apply(context, mapper.readTree("{\"query\":\"tea\"}")))
        .isEqualTo("not found");
    verify(client, never()).recall(anyString(), anyString());
  }

  @Test
  void groupResultCarriesProvenanceAndReadOnlyStatus() throws Exception {
    when(client.isConfigured()).thenReturn(true);
    var retrieval = mock(AuthorizedMemoryRetrievalService.class);
    when(scope.authorizedRetrievalService()).thenReturn(Optional.of(retrieval));
    when(retrieval.search(context, "tea"))
        .thenReturn(
            List.of(
                new AuthorizedMemory(
                    "artifact", "Meeting Saturday", "Planning", Instant.now(), true, null)));
    var result =
        mapper.readTree(
            new MemoryGetAgentTool(client, scope)
                .getTool()
                .handler()
                .apply(context, mapper.readTree("{\"query\":\"tea\"}")));
    assertThat(result.at("/memories/0/read_only").asBoolean()).isTrue();
    assertThat(result.at("/memories/0/source_group").asText()).isEqualTo("Planning");
    assertThat(result.at("/memories/0/memory_id").isMissingNode()).isTrue();
  }

  @Test
  void foreignAndCollectiveMemoryCannotBeMutated() throws Exception {
    when(client.isConfigured()).thenReturn(true);
    when(scope.primaryScope(context)).thenReturn(Optional.of("bank"));
    var args = mapper.readTree("{\"memory_id\":\"foreign\",\"memory\":\"new text\"}");
    assertThat(new MemoryUpdateAgentTool(client, scope).getTool().handler().apply(context, args))
        .contains("does not belong");
    assertThat(
            new MemoryDeleteAgentTool(client, scope)
                .getTool()
                .handler()
                .apply(context, mapper.readTree("{\"memory_id\":\"foreign\"}")))
        .contains("does not belong");
    when(scope.isReadOnlyMemory("bank", "foreign")).thenReturn(true);
    assertThat(
            new MemoryDeleteAgentTool(client, scope)
                .getTool()
                .handler()
                .apply(context, mapper.readTree("{\"memory_id\":\"foreign\"}")))
        .contains("read-only");
    verify(scope, never()).delete(anyString(), anyString());
    verify(scope, never()).update(anyString(), anyString(), anyString());
  }

  @Test
  void disabledMemoryDoesNotRetrieve() throws Exception {
    assertThat(
            new MemoryGetAgentTool(client, scope)
                .getTool()
                .handler()
                .apply(context, mapper.readTree("{}")))
        .isEqualTo("not configured");
    verifyNoInteractions(scope);
  }
}
