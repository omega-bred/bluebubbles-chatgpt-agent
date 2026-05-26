package io.breland.bbagent.server.agent.llm;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import java.util.function.Supplier;

public final class OpenAiResponsesLlmProvider implements LlmProvider {
  private final Supplier<OpenAIClient> openAiSupplier;
  private final ModelPicker modelPicker;

  public OpenAiResponsesLlmProvider(
      Supplier<OpenAIClient> openAiSupplier, ModelPicker modelPicker) {
    this.openAiSupplier = openAiSupplier;
    this.modelPicker = modelPicker;
  }

  @Override
  public String providerKey() {
    return "openai_responses";
  }

  @Override
  public Response createResponse(LlmRequest request) {
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder().inputOfResponse(request.inputItems());
    modelPicker.applyResponsesModelParams(
        params, request.modelAccess(), request.message(), request.workflowContext());
    for (AgentTool tool : request.tools()) {
      params.addTool(Tool.ofFunction(tool.asFunctionTool()));
    }
    return openAiSupplier.get().responses().create(params.build());
  }
}
