package io.breland.bbagent.server.agent.tools.bb;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.JsonSchemaUtilities;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesHandleAddress;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetThreadContextAgentTool implements ToolProvider {

  private final BBHttpClientWrapper bbHttpClientWrapper;

  public GetThreadContextAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public static final String TOOL_NAME = "get_thread_context";

  public record GetThreadContextResponse(
      @JsonProperty("thread_root_guid") String threadRootGuid,
      @JsonProperty("last_message_guid") String lastMessageGuid,
      @JsonProperty("last_message_text") String lastMessageText,
      @JsonProperty("last_message_sender") String lastMessageSender,
      @JsonProperty("last_message_timestamp") String lastMessageTimestamp,
      @JsonProperty("last_image_message_guid") String lastImageMessageGuid,
      @JsonProperty("last_image_urls") List<String> lastImageUrls) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get the latest message and image references for the current thread. Use last_image_message_guid with load_conversation_images to inspect or reuse the photo; last_image_urls contains references, not model image inputs.",
        JsonSchemaUtilities.functionParameters(
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
        false,
        (context, args) -> {
          String threadRootGuid = resolveThreadRootGuid(context);
          if (threadRootGuid == null || threadRootGuid.isBlank()) {
            log.warn("Could not resolve thread_root_guid");
            return "no thread";
          }
          ConversationState state = context.getConversationState(context.message().chatGuid());
          if (state == null) {
            log.warn("Could not resolve conversation state");
            return "no context";
          }
          ConversationState.ThreadContext threadContext = state.getThreadContext(threadRootGuid);
          if (threadContext == null) {
            threadContext = fetchThreadContext(context, threadRootGuid);
            if (threadContext != null) {
              state.recordThreadMessage(threadRootGuid, threadContext);
            } else {
              return "no context";
            }
          }
          GetThreadContextResponse response =
              new GetThreadContextResponse(
                  threadContext.threadRootGuid(),
                  threadContext.lastMessageGuid(),
                  threadContext.lastMessageText(),
                  threadContext.lastMessageSender(),
                  threadContext.lastMessageTimestamp(),
                  threadContext.lastImageMessageGuid(),
                  threadContext.lastImageUrls());
          String modelResponse = ToolJson.stringify(context.getMapper(), response, "no context");
          log.info("modelResponse: {}", modelResponse);
          return modelResponse;
        });
  }

  private String resolveThreadRootGuid(ToolContext context) {
    if (context == null || context.message() == null) {
      return null;
    }
    String threadOriginatorGuid = context.message().threadOriginatorGuid();
    if (threadOriginatorGuid != null && !threadOriginatorGuid.isBlank()) {
      return threadOriginatorGuid;
    }
    String replyToGuid = context.message().replyToGuid();
    return replyToGuid == null || replyToGuid.isBlank() ? null : replyToGuid;
  }

  private ConversationState.ThreadContext fetchThreadContext(
      ToolContext context, String threadRootGuid) {
    if (context == null || threadRootGuid == null || threadRootGuid.isBlank()) {
      return null;
    }
    try {
      var message = this.bbHttpClientWrapper.getMessage(threadRootGuid);
      if (message == null) {
        return null;
      }
      String sender = BlueBubblesHandleAddress.from(message.getHandle());
      List<String> imageUrls = extractImageUrls(context, message.getAttachments());
      String timestamp =
          io.breland.bbagent.server.TimeSupport.epochSecondsOrMillisOrNow(message.getDateCreated())
              .toString();
      return new ConversationState.ThreadContext(
          threadRootGuid,
          message.getGuid().toString(),
          message.getText(),
          sender,
          timestamp,
          imageUrls.isEmpty() ? null : message.getGuid().toString(),
          imageUrls);
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> extractImageUrls(ToolContext context, List<Object> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      return List.of();
    }
    return IncomingAttachment.fromHistory(attachments, context.getMapper()).stream()
        .filter(IncomingAttachment::mayBeImage)
        .map(
            attachment ->
                attachment.guid() == null ? "image" : "attachment_guid:" + attachment.guid())
        .toList();
  }
}
