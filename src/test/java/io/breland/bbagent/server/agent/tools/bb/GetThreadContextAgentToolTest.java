package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GetThreadContextAgentToolTest {

  @Test
  void serializesCachedThreadContextAsJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ConversationState state = new ConversationState();
    state.recordThreadMessage(
        "root-guid",
        new ConversationState.ThreadContext(
            "root-guid",
            "last-guid",
            "last text",
            "+15555550123",
            "2026-06-03T12:00:00Z",
            List.of("attachment_guid:image-1")));
    String output =
        new GetThreadContextAgentTool(Mockito.mock(BBHttpClientWrapper.class))
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(incomingMessage("iMessage;+;chat-1"))
                    .objectMapper(mapper)
                    .conversationState("iMessage;+;chat-1", state)
                    .build(),
                mapper.createObjectNode());
    JsonNode result = mapper.readTree(output);

    assertEquals("root-guid", result.get("thread_root_guid").asText());
    assertEquals("last-guid", result.get("last_message_guid").asText());
    assertEquals("last text", result.get("last_message_text").asText());
    assertEquals("+15555550123", result.get("last_message_sender").asText());
    assertEquals("2026-06-03T12:00:00Z", result.get("last_message_timestamp").asText());
    assertEquals("attachment_guid:image-1", result.get("last_image_urls").get(0).asText());
  }

  @Test
  void serializesUncachedThreadSenderAsTheHandleAddress() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ConversationState state = new ConversationState();
    BBHttpClientWrapper client = Mockito.mock(BBHttpClientWrapper.class);
    when(client.getMessage("root-guid"))
        .thenReturn(
            new Message()
                .guid(UUID.fromString("8d7d7d09-dd10-425f-9b72-8ef322eca49d"))
                .text("uncached text")
                .handle(Map.of("address", "+15555550123"))
                .dateCreated(Instant.parse("2026-06-03T12:00:00Z").getEpochSecond())
                .attachments(List.of()));

    String output =
        new GetThreadContextAgentTool(client)
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(incomingMessage("iMessage;+;chat-1"))
                    .objectMapper(mapper)
                    .conversationState("iMessage;+;chat-1", state)
                    .build(),
                mapper.createObjectNode());
    JsonNode result = mapper.readTree(output);

    assertEquals("+15555550123", result.get("last_message_sender").asText());
  }

  private static IncomingMessage incomingMessage(String chatGuid) {
    return new IncomingMessage(
        chatGuid,
        "message-guid",
        "root-guid",
        "what was sent?",
        false,
        "iMessage",
        "+15555550123",
        true,
        Instant.now(),
        List.of(),
        false);
  }
}
