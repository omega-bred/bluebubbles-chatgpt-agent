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

  public ResponseCreateParams.Builder applyResponsesModelParams(
      ResponseCreateParams.Builder builder,
      IncomingMessage incomingMessage,
      AgentWorkflowContext agentWorkflowContext) {
    ModelAccessService.ModelAccess modelAccess = resolveModelAccess(incomingMessage);
    if (modelAccess.premium()) {
      log.info("User {} is a premium user", incomingMessage.sender());
      builder.maxOutputTokens(2500);
      builder.reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
      builder.model(modelAccess.responsesModel());
      builder
          .addTool(
              Tool.ImageGeneration.builder()
                  .model(Tool.ImageGeneration.Model.GPT_IMAGE_1_5)
                  .size(Tool.ImageGeneration.Size._1536X1024)
                  .moderation(Tool.ImageGeneration.Moderation.LOW)
                  .background(Tool.ImageGeneration.Background.AUTO)
                  .outputFormat(Tool.ImageGeneration.OutputFormat.PNG)
                  .quality(Tool.ImageGeneration.Quality.HIGH)
                  .build())
          .addTool(
              WebSearchTool.builder()
                  .type(WebSearchTool.Type.WEB_SEARCH_2025_08_26)
                  .searchContextSize(WebSearchTool.SearchContextSize.MEDIUM)
                  .build());
    } else {
      log.info("User {} is a standard user", incomingMessage.sender());
      builder.maxOutputTokens(1500);
      builder.reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build());
      builder.model(modelAccess.responsesModel());
    }
    return builder;
  }

  public boolean shouldSquashDeveloperMessagesIntoSystem(IncomingMessage incomingMessage) {
    String responsesModel = resolveModelAccess(incomingMessage).responsesModel();
    return responsesModel != null
        && ModelAccessService.DEVELOPER_MESSAGE_SYSTEM_SQUASH_MODELS.contains(
            responsesModel.trim());
  }

  private ModelAccessService.ModelAccess resolveModelAccess(IncomingMessage incomingMessage) {
    if (modelAccessService == null) {
      return new ModelAccessService.ModelAccess(
          null,
          false,
          "standard",
          ModelAccessService.STANDARD_MODEL_KEY,
          ModelAccessService.STANDARD_MODEL_LABEL,
          ModelAccessService.STANDARD_RESPONSES_MODEL,
          false,
          java.util.List.of());
    }
    return modelAccessService.resolve(incomingMessage);
  }
}
