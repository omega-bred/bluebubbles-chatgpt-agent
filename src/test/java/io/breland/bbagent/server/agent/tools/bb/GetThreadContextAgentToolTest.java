package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    conversations.put("iMessage;+;chat-1", state);
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    when(messageAgent.getObjectMapper()).thenReturn(mapper);
    when(messageAgent.getConversations()).thenReturn(conversations);

    String output =
        new GetThreadContextAgentTool(Mockito.mock(BBHttpClientWrapper.class))
            .getTool()
            .handler()
            .apply(
                new ToolContext(messageAgent, incomingMessage("iMessage;+;chat-1"), null),
                mapper.readTree("{\"thread_root_guid\":\"root-guid\"}"));
    JsonNode result = mapper.readTree(output);

    assertEquals("root-guid", result.get("thread_root_guid").asText());
    assertEquals("last-guid", result.get("last_message_guid").asText());
    assertEquals("last text", result.get("last_message_text").asText());
    assertEquals("+15555550123", result.get("last_message_sender").asText());
    assertEquals("2026-06-03T12:00:00Z", result.get("last_message_timestamp").asText());
    assertEquals("attachment_guid:image-1", result.get("last_image_urls").get(0).asText());
  }

  private static IncomingMessage incomingMessage(String chatGuid) {
    return new IncomingMessage(
        chatGuid,
        "message-guid",
        null,
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
