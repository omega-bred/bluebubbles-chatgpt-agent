package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetGroupCatchupAgentToolTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final MemoryScopeResolver scopeResolver = mock(MemoryScopeResolver.class);
  private final ConversationDigestService digestService = mock(ConversationDigestService.class);
  private final ToolContext context = mock(ToolContext.class);

  @BeforeEach
  void setUp() {
    when(context.message()).thenReturn(message(false));
    when(context.canonicalAccountId()).thenReturn(Optional.of("account-1"));
    when(context.getMapper()).thenReturn(mapper);
    when(scopeResolver.conversationDigestService()).thenReturn(Optional.of(digestService));
    when(digestService.currentTime()).thenReturn(NOW);
  }

  @Test
  void returnsStructuredCoverageForADirectChatRequest() throws Exception {
    Instant from = NOW.minusSeconds(6 * 60 * 60);
    when(digestService.catchUp("account-1", "Trip", from, NOW))
        .thenReturn(
            new CatchupResult(
                List.of(
                    new CatchupGroup(
                        "Trip",
                        "Saturday was selected.",
                        List.of("The group compared two days."),
                        List.of("Meet Saturday."),
                        List.of("Choose a restaurant."),
                        from,
                        NOW,
                        NOW.minusSeconds(60))),
                List.of()));

    String response =
        new GetGroupCatchupAgentTool(scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"group\":\"Trip\",\"lookback_hours\":6}"));

    var group = mapper.readTree(response).path("groups").get(0);
    assertThat(group.path("group").asText()).isEqualTo("Trip");
    assertThat(group.path("coverage_through").asText()).isEqualTo(NOW.minusSeconds(60).toString());
    assertThat(group.path("decisions").get(0).asText()).isEqualTo("Meet Saturday.");
  }

  @Test
  void rejectsUseFromAGroupConversation() throws Exception {
    when(context.message()).thenReturn(message(true));

    String response =
        new GetGroupCatchupAgentTool(scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{}"));

    assertThat(response).isEqualTo("available only in a one-to-one chat");
  }

  @Test
  void surfacesSemanticDisambiguationOptions() throws Exception {
    Instant from = NOW.minusSeconds(86_400);
    when(digestService.catchUp("account-1", "Trip", from, NOW))
        .thenReturn(
            new CatchupResult(List.of(), List.of("Trip (last active 2026-08-08T12:00:00Z)")));

    String response =
        new GetGroupCatchupAgentTool(scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"group\":\"Trip\"}"));

    assertThat(mapper.readTree(response).path("disambiguation_required").asBoolean()).isTrue();
  }

  private static IncomingMessage message(boolean group) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        group ? "iMessage;+;group-1" : "iMessage;-;+15555550123",
        "message-1",
        null,
        "What did I miss?",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        group,
        NOW,
        List.of(),
        false);
  }
}
