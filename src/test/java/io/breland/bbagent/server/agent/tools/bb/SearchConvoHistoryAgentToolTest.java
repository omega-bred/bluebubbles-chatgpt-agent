package io.breland.bbagent.server.agent.tools.bb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchConvoHistoryAgentToolTest {
  private final BBHttpClientWrapper wrapper = mock(BBHttpClientWrapper.class);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serializesLegacySenderAsAddressScalarOrNull() throws Exception {
    when(wrapper.getObjectMapper()).thenReturn(mapper);
    when(wrapper.searchConversationHistory("iMessage;+;group", "Wordle", 20, 0))
        .thenReturn(
            List.of(
                message("00000000-0000-0000-0000-000000000101", Map.of("address", "+15555550199")),
                message("00000000-0000-0000-0000-000000000102", null)));
    ToolContext context = mock(ToolContext.class);
    when(context.message()).thenReturn(incoming());
    when(context.getMapper()).thenReturn(mapper);

    String output =
        new SearchConvoHistoryAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"query\":\"Wordle\",\"limit\":20,\"offset\":0}"));

    JsonNode messages = mapper.readTree(output).get("messages");
    assertThat(messages.get(0).get("sender").isTextual()).isTrue();
    assertThat(messages.get(0).get("sender").asText()).isEqualTo("+15555550199");
    assertThat(messages.get(1).get("sender").isNull()).isTrue();
  }

  private static Message message(String guid, Object handle) {
    return new Message()
        .guid(UUID.fromString(guid))
        .text("Wordle")
        .dateCreated(Instant.parse("2026-08-09T12:00:00Z").toEpochMilli())
        .handle(handle);
  }

  private static IncomingMessage incoming() {
    return new IncomingMessage(
        "iMessage;+;group",
        "request-message",
        null,
        "search Wordle",
        false,
        "iMessage",
        "+15555550123",
        true,
        Instant.parse("2026-08-09T12:00:00Z"),
        List.of(),
        false);
  }
}
