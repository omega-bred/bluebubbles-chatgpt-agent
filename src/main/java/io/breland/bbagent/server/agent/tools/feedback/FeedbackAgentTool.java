package io.breland.bbagent.server.agent.tools.feedback;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.format.DateTimeFormatter;

public class FeedbackAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "record_feedback";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final FeedbackService feedbackService;

  @Schema(description = "Record user feedback about the assistant or chat agent experience.")
  public record FeedbackRequest(
      @Schema(
              description =
                  "The user's exact feedback, bug report, feature request, capability request, complaint, or praise.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("feedback")
          String feedback,
      @Schema(
              description =
                  "Short category such as model, tool, bluebubbles, feature_request, bug, capability, or general.")
          @JsonProperty("category")
          String category) {}

  public FeedbackAgentTool(FeedbackService feedbackService) {
    this.feedbackService = feedbackService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Record user feedback for the agent owner. Call automatically when the incoming message is feedback about the model, assistant behavior, tools, BlueBubbles/iMessage chat agent, missing capabilities, bugs, complaints, praise, or a request like 'tell your creator', 'can you do this?', or 'why can't you do this?'. Record the user's exact feedback. Do not use for ordinary task requests unless the user is asking for or criticizing a capability.",
        jsonSchema(FeedbackRequest.class),
        false,
        (context, args) -> {
          FeedbackRequest request = context.getMapper().convertValue(args, FeedbackRequest.class);
          if (request.feedback() == null || request.feedback().isBlank()) {
            return "missing feedback";
          }
          try {
            FeedbackService.RecordedFeedback feedback =
                feedbackService.recordFeedback(
                    context.message(), context.accountId(), request.feedback(), request.category());
            return ToolJson.stringify(
                context.getMapper(), toResponse(feedback), "recorded feedback");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record FeedbackResponse(
      String feedbackId, String submittedAt, boolean recorded, String userFacingText) {}

  private FeedbackResponse toResponse(FeedbackService.RecordedFeedback feedback) {
    return new FeedbackResponse(
        feedback.feedbackId(),
        INSTANT_FORMATTER.format(feedback.submittedAt()),
        true,
        "Feedback recorded. Thanks for saying it directly.");
  }
}
