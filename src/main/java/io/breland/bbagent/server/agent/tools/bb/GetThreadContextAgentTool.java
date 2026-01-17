package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetThreadContextAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_thread_context";

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get the latest message and images for the current thread. Use when asked about the last message in this thread or previously sent images in this thread.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("thread_root_guid", Map.of("type", "string")))),
        false,
        (context, args) -> {
          String threadRootGuid = getOptionalText(args, "thread_root_guid");
          if (threadRootGuid == null || threadRootGuid.isBlank()) {
            threadRootGuid = resolveThreadRootGuid(context);
          }
          if (threadRootGuid == null || threadRootGuid.isBlank()) {
            log.warn("Could not resolve thread_root_guid");
            return "no thread";
          }
          ConversationState state = context.getConversations().get(context.message().chatGuid());
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
          Map<String, Object> response = new LinkedHashMap<>();
          response.put("thread_root_guid", threadContext.threadRootGuid());
          response.put("last_message_guid", threadContext.lastMessageGuid());
          response.put("last_message_text", threadContext.lastMessageText());
          response.put("last_message_sender", threadContext.lastMessageSender());
          response.put("last_message_timestamp", threadContext.lastMessageTimestamp());
          response.put("last_image_urls", threadContext.lastImageUrls());
          try {
            String modelResponse = context.getMapper().writeValueAsString(response);
            log.info("modelResponse: {}", modelResponse);
            return modelResponse;
          } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
          }
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
    if (replyToGuid != null && !replyToGuid.isBlank()) {
      return replyToGuid;
    }
    return null;
  }

  private ConversationState.ThreadContext fetchThreadContext(
      ToolContext context, String threadRootGuid) {
    if (context == null || threadRootGuid == null || threadRootGuid.isBlank()) {
      return null;
    }
    try {
      var message = context.getBbHttpClientWrapper().getMessage(threadRootGuid);
      if (message == null) {
        return null;
      }
      String sender = message.getHandle() != null ? message.getHandle().toString() : null;
      List<String> imageUrls = extractImageUrls(context, message.getAttachments());
      String timestamp =
          message.getDateCreated() != null
              ? java.time.Instant.ofEpochSecond(message.getDateCreated()).toString()
              : java.time.Instant.now().toString();
      return new ConversationState.ThreadContext(
          threadRootGuid,
          message.getGuid().toString(),
          message.getText(),
          sender,
          timestamp,
          imageUrls);
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> extractImageUrls(ToolContext context, List<Object> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      return List.of();
    }
    var mapper = context.getMapper();
    return attachments.stream()
        .map(attachment -> mapper.convertValue(attachment, JsonNode.class))
        .map(this::parseAttachment)
        .flatMap(Optional::stream)
        .filter(att -> att.mimeType() != null && att.mimeType().startsWith("image/"))
        .map(this::resolveAttachmentImageUrl)
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
  }

  private Optional<IncomingAttachment> parseAttachment(JsonNode node) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    String guid = getText(node, "guid", "id");
    String mimeType = getText(node, "mimeType", "mime_type");
    String filename = getText(node, "filename", "transferName", "name");
    String url = getText(node, "url", "path");
    String dataUrl = getText(node, "dataUrl");
    String base64 = getText(node, "base64");
    return Optional.of(new IncomingAttachment(guid, mimeType, filename, url, dataUrl, base64));
  }

  private Optional<String> resolveAttachmentImageUrl(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    if (attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
      if (attachment.dataUrl().startsWith("data:image/")) {
        return Optional.of(attachment.dataUrl());
      }
      return Optional.empty();
    }
    if (attachment.base64() != null
        && attachment.mimeType() != null
        && attachment.mimeType().startsWith("image/")) {
      return Optional.of("data:" + attachment.mimeType() + ";base64," + attachment.base64().trim());
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return Optional.of(attachment.url());
    }
    if (attachment.guid() != null && !attachment.guid().isBlank()) {
      return Optional.of("attachment_guid:" + attachment.guid());
    }
    return Optional.empty();
  }

  private String getText(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      com.fasterxml.jackson.databind.JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isTextual()) {
        return value.asText();
      }
      if (value.isNumber() || value.isBoolean()) {
        return value.asText();
      }
    }
    return null;
  }
}
