package io.breland.bbagent.server.agent.model_picker;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ModelPicker {
  private final ModelAccessService modelAccessService;

  public ModelPicker() {
    this(null);
  }

  @Autowired
  public ModelPicker(@Nullable ModelAccessService modelAccessService) {
    this.modelAccessService = modelAccessService;
  }

  public @Nullable ModelAccessService modelAccessService() {
    return modelAccessService;
  }

  public ResponseCreateParams.Builder applyResponsesModelParams(
      ResponseCreateParams.Builder builder,
      IncomingMessage incomingMessage,
      AgentWorkflowContext agentWorkflowContext) {
    ModelAccessService.ModelAccess modelAccess = resolveModelAccess(incomingMessage);
    return applyResponsesModelParams(builder, modelAccess, incomingMessage, agentWorkflowContext);
  }

  public ResponseCreateParams.Builder applyResponsesModelParams(
      ResponseCreateParams.Builder builder,
      ModelAccessService.ModelAccess modelAccess,
      IncomingMessage incomingMessage,
      AgentWorkflowContext agentWorkflowContext) {
    boolean premium = modelAccess.premium();
    log.info(
        "Applying {} model access using model {}",
        premium ? "premium" : "standard",
        modelAccess.currentModelKey());
    builder.maxOutputTokens(premium ? 2500 : 1500);
    builder.reasoning(
        Reasoning.builder()
            .effort(premium ? ReasoningEffort.MEDIUM : ReasoningEffort.HIGH)
            .build());
    builder.model(modelAccess.responsesModel());
    if (premium) {
      if (modelAccess.supportsImageGeneration()) {
        builder.addTool(
            Tool.ImageGeneration.builder()
                .model(Tool.ImageGeneration.Model.GPT_IMAGE_1_5)
                .size(Tool.ImageGeneration.Size._1536X1024)
                .moderation(Tool.ImageGeneration.Moderation.LOW)
                .background(Tool.ImageGeneration.Background.AUTO)
                .outputFormat(Tool.ImageGeneration.OutputFormat.PNG)
                .quality(Tool.ImageGeneration.Quality.HIGH)
                .build());
      }
      if (modelAccess.supportsWebSearch()) {
        builder.addTool(
            WebSearchTool.builder()
                .type(WebSearchTool.Type.WEB_SEARCH_2025_08_26)
                .searchContextSize(WebSearchTool.SearchContextSize.MEDIUM)
                .build());
      }
    }
    return builder;
  }

  public boolean shouldSquashDeveloperMessagesIntoSystem(IncomingMessage incomingMessage) {
    String responsesModel = resolveModelAccess(incomingMessage).responsesModel();
    return responsesModel != null
        && ModelAccessService.DEVELOPER_MESSAGE_SYSTEM_SQUASH_MODELS.contains(
            responsesModel.trim());
  }

  public ModelAccessService.ModelAccess resolveModelAccess(IncomingMessage incomingMessage) {
    if (modelAccessService == null) {
      return new ModelAccessService.ModelAccess(
          null,
          false,
          ModelAccessService.STANDARD_MODEL_KEY,
          ModelAccessService.STANDARD_MODEL_LABEL,
          ModelAccessService.STANDARD_RESPONSES_MODEL,
          false,
          java.util.List.of());
    }
    return modelAccessService.resolve(incomingMessage);
  }
}
