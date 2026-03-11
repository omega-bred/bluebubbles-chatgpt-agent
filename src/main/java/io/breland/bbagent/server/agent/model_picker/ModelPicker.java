package io.breland.bbagent.server.agent.model_picker;

import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ModelPicker {
  private Set<String> premiumUsers = Set.of();

  public ResponseCreateParams.Builder applyResponsesModelParams(
      ResponseCreateParams.Builder builder,
      IncomingMessage incomingMessage,
      AgentWorkflowContext agentWorkflowContext) {
    if (isPremiumUser(incomingMessage)) {
        log.info("User {} is a premium user", incomingMessage.sender());
        builder.maxOutputTokens(2500);
        builder.reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
        builder.model("openai/" + ChatModel.GPT_5_3_CHAT_LATEST.toString());
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
        builder.maxOutputTokens(1000);
        builder.reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build());
        builder.model("ollama-mbp/gpt-oss:120b");
    }
    return builder;
  }

  public void setPremiumUsers(Set<String> premiumUsers) {
    this.premiumUsers = premiumUsers == null ? Set.of() : Set.copyOf(premiumUsers);
  }

  private boolean isPremiumUser(IncomingMessage incomingMessage) {
    if (incomingMessage == null || incomingMessage.sender() == null) {
      return false;
    }
    return premiumUsers.contains(incomingMessage.sender());
  }
}
