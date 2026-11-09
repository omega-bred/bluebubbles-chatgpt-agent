package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ChatModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.List;

final class ResponseFixtures {
  private ResponseFixtures() {}

  static Response responseWithText(String text) {
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

  static Response baseResponse(List<ResponseOutputItem> outputItems) {
    return Response.builder()
        .id("resp-1")
        .createdAt(0.0)
        .error((ResponseError) null)
        .incompleteDetails((Response.IncompleteDetails) null)
        .instructions((Response.Instructions) null)
        .metadata((Response.Metadata) null)
        .model(ChatModel.GPT_5_CHAT_LATEST)
        .output(outputItems)
        .parallelToolCalls(false)
        .temperature(0.2)
        .toolChoice(ToolChoiceOptions.AUTO)
        .tools(List.of())
        .topP(1.0)
        .build();
  }

  static String extractText(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      EasyInputMessage.Content content = item.asEasyInputMessage().content();
      if (content.isTextInput()) {
        return content.asTextInput();
      }
      if (content.isResponseInputMessageContentList()) {
        return content.asResponseInputMessageContentList().toString();
      }
    }
    if (item.isMessage()) {
      return item.asMessage().content().toString();
    }
    return item.toString();
  }
}
