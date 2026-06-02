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
  void parseTextFunctionCalls_preservesCommasInsideArgumentValues() throws Exception {
    List<com.openai.models.responses.ResponseFunctionToolCall> calls =
        AgentResponseHelper.parseTextFunctionCalls(
            "call:send_text{message:Clone repos, cherry-pick commit, and push to"
                + " GitHub.,thread_id:Notify Breland once the cherry-pick and push are"
                + " complete.}");

    assertEquals(1, calls.size());
    assertEquals("send_text", calls.getFirst().name());
    var args = new ObjectMapper().readTree(calls.getFirst().arguments());
    assertEquals(
        "Clone repos, cherry-pick commit, and push to GitHub.", args.get("message").asText());
    assertEquals(
        "Notify Breland once the cherry-pick and push are complete.",
        args.get("thread_id").asText());
  }

  @Test
  void parseTextFunctionCalls_usesJsonEncodingForArgumentValues() throws Exception {
    List<com.openai.models.responses.ResponseFunctionToolCall> calls =
        AgentResponseHelper.parseTextFunctionCalls(
            "call:send_text{message:\"quote \\\" newline\\n slash\\\\\"}");

    assertEquals(1, calls.size());
    assertEquals(
        "quote \\\" newline\\n slash\\\\",
        new ObjectMapper().readTree(calls.getFirst().arguments()).get("message").asText());
  }

  @Test
  void normalizeAssistantText_stripsTextToolCallLines() {
    String normalized =
        AgentResponseHelper.normalizeAssistantText(
            new ObjectMapper(),
"""
I'll start that now.
call:send_text{message:Clone repos, cherry-pick commit, and push to GitHub.,thread_id:Notify Breland once complete.}
""");

    assertEquals("I'll start that now.", normalized);
  }

  @Test
  void normalizeAssistantText_removesOnlyTextToolCallResponse() {
    String normalized =
        AgentResponseHelper.normalizeAssistantText(
            new ObjectMapper(),
            "call:send_text{message:Clone repos, cherry-pick commit, and push to"
                + " GitHub.,thread_id:Notify Breland once complete.}");

    assertEquals("", normalized);
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

  @Test
  void blockedToolCallOutputTellsModelNotToRepeatTool() throws Exception {
    String outputJson =
        new ObjectMapper().writeValueAsString(AgentResponseHelper.blockedToolCallOutput("call-1"));

    assertTrue(outputJson.contains("prevent repeated loops"));
    assertTrue(outputJson.contains("without calling this tool again"));
  }

  @Test
  void emptyAssistantResponseRetryInstructionAsksForVisibleAnswer() throws Exception {
    String itemJson =
        new ObjectMapper()
            .writeValueAsString(AgentResponseHelper.emptyAssistantResponseRetryInstruction());

    assertTrue(itemJson.contains("developer"));
    assertTrue(itemJson.contains("user-visible assistant message"));
    assertTrue(itemJson.contains("Do not put the answer only in reasoning"));
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
