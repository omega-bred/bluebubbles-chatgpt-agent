package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentResponseHelperTest {

  @Test
  void parseTextFunctionCalls_parsesSimpleCallSyntax() throws Exception {
    List<com.openai.models.responses.ResponseFunctionToolCall> calls =
        AgentResponseHelper.parseTextFunctionCalls("call:send_giphy{query:artist painting}");

    assertEquals(1, calls.size());
    assertEquals("send_giphy", calls.getFirst().name());
    assertEquals(
        "artist painting",
        new ObjectMapper().readTree(calls.getFirst().arguments()).get("query").asText());
  }

  @Test
  void extractFunctionCalls_fallsBackToTextWhenNoNativeToolCalls() {
    Response response = responseWithText("call:send_giphy{query:artist painting}");

    List<com.openai.models.responses.ResponseFunctionToolCall> calls =
        AgentResponseHelper.extractFunctionCalls(response);

    assertEquals(1, calls.size());
    assertEquals("send_giphy", calls.getFirst().name());
    assertTrue(calls.getFirst().callId().startsWith("text-call-"));
  }

  private static Response responseWithText(String text) {
    try {
      String responseJson =
          """
          {
            "id": "resp-1",
            "created_at": 0,
            "model": "gpt-5-chat-latest",
            "output": [
              {
                "type": "message",
                "id": "msg-1",
                "role": "assistant",
                "status": "completed",
                "content": [
                  {
                    "type": "output_text",
                    "text": %s,
                    "annotations": []
                  }
                ]
              }
            ],
            "parallel_tool_calls": false,
            "temperature": 0.2,
            "tools": [],
            "top_p": 1.0
          }
          """
              .formatted(new ObjectMapper().writeValueAsString(text));
      return new ObjectMapper().readValue(responseJson, Response.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to build test response payload", e);
    }
  }
}
