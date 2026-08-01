package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
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
  void extractToolContextItemsDropsNullFunctionCallNamespace() throws Exception {
    Response response = responseWithFunctionCallNamespaceNull();

    List<ResponseInputItem> items =
        AgentResponseHelper.extractToolContextItems(
            response, AgentResponseHelper.extractFunctionCalls(response));
    JsonNode functionCall =
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(items.getFirst()));

    assertFalse(functionCall.has("namespace"), functionCall::toPrettyString);
  }

  @Test
  void blockedToolCallOutputTellsModelNotToRepeatTool() throws Exception {
    String outputJson =
        new ObjectMapper().writeValueAsString(AgentResponseHelper.blockedToolCallOutput("call-1"));

    assertTrue(outputJson.contains("prevent repeated loops"));
    assertTrue(outputJson.contains("without calling this tool again"));
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

  private static Response responseWithFunctionCallNamespaceNull() throws Exception {
    return new ObjectMapper()
        .readValue(
            """
            {
              "id": "resp-1",
              "created_at": 0,
              "model": "openrouter/z-ai/glm-5.2",
              "output": [
                {
                  "type": "function_call",
                  "arguments": "{}",
                  "call_id": "call-1",
                  "name": "local_litellm_smoke_tool",
                  "namespace": null
                }
              ],
              "parallel_tool_calls": false,
              "tools": []
            }
            """,
            Response.class);
  }
}
