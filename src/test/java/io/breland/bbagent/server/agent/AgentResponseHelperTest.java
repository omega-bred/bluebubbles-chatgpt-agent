package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ToolChoice;
import com.openai.models.responses.ToolChoiceOptions;
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
    ResponseOutputMessage.OutputText outputText =
        ResponseOutputMessage.OutputText.builder().text(text).annotations(List.of()).build();
    ResponseOutputMessage message =
        ResponseOutputMessage.builder()
            .id("msg-1")
            .content(List.of(ResponseOutputMessage.Content.ofOutputText(outputText)))
            .role(ResponseOutputMessage.Role.ASSISTANT)
            .status(ResponseOutputMessage.Status.COMPLETED)
            .type(ResponseOutputMessage.Type.MESSAGE)
            .build();

    return Response.builder()
        .id("resp-1")
        .createdAt(0.0)
        .error((ResponseError) null)
        .incompleteDetails((Response.IncompleteDetails) null)
        .instructions((Response.Instructions) null)
        .metadata((Response.Metadata) null)
        .model(ChatModel.GPT_5_CHAT_LATEST)
        .output(List.of(ResponseOutputItem.ofMessage(message)))
        .parallelToolCalls(false)
        .temperature(0.2)
        .toolChoice(ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
        .tools(List.of())
        .topP(1.0)
        .build();
  }
}
