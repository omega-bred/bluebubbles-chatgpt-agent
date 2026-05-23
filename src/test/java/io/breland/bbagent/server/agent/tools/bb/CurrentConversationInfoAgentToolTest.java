package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CurrentConversationInfoAgentToolTest {

  @Test
  void readsConversationInfoFromRawJsonWhenGroupIdIsNotUuid() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    StubBBHttpClientWrapper wrapper =
        new StubBBHttpClientWrapper(
            mapper.readTree(
                """
                {
                  "guid": "any;+;chat193898160757775814",
                  "displayName": "Wordling Wonders",
                  "chatIdentifier": "chat193898160757775814",
                  "groupId": "at_0_7E65761D-1B84-4FAF-BC17-7F485E7FDEC5",
                  "lastAddressedHandle": "+18035551212",
                  "participants": [
                    {"address": "+18035551212", "country": "US"}
                  ]
                }
                """));
    CurrentConversationInfoAgentTool toolProvider = new CurrentConversationInfoAgentTool(wrapper);
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    when(messageAgent.getObjectMapper()).thenReturn(mapper);

    String output =
        toolProvider
            .getTool()
            .handler()
            .apply(
                new ToolContext(
                    messageAgent, incomingMessage("any;+;chat193898160757775814"), null),
                mapper.createObjectNode());
    JsonNode result = mapper.readTree(output);

    assertEquals("Wordling Wonders", result.get("display_name").asText());
    assertEquals("chat193898160757775814", result.get("chat_identifier").asText());
    assertEquals("+18035551212", result.get("participants").get(0).get("handle").asText());
    assertTrue(output.contains("Wordling Wonders"));
  }

  private static IncomingMessage incomingMessage(String chatGuid) {
    return new IncomingMessage(
        chatGuid,
        "message-guid",
        null,
        "what chat is this?",
        false,
        "iMessage",
        "+18035551212",
        true,
        Instant.now(),
        List.of(),
        false);
  }

  private static final class StubBBHttpClientWrapper extends BBHttpClientWrapper {
    private final JsonNode conversationInfo;

    StubBBHttpClientWrapper(JsonNode conversationInfo) {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
      this.conversationInfo = conversationInfo;
    }

    @Override
    public JsonNode getConversationInfoJson(String chatGuid) {
      return conversationInfo;
    }
  }
}
