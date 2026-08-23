package io.breland.bbagent.server.agent.llm;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import org.springframework.stereotype.Component;

@Component
public final class OpenAiResponsesLlmProvider implements LlmProvider {
  private final OpenAiClientProvider openAiClientProvider;
  private final ModelPicker modelPicker;

  public OpenAiResponsesLlmProvider(
      OpenAiClientProvider openAiClientProvider, ModelPicker modelPicker) {
    this.openAiClientProvider = openAiClientProvider;
    this.modelPicker = modelPicker;
  }

  @Override
  public Response createResponse(LlmRequest request) {
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder().inputOfResponse(request.inputItems());
    modelPicker.applyResponsesModelParams(params, request.modelAccess(), request.message());
    for (AgentTool tool : request.tools()) {
      params.addTool(Tool.ofFunction(tool.asFunctionTool()));
    }
    return openAiClientProvider.get().responses().create(params.build());
  }
}
