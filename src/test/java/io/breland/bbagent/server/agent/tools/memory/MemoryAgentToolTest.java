package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.AuthorizedMemoryRetrievalService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedMemory;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Mem0Client mem0Client = mock(Mem0Client.class);
  private final MemoryScopeResolver scopeResolver = mock(MemoryScopeResolver.class);
  private ToolContext context;

  @BeforeEach
  void setUp() {
    when(mem0Client.isConfigured()).thenReturn(true);
    when(mem0Client.getObjectMapper()).thenReturn(mapper);
    context = context(directMessage());
  }

  @Test
  void savesToCanonicalScopeAndRecordsOwnership() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(mem0Client.addMemory(eq("account:account-1"), eq("likes tea"), anyMap()))
        .thenReturn(new Mem0Client.MemoryMutationResult(true, "memory-1"));

    String result =
        new MemorySaveAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory\":\"likes tea\"}"));

    assertThat(result).isEqualTo("saved");
    verify(scopeResolver).recordOwnership("account:account-1", "memory-1", "likes tea");
    verify(mem0Client).addMemory(eq("account:account-1"), eq("likes tea"), anyMap());
  }

  @Test
  void refusesToWriteWhenGroupMemoryIsDisabled() throws Exception {
    context = context(groupMessage());
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.empty());

    String result =
        new MemorySaveAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory\":\"decision\"}"));

    assertThat(result).isEqualTo("group memory is not enabled");
    verify(mem0Client, never())
        .addMemory(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyMap());
  }

  @Test
  void readsLegacyScopeOnlyAfterCanonicalSearchIsEmptyAndMarksTheResult() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(scopeResolver.legacyReadScope(context)).thenReturn(Optional.of("+15555550123"));
    when(mem0Client.searchMemories("account:account-1", "tea")).thenReturn(List.of());
    when(mem0Client.searchMemories("+15555550123", "tea"))
        .thenReturn(List.of(new Mem0Client.StoredMemory("legacy-1", "likes tea")));

    String result =
        new MemoryGetAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"query\":\"tea\"}"));

    JsonNode memory = mapper.readTree(result).path("memories").get(0);
    assertThat(memory.path("memory_id").asText()).isEqualTo("legacy-1");
    assertThat(memory.path("legacy").asBoolean()).isTrue();
    verify(mem0Client).searchMemories("account:account-1", "tea");
    verify(mem0Client).searchMemories("+15555550123", "tea");
  }

  @Test
  void canonicalReadDoesNotConsultLegacyScope() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(mem0Client.searchMemories("account:account-1", "tea"))
        .thenReturn(List.of(new Mem0Client.StoredMemory("memory-1", "likes tea")));

    String result =
        new MemoryGetAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"query\":\"tea\"}"));

    JsonNode memory = mapper.readTree(result).path("memories").get(0);
    assertThat(memory.path("legacy").asBoolean()).isFalse();
    verify(scopeResolver, never()).legacyReadScope(context);
  }

  @Test
  void updateAndDeleteRejectMemoryNotOwnedByCurrentCanonicalScope() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(scopeResolver.ownsMemory("account:account-1", "memory-2")).thenReturn(false);

    String updateResult =
        new MemoryUpdateAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(
                context, mapper.readTree("{\"memory_id\":\"memory-2\",\"memory\":\"new text\"}"));
    String deleteResult =
        new MemoryDeleteAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory_id\":\"memory-2\"}"));

    assertThat(updateResult).isEqualTo("memory does not belong to the current scope");
    assertThat(deleteResult).isEqualTo("memory does not belong to the current scope");
    verify(mem0Client, never())
        .updateMemory(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(mem0Client, never()).deleteMemory(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void successfulUpdateAndDeleteMaintainOwnershipRecords() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(scopeResolver.ownsMemory("account:account-1", "memory-1")).thenReturn(true);
    when(mem0Client.updateMemory("memory-1", "new text", null)).thenReturn(true);
    when(mem0Client.deleteMemory("memory-1")).thenReturn(true);

    String updateResult =
        new MemoryUpdateAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(
                context, mapper.readTree("{\"memory_id\":\"memory-1\",\"memory\":\"new text\"}"));
    String deleteResult =
        new MemoryDeleteAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory_id\":\"memory-1\"}"));

    assertThat(updateResult).isEqualTo("updated");
    assertThat(deleteResult).isEqualTo("deleted");
    verify(scopeResolver).updateOwnership("account:account-1", "memory-1", "new text");
    verify(scopeResolver).removeOwnership("account:account-1", "memory-1");
  }

  @Test
  void formatsAuthorizedGroupDecisionAsReadOnlyWithoutMem0Id() throws Exception {
    AuthorizedMemoryRetrievalService retrievalService =
        mock(AuthorizedMemoryRetrievalService.class);
    when(scopeResolver.authorizedRetrievalService()).thenReturn(Optional.of(retrievalService));
    when(retrievalService.search(context, "Saturday"))
        .thenReturn(
            List.of(
                new AuthorizedMemory(
                    "artifact-1",
                    "Meet Saturday at 6 PM.",
                    "Trip planning",
                    Instant.parse("2026-08-08T17:03:00Z"),
                    true,
                    null)));

    String result =
        new MemoryGetAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"query\":\"Saturday\"}"));

    JsonNode memory = mapper.readTree(result).path("memories").get(0);
    assertThat(memory.path("artifact_id").asText()).isEqualTo("artifact-1");
    assertThat(memory.path("source_group").asText()).isEqualTo("Trip planning");
    assertThat(memory.path("read_only").asBoolean()).isTrue();
    assertThat(memory.has("memory_id")).isFalse();
  }

  @Test
  void updateAndDeleteExplicitlyRejectReadOnlyGroupArtifacts() throws Exception {
    when(scopeResolver.primaryScope(context)).thenReturn(Optional.of("account:account-1"));
    when(scopeResolver.isReadOnlyMemory("account:account-1", "artifact-1")).thenReturn(true);

    String updateResult =
        new MemoryUpdateAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(
                context, mapper.readTree("{\"memory_id\":\"artifact-1\",\"memory\":\"new text\"}"));
    String deleteResult =
        new MemoryDeleteAgentTool(mem0Client, scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"memory_id\":\"artifact-1\"}"));

    assertThat(updateResult).isEqualTo("collective group memories are read-only");
    assertThat(deleteResult).isEqualTo("collective group memories are read-only");
    verify(mem0Client, never())
        .updateMemory(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(mem0Client, never()).deleteMemory(org.mockito.ArgumentMatchers.any());
  }

  private ToolContext context(IncomingMessage message) {
    return ToolContextFixture.with(message).objectMapper(mapper).build();
  }

  private static IncomingMessage directMessage() {
    return message("iMessage;-;+15555550123", "+15555550123", false);
  }

  private static IncomingMessage groupMessage() {
    return message("iMessage;+;group-1", "+15555550123", true);
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
