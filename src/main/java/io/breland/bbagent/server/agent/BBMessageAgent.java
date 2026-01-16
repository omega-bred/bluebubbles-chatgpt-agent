package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.responses.*;
import io.breland.bbagent.server.agent.tools.*;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.assistant.AssistantResponsivenessAgentTool;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SearchConvoHistoryAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SetGroupIconAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.*;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.tools.memory.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BBMessageAgent {

  public static final int MAX_HISTORY = 50;
  public static final String NO_RESPONSE_TEXT = "NO_RESPONSE";
  private static final int MAX_TOOL_LOOPS = 50;
  private static final int MAX_IMAGE_ATTACHMENTS = 4;
  private static final int MAX_FILE_ATTACHMENTS = 4;
  public static final String IMESSAGE_SERVICE = "iMessage";

  public enum AssistantResponsiveness {
    DEFAULT,
    LESS_RESPONSIVE,
    MORE_RESPONSIVE
  }

  private static final Set<String> GROUP_ONLY_TOOLS =
      Set.of(RenameConversationAgentTool.TOOL_NAME, SetGroupIconAgentTool.TOOL_NAME);
  public static final Set<String> SUPPORTED_REACTIONS =
      Set.of(
          "love",
          "like",
          "dislike",
          "laugh",
          "emphasize",
          "question",
          "-love",
          "-like",
          "-dislike",
          "-laugh",
          "-emphasize",
          "-question");

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  @Getter private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  // TODO: persist
  private final Map<String, AssistantResponsiveness> assistantResponsivenessByChat =
      new ConcurrentHashMap<>();
  // TODO: persist
  private final Map<String, String> globalNamesBySender = new ConcurrentHashMap<>();
  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

  private OpenAIClient openAIClient;
  private final Supplier<OpenAIClient> openAiSupplier =
      () -> {
        if (openAIClient == null) {
          openAIClient = OpenAIOkHttpClient.fromEnv();
        }
        return openAIClient;
      };

  private BBHttpClientWrapper bbHttpClientWrapper;
  private Mem0Client mem0Client;
  private GcalClient gcalClient;
  private GiphyClient giphyClient;

  @Autowired
  public BBMessageAgent(
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      GiphyClient giphyClient) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mem0Client = mem0Client;
    this.gcalClient = gcalClient;
    this.giphyClient = giphyClient;
    registerBuiltInTools();
  }

  BBMessageAgent(
      OpenAIClient openAIClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      GiphyClient giphyClient) {
    this.openAIClient = openAIClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mem0Client = mem0Client;
    this.gcalClient = gcalClient;
    this.giphyClient = giphyClient;
    registerBuiltInTools();
  }

  public void handleIncomingMessage(Map<String, Object> payload) {
    log.debug("Incoming Message {}", payload);
    JsonNode root = objectMapper.valueToTree(payload);
    String type = root.path("type").asText();
    if (!isMessageEvent(type, root.path("data"))) {
      return;
    }
    JsonNode data = root.path("data");
    IncomingMessage message = parseWebhookMessage(data);
    if (message != null) {
      handleIncomingMessage(message);
    }
  }

  private boolean isMessageEvent(String type, JsonNode data) {
    if ("new-message".equals(type)) {
      return true;
    }
    if ("edited-message".equals(type)
        || "message-edited".equals(type)
        || "edit-message".equals(type)) {
      return true;
    }
    JsonNode edited = data.path("dateEdited");
    return edited != null && !edited.isNull();
  }

  public void handleIncomingMessage(IncomingMessage message) {
    if (!shouldProcess(message)) {
      log.debug("Dropping message {}", message);
      return;
    }
    log.info("Processing Message {}", message);
    ConversationState state =
        conversations.computeIfAbsent(
            message.chatGuid(),
            key -> {
              ConversationState stateToHydrate = new ConversationState();
              log.info("Hydrating conversation state for chat {}", key);
              try {
                var messages = bbHttpClientWrapper.getMessagesInChat(key);

                messages
                    .reversed()
                    .forEach(
                        msg -> {
                          var turn =
                              msg.getIsFromMe() != null && msg.getIsFromMe() == true
                                  ? ConversationTurn.assistant(
                                      msg.getText(), Instant.ofEpochSecond(msg.getDateCreated()))
                                  : ConversationTurn.user(
                                      msg.getText(), Instant.ofEpochSecond(msg.getDateCreated()));
                          if (!msg.getGuid().equals(message.messageGuid())) {
                            if (!msg.getIsFromMe()) {
                              stateToHydrate.setLastProcessedMessageGuid(msg.getGuid());
                              stateToHydrate.setLastProcessedMessageFingerprint(
                                  IncomingMessage.create(msg).computeMessageFingerprint());
                            }
                            stateToHydrate.addTurn(turn);
                          }
                        });

                log.info(
                    "Hydrated conversation state for chat {} got {} messages from history",
                    key,
                    stateToHydrate.history().size());
              } catch (Exception e) {
                log.warn("Failed to hydrate conversation state for chat {}", key, e);
              }
              return stateToHydrate;
            });
    synchronized (state) {
      String fingerprint = message.computeMessageFingerprint();
      if (message.messageGuid() != null
          && message.messageGuid().equals(state.getLastProcessedMessageGuid())) {
        log.warn("Dropping message [ message guid ] {}", message);
        return;
      }
      if (fingerprint != null && fingerprint.equals(state.getLastProcessedMessageFingerprint())) {
        log.warn("Dropping duplicate message [ fingerprint ] {}", message);
        return;
      }
      Response response = runAssistant(state, message);
      if (response != null) {
        Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
        state.addTurn(ConversationTurn.user(message.summaryForHistory(), timestamp));
      }
      state.setLastProcessedMessageGuid(message.messageGuid());
      state.setLastProcessedMessageFingerprint(fingerprint);
    }
  }

  private boolean shouldProcess(IncomingMessage message) {
    if (message == null) {
      return false;
    }
    if (Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    if (message.chatGuid() == null || message.chatGuid().isBlank()) {
      return false;
    }
    if (message.service() != null && !IMESSAGE_SERVICE.equalsIgnoreCase(message.service())) {
      return false;
    }
    if (message.text() == null
        || message.text().isBlank() && message.isGroup() != null && message.isGroup()) {
      // group name or photo edited.
      return false;
    }
    return true;
  }

  private Response runAssistant(ConversationState state, IncomingMessage message) {
    List<ResponseInputItem> inputItems = buildConversationInput(state, message);
    log.trace("Getting response for {}", inputItems.toString());
    Response response = createResponse(inputItems, message);
    if (response == null) {
      log.warn("Got a null response for {}", message.text());
      return null;
    }
    log.debug(response.toString());
    boolean sentTextByTool = false;
    boolean sentReactionByTool = false;
    int loops = 0;
    while (loops < MAX_TOOL_LOOPS) {
      List<ResponseFunctionToolCall> toolCalls = extractFunctionCalls(response);
      log.debug(toolCalls.toString());
      if (toolCalls.isEmpty()) {
        break;
      }
      if (toolCalls.stream().anyMatch(call -> SendTextAgentTool.TOOL_NAME.equals(call.name()))) {
        sentTextByTool = true;
      }
      if (toolCalls.stream()
          .anyMatch(call -> SendReactionAgentTool.TOOL_NAME.equals(call.name()))) {
        sentReactionByTool = true;
      }
      List<ResponseInputItem> toolContinuation = new ArrayList<>(inputItems);
      log.debug(toolContinuation.toString());
      for (ResponseFunctionToolCall toolCall : toolCalls) {
        toolContinuation.add(ResponseInputItem.ofFunctionCall(toolCall));
      }
      toolContinuation.addAll(executeToolCalls(toolCalls, message));
      response = createResponse(toolContinuation, message);
      inputItems = toolContinuation;
      loops++;
    }
    String assistantText = normalizeAssistantText(extractResponseText(response));
    List<GeneratedImage> generatedImages = extractGeneratedImages(response);
    log.info("Extracted " + generatedImages.size() + " images from assistant response");
    boolean sentImageByMultipart = false;
    if (!generatedImages.isEmpty()) {
      log.info("Found {} image for multipart requests", generatedImages.size());
      String caption = assistantText;
      if (caption != null) {
        String trimmed = caption.trim();
        if (trimmed.isBlank()
            || NO_RESPONSE_TEXT.equalsIgnoreCase(trimmed)
            || parseReactionText(trimmed).isPresent()) {
          caption = null;
        }
      }
      List<BBHttpClientWrapper.AttachmentData> attachments = new ArrayList<>();
      for (GeneratedImage image : generatedImages) {
        attachments.add(new BBHttpClientWrapper.AttachmentData(image.filename(), image.bytes()));
      }
      sentImageByMultipart =
          bbHttpClientWrapper.sendMultipartMessage(message.chatGuid(), caption, attachments);
      if (sentImageByMultipart && caption != null && !caption.isBlank()) {
        sentTextByTool = true;
      }
    }
    Optional<String> parsedReaction = parseReactionText(assistantText);
    if (parsedReaction.isPresent()) {
      String reaction = parsedReaction.get();
      if (sentReactionByTool) {
        log.debug("Skipping reaction text output since reaction tool already ran");
      } else if (bbHttpClientWrapper.sendReactionDirect(message, reaction)) {
        state.addTurn(ConversationTurn.assistant("[reaction: " + reaction + "]", Instant.now()));
      } else {
        log.warn("Unable to send reaction for assistant text: {}", assistantText);
      }
      return response;
    }
    if (!assistantText.isBlank() && !NO_RESPONSE_TEXT.equalsIgnoreCase(assistantText.trim())) {
      log.info("Assistant reply text: {}", assistantText);
      if (!sentTextByTool && !sentImageByMultipart) {
        bbHttpClientWrapper.sendTextDirect(message, assistantText.trim());
      }
      state.addTurn(ConversationTurn.assistant(assistantText.trim(), Instant.now()));
    } else {
      if (sentImageByMultipart) {
        state.addTurn(ConversationTurn.assistant("[image]", Instant.now()));
      } else {
        log.info("No assistant reply generated");
      }
    }
    return response;
  }

  private Response createResponse(List<ResponseInputItem> inputItems, IncomingMessage message) {
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder()
            .addTool(
                Tool.ImageGeneration.builder()
                    .model(Tool.ImageGeneration.Model.GPT_IMAGE_1)
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
                    .build())
            .model(ChatModel.GPT_5_CHAT_LATEST)
            .inputOfResponse(inputItems)
            .temperature(0.2)
            .maxOutputTokens(600);
    for (AgentTool tool : tools.values()) {
      if (shouldIncludeTool(tool, message)) {
        params.addTool(Tool.ofFunction(tool.asFunctionTool()));
      }
    }
    try {
      ResponseCreateParams finalRequest = params.build();

      log.trace("Final message: {}", finalRequest.toString());
      return openAiSupplier.get().responses().create(finalRequest);
    } catch (RuntimeException e) {
      log.warn("OpenAI response failed", e);
      return null;
    }
  }

  private List<ResponseFunctionToolCall> extractFunctionCalls(Response response) {
    if (response == null || response.output() == null) {
      return List.of();
    }
    List<ResponseFunctionToolCall> calls = new ArrayList<>();
    for (ResponseOutputItem item : response.output()) {
      if (item.functionCall().isPresent()) {
        calls.add(item.functionCall().get());
      }
    }
    return calls;
  }

  private List<ResponseInputItem> executeToolCalls(
      List<ResponseFunctionToolCall> toolCalls, IncomingMessage message) {
    List<ResponseInputItem> outputs = new ArrayList<>();
    for (ResponseFunctionToolCall toolCall : toolCalls) {
      log.info("Invoking tool {}", toolCall.name());
      AgentTool tool = tools.get(toolCall.name());
      String output;
      try {
        JsonNode args = objectMapper.readTree(toolCall.arguments());
        if (tool == null) {
          output = "Unknown tool: " + toolCall.name();
        } else {
          output = tool.handler().apply(new ToolContext(this, message), args);
        }
      } catch (Exception e) {
        output = "Tool call failed: " + e.getMessage();
        log.warn("Tool call failed: {}", toolCall.name(), e);
      }

      ResponseInputItem.FunctionCallOutput toolOutput =
          ResponseInputItem.FunctionCallOutput.builder()
              .callId(toolCall.callId())
              .output(output)
              .build();
      outputs.add(ResponseInputItem.ofFunctionCallOutput(toolOutput));
    }
    return outputs;
  }

  private Optional<String> parseReactionText(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String trimmed = text.trim();
    if (!trimmed.startsWith("[reaction:") || !trimmed.endsWith("]")) {
      return Optional.empty();
    }
    String inner = trimmed.substring("[reaction:".length(), trimmed.length() - 1).trim();
    if (inner.isBlank()) {
      return Optional.empty();
    }
    String reaction = inner.toLowerCase(Locale.ROOT);
    if (!SUPPORTED_REACTIONS.contains(reaction)) {
      return Optional.empty();
    }
    return Optional.of(reaction);
  }

  private boolean shouldIncludeTool(AgentTool tool, IncomingMessage message) {
    if (GROUP_ONLY_TOOLS.contains(tool.name())) {
      return AgentTool.isGroupMessage(message);
    }
    return true;
  }

  private String extractResponseText(Response response) {
    if (response == null || response.output() == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (ResponseOutputItem item : response.output()) {
      if (item.message().isEmpty()) {
        continue;
      }
      ResponseOutputMessage message = item.message().get();
      for (ResponseOutputMessage.Content content : message.content()) {
        if (content.isOutputText()) {
          if (builder.length() > 0) {
            builder.append(' ');
          }
          builder.append(content.asOutputText().text());
        }
      }
    }
    return builder.toString().trim();
  }

  private String normalizeAssistantText(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JsonNode node = objectMapper.readTree(trimmed);
        JsonNode messageNode = node.get("message");
        if (messageNode != null && messageNode.isTextual()) {
          return messageNode.asText().trim();
        }
      } catch (Exception ignored) {
        return trimmed;
      }
    }
    return trimmed;
  }

  private record GeneratedImage(byte[] bytes, String filename) {}

  private List<GeneratedImage> extractGeneratedImages(Response response) {
    if (response == null || response.output() == null) {
      return List.of();
    }
    List<GeneratedImage> images = new ArrayList<>();
    log.debug(
        "Extracting images from response - total of {} items in response",
        response.output().size());
    for (ResponseOutputItem item : response.output()) {
      if (item.imageGenerationCall().isEmpty()) {
        log.debug("Skipping item - not image generation call");
        continue;
      }
      ResponseOutputItem.ImageGenerationCall call = item.imageGenerationCall().get();
      log.info("Got an image igeneration item {}", call.id());
      if (!call.status().equals(ResponseOutputItem.ImageGenerationCall.Status.COMPLETED)) {
        log.warn("Image generation failed(bad status), status was : {}", call.status());
        continue;
      }
      String result = call.result().orElse(null);
      if (result == null || result.isBlank()) {
        log.warn("Image generation failed(blank result): {}", call.id());
        continue;
      }
      byte[] bytes = decodeImageResult(result.trim());
      if (bytes == null || bytes.length == 0) {
        log.warn("Image generation failed(empty bytes): {}", call.id());
        continue;
      }
      String id = call.id();
      String filename = "generated-" + (id != null ? id : UUID.randomUUID()) + ".png";
      log.info("Generated image for {}: {}", id, filename);
      images.add(new GeneratedImage(bytes, filename));
    }
    return images;
  }

  private byte[] decodeImageResult(String result) {
    if (result == null || result.isBlank()) {
      log.warn("Decode failed: empty string");
      return null;
    }
    String trimmed = result.trim();
    if (trimmed.startsWith("data:")) {
      log.debug("Decoding image data(inline)");
      int comma = trimmed.indexOf(',');
      if (comma > 0 && comma < trimmed.length() - 1) {
        return decodeBase64(trimmed.substring(comma + 1));
      }
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      log.debug("Need to download image bytes");
      return downloadBytes(trimmed);
    }
    log.debug("Doing b64 decode");
    return decodeBase64(trimmed);
  }

  private byte[] decodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      log.warn("Failed to decode base64 image result", e);
      return null;
    }
  }

  private byte[] downloadBytes(String url) {
    try (InputStream input = new URL(url).openStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (Exception e) {
      log.warn("Failed to download image result {}", url, e);
      return null;
    }
  }

  private IncomingMessage parseWebhookMessage(JsonNode data) {
    if (data == null || data.isNull()) {
      return null;
    }
    String messageGuid = getText(data, "guid");
    String text = getText(data, "text");
    Boolean fromMe = getBoolean(data, "isFromMe");
    String service = getText(data.path("handle"), "service");
    String sender = getText(data.path("handle"), "address");
    Instant timestamp = parseTimestamp(data.path("dateCreated"));
    List<IncomingAttachment> attachments = parseAttachments(data.path("attachments"));
    String chatGuid = resolveChatGuid(data);
    Boolean isGroup = resolveIsGroup(data);
    return new IncomingMessage(
        chatGuid, messageGuid, text, fromMe, service, sender, isGroup, timestamp, attachments);
  }

  private String resolveChatGuid(JsonNode data) {
    JsonNode chats = data.path("chats");
    if (chats.isArray() && !chats.isEmpty()) {
      JsonNode chat = chats.get(0);
      String guid = getText(chat, "guid");
      if (guid != null) {
        return guid;
      }
    }
    return getText(data, "chatGuid");
  }

  private Boolean resolveIsGroup(JsonNode data) {
    JsonNode chats = data.path("chats");
    if (chats.isArray() && chats.size() > 1) {
      return true;
    }
    String groupTitle = getText(data, "groupTitle");
    if (groupTitle != null && !groupTitle.isBlank()) {
      return true;
    }
    return null;
  }

  private Instant parseTimestamp(JsonNode value) {
    if (value == null || value.isNull()) {
      return Instant.now();
    }
    if (value.isNumber()) {
      long epoch = value.asLong();
      if (epoch > 1_000_000_000_000L) {
        return Instant.ofEpochMilli(epoch);
      }
      return Instant.ofEpochSecond(epoch);
    }
    if (value.isTextual()) {
      String raw = value.asText();
      try {
        long epoch = Long.parseLong(raw);
        if (epoch > 1_000_000_000_000L) {
          return Instant.ofEpochMilli(epoch);
        }
        return Instant.ofEpochSecond(epoch);
      } catch (NumberFormatException ignored) {
        try {
          return Instant.parse(raw);
        } catch (Exception ignored2) {
          return Instant.now();
        }
      }
    }
    return Instant.now();
  }

  private List<IncomingAttachment> parseAttachments(JsonNode attachmentsNode) {
    if (attachmentsNode == null || !attachmentsNode.isArray()) {
      return List.of();
    }
    List<IncomingAttachment> attachments = new ArrayList<>();
    for (JsonNode attachmentNode : attachmentsNode) {
      String guid = getText(attachmentNode, "guid", "id");
      String mimeType = getText(attachmentNode, "mimeType", "mime_type");
      String filename = getText(attachmentNode, "filename", "transferName", "name");
      String url = getText(attachmentNode, "url", "path");
      String dataUrl = getText(attachmentNode, "dataUrl");
      String base64 = getText(attachmentNode, "base64");
      attachments.add(new IncomingAttachment(guid, mimeType, filename, url, dataUrl, base64));
    }
    return attachments;
  }

  private String getText(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      JsonNode value = node.get(field);
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

  private Boolean getBoolean(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isBoolean()) {
        return value.asBoolean();
      }
      if (value.isTextual()) {
        return Boolean.parseBoolean(value.asText());
      }
    }
    return null;
  }

  private List<ResponseInputItem> buildConversationInput(
      ConversationState state, IncomingMessage message) {
    List<ResponseInputItem> items = new ArrayList<>();
    boolean isGroupMessage = AgentTool.isGroupMessage(message);
    items.add(ResponseInputItem.ofEasyInputMessage(systemMessage(isGroupMessage, message)));
    items.add(ResponseInputItem.ofEasyInputMessage(developerMessage()));
    for (ConversationTurn turn : state.history()) {
      items.add(ResponseInputItem.ofEasyInputMessage(turn.toEasyInputMessage()));
    }
    items.add(ResponseInputItem.ofEasyInputMessage(userMessage(message)));
    return items;
  }

  private EasyInputMessage systemMessage(boolean groupMessage, IncomingMessage message) {
    AssistantResponsiveness responsiveness =
        getAssistantResponsiveness(message != null ? message.chatGuid() : null);
    String responsivenessInstruction =
        switch (responsiveness) {
          case LESS_RESPONSIVE ->
              "Responsiveness: less responsive. Assume "
                  + NO_RESPONSE_TEXT
                  + " unless explicitly addressed, and avoid jumping into group chat unless asked. No reacting unless directly asked. Don't engage in casual conversation, only reply to direct asks to you only. Do not assume a message was meant for you unless you're directly address by name.";
          case MORE_RESPONSIVE ->
              "Responsiveness: more responsive. Act like an active participant, reply when helpful, and use reactions more freely. ";
          case DEFAULT -> "";
        };
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.SYSTEM)
        .content(
            "You are a chat assistant for iMessage via BlueBubbles. "
                + (groupMessage
                    ? "Only respond when it is helpful or requested - this is a group message and not all messages are for you. You MUST ONLY respond if the message was directed to you or if your response will add useful and helpful information."
                    : "This is a one on one message with a user. You should respond to messages unless no reply is needed.")
                + "You can use reactions for quick acknowledgements and avoid spamming. "
                + "Never reply to your own messages."
                + responsivenessInstruction
                + "Use the "
                + MemoryGetAgentTool.TOOL_NAME
                + " tool when memory could improve your response (skip if no reply is needed or another tool is more appropriate). "
                + "Send a natural language query to the tool describing what information may help you answer. "
                + "If no reply is needed, output exactly "
                + NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private EasyInputMessage developerMessage() {
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.DEVELOPER)
        .content(
            "You may respond with plain text if that is sufficient. "
                + "Only call "
                + SendTextAgentTool.TOOL_NAME
                + " or "
                + SendReactionAgentTool.TOOL_NAME
                + " when you specifically need those actions; plain text is fine otherwise. "
                + "Use available tools for tasks like calendars or lookups when asked. "
                + "Use web_search for current info or external lookups when relevant. "
                + "If the user asks the assistant to be more or less responsive (especially in group chats), call "
                + AssistantResponsivenessAgentTool.TOOL_NAME
                + " to update the setting. "
                + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                + AssistantNameAgentTool.TOOL_NAME
                + " after they explicitly agree. "
                + "Use "
                + SearchConvoHistoryAgentTool.TOOL_NAME
                + " if you need to look up recent messages in this chat. "
                + "Use "
                + CurrentConversationInfoAgentTool.TOOL_NAME
                + " to see participants and metadata for the chat. "
                + "For group chats, you can rename the conversation or set a group icon when requested. "
                + "Use "
                + SendGiphyAgentTool.TOOL_NAME
                + " to reply with a GIF when it would be more expressive than text. "
                + "If a tool is unavailable, ask the user for clarification or say it is not configured. "
                + "For Google Calendar requests, use calendar tools like "
                + ListCalendarsAgentTool.TOOL_NAME
                + ", "
                + ListEventsAgentTool.TOOL_NAME
                + ", "
                + SearchEventsAgentTool.TOOL_NAME
                + ", "
                + GetEventAgentTool.TOOL_NAME
                + ", "
                + CreateEventAgentTool.TOOL_NAME
                + ", "
                + UpdateEventAgentTool.TOOL_NAME
                + ", "
                + DeleteEventAgentTool.TOOL_NAME
                + ", "
                + RespondToEventAgentTool.TOOL_NAME
                + ", "
                + GetFreebusyAgentTool.TOOL_NAME
                + ", "
                + ListColorsAgentTool.TOOL_NAME
                + ", and "
                + GetCurrentTimeAgentTool.TOOL_NAME
                + ". If the account is not linked, call "
                + ManageAccountsAgentTool.TOOL_NAME
                + " to get an auth_url and then exchange_code. "
                + "When the user shares information about themselves, or information that is helpful to remember "
                + "use the "
                + MemorySaveAgentTool.TOOL_NAME
                + " tool to persist that info. "
                + "Use the current conversation identity; do not ask for an identifier. "
                + "If asked to recall details about the user or prior interactions, or if memory could help answer a question, "
                + "call "
                + MemoryGetAgentTool.TOOL_NAME
                + " before responding. "
                + "If the user asks to correct or remove saved details and provides a memory_id, "
                + "call "
                + MemoryUpdateAgentTool.TOOL_NAME
                + " or "
                + MemoryDeleteAgentTool.TOOL_NAME
                + ". "
                + "If no reply is needed, output exactly "
                + NO_RESPONSE_TEXT
                + "."
                + "If the incoming message starts with 'Reacted ', 'Loved ', 'Liked ', 'Disliked ', 'Questioned ', 'Emphasized ', 'Laughed at ' - reply "
                + NO_RESPONSE_TEXT
                + " unless the reaction directly answers a question you (the assistant) asked or implies the user needs clarification. These are just reactions to your prior message and do not necessarily indicate a response is needed. Use your best judgement but err on the side of being less verbose and not responding by using "
                + NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private EasyInputMessage userMessage(IncomingMessage message) {
    List<ResponseInputContent> content = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Incoming message");
    if (message.sender() != null && !message.sender().isBlank()) {
      text.append(" from ").append(message.sender());
    }
    if (AgentTool.isGroupMessage(message)) {
      text.append(" (group chat)");
      if (message.sender() != null && !message.sender().isBlank()) {
        String knownName = getGlobalNameForSender(message.sender());
        if (knownName != null && !knownName.isBlank()) {
          text.append(" [sender name=").append(knownName).append("]");
        }
      }
    }
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      text.append(" [chatGuid=").append(message.chatGuid()).append("]");
    }
    if (message.messageGuid() != null && !message.messageGuid().isBlank()) {
      text.append(" [messageGuid=").append(message.messageGuid()).append("]");
    }
    text.append(": ");
    if (message.text() != null && !message.text().isBlank()) {
      text.append(message.text());
    } else {
      text.append("[no text]");
    }
    List<String> imageUrls = resolveImageUrls(message);
    List<ResponseInputFile> files = resolveAttachmentFiles(message);
    if (!imageUrls.isEmpty()) {
      text.append(" [").append(imageUrls.size()).append(" image(s) attached]");
    }
    if (!files.isEmpty()) {
      text.append(" [").append(files.size()).append(" file(s) attached]");
    }
    content.add(
        ResponseInputContent.ofInputText(
            ResponseInputText.builder().text(text.toString()).build()));
    int added = 0;
    for (String url : imageUrls) {
      if (added >= MAX_IMAGE_ATTACHMENTS) {
        break;
      }
      content.add(
          ResponseInputContent.ofInputImage(
              ResponseInputImage.builder()
                  .detail(ResponseInputImage.Detail.AUTO)
                  .imageUrl(url)
                  .build()));
      added++;
    }
    int addedFiles = 0;
    for (ResponseInputFile file : files) {
      if (addedFiles >= MAX_FILE_ATTACHMENTS) {
        break;
      }
      content.add(ResponseInputContent.ofInputFile(file));
      addedFiles++;
    }
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.USER)
        .contentOfResponseInputMessageContentList(content)
        .build();
  }

  private List<String> resolveImageUrls(IncomingMessage message) {
    if (message.attachments() == null || message.attachments().isEmpty()) {
      return List.of();
    }
    return message.attachments().stream()
        .map(this::resolveAttachmentImageUrl)
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
  }

  private Optional<String> resolveAttachmentImageUrl(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    String mimeType = attachment.mimeType();
    if (mimeType != null && !mimeType.startsWith("image/")) {
      return Optional.empty();
    }
    if (attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
      if (attachment.dataUrl().startsWith("data:image/")) {
        return Optional.of(attachment.dataUrl());
      }
      return Optional.empty();
    }
    if (attachment.base64() != null && mimeType != null && mimeType.startsWith("image/")) {
      return Optional.of("data:" + mimeType + ";base64," + attachment.base64());
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return Optional.of(attachment.url());
    }
    if (attachment.guid() != null && !attachment.guid().isBlank()) {
      try {
        Path path = bbHttpClientWrapper.getAttachment(attachment.guid());
        if (path != null) {
          byte[] bytes = java.nio.file.Files.readAllBytes(path);
          String resolvedMime = mimeType;
          if (resolvedMime == null || resolvedMime.isBlank()) {
            try {
              resolvedMime = java.nio.file.Files.probeContentType(path);
            } catch (Exception ignored) {
              // best effort mime detection
            }
          }
          if (resolvedMime == null || resolvedMime.isBlank()) {
            resolvedMime = "image/png";
          }
          String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
          try {
            java.nio.file.Files.deleteIfExists(path);
          } catch (Exception ignored) {
            // best effort cleanup
          }
          return Optional.of("data:" + resolvedMime + ";base64," + base64);
        }
      } catch (Exception e) {
        log.warn("Failed to download image attachment {}", attachment.guid(), e);
      }
    }
    return Optional.empty();
  }

  private List<ResponseInputFile> resolveAttachmentFiles(IncomingMessage message) {
    if (message.attachments() == null || message.attachments().isEmpty()) {
      return List.of();
    }
    return message.attachments().stream()
        .map(this::resolveAttachmentFile)
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
  }

  private Optional<ResponseInputFile> resolveAttachmentFile(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    String mimeType = attachment.mimeType();
    if (mimeType != null && mimeType.startsWith("image/")) {
      return Optional.empty();
    }
    String filename = attachment.filename();
    if (filename == null || filename.isBlank()) {
      filename = "attachment";
    }
    String fileData = null;
    if (attachment.guid() != null && !attachment.guid().isBlank()) {
      try {
        Path path = bbHttpClientWrapper.getAttachment(attachment.guid());
        if (path != null) {
          byte[] bytes = java.nio.file.Files.readAllBytes(path);
          fileData = java.util.Base64.getEncoder().encodeToString(bytes);
          try {
            java.nio.file.Files.deleteIfExists(path);
          } catch (Exception ignored) {
            // best effort cleanup
          }
          if (path.getFileName() != null && !path.getFileName().toString().isBlank()) {
            filename = path.getFileName().toString();
          }
        }
      } catch (Exception e) {
        log.warn("Failed to download attachment {}", attachment.guid(), e);
      }
    }
    if (fileData == null && attachment.base64() != null && !attachment.base64().isBlank()) {
      fileData = attachment.base64();
    }
    if (fileData == null && attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
      String dataUrl = attachment.dataUrl();
      int comma = dataUrl.indexOf(',');
      if (comma > 0) {
        fileData = dataUrl.substring(comma + 1);
      }
    }
    if (fileData == null || fileData.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ResponseInputFile.builder().fileData(fileData).filename(filename).build());
  }

  private void registerBuiltInTools() {
    registerTool(new SendTextAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SendReactionAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SearchConvoHistoryAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new CurrentConversationInfoAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new RenameConversationAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SetGroupIconAgentTool(bbHttpClientWrapper, openAiSupplier).getTool());
    registerTool(
        new SendGiphyAgentTool(bbHttpClientWrapper, giphyClient, openAiSupplier).getTool());
    registerTool(new AssistantResponsivenessAgentTool().getTool());
    registerTool(new AssistantNameAgentTool().getTool());
    registerTool(new MemorySaveAgentTool(mem0Client).getTool());
    registerTool(new MemoryGetAgentTool(mem0Client).getTool());
    registerTool(new MemoryUpdateAgentTool(mem0Client).getTool());
    registerTool(new MemoryDeleteAgentTool(mem0Client).getTool());
    registerTool(new ListCalendarsAgentTool(gcalClient).getTool());
    registerTool(new ListEventsAgentTool(gcalClient).getTool());
    registerTool(new SearchEventsAgentTool(gcalClient).getTool());
    registerTool(new GetEventAgentTool(gcalClient).getTool());
    registerTool(new CreateEventAgentTool(gcalClient).getTool());
    registerTool(new UpdateEventAgentTool(gcalClient).getTool());
    registerTool(new DeleteEventAgentTool(gcalClient).getTool());
    registerTool(new RespondToEventAgentTool(gcalClient).getTool());
    registerTool(new GetFreebusyAgentTool(gcalClient).getTool());
    registerTool(new ManageAccountsAgentTool(gcalClient).getTool());
    registerTool(new ListColorsAgentTool(gcalClient).getTool());
    registerTool(new GetCurrentTimeAgentTool(gcalClient).getTool());
  }

  //  public void registerTool(
  //      String name,
  //      String description,
  //      Map<String, Object> schema,
  //      java.util.function.BiFunction<IncomingMessage, JsonNode, String> handler) {
  //    registerTool(
  //        new AgentTool(
  //            name,
  //            description,
  //            jsonSchema(schema),
  //            false,
  //            (context, args) -> handler.apply(context.message(), args)));
  //  }

  private void registerTool(AgentTool tool) {
    tools.put(tool.name(), tool);
  }

  public AssistantResponsiveness getAssistantResponsiveness(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return AssistantResponsiveness.DEFAULT;
    }
    return assistantResponsivenessByChat.getOrDefault(chatGuid, AssistantResponsiveness.DEFAULT);
  }

  public void setAssistantResponsiveness(String chatGuid, AssistantResponsiveness responsiveness) {
    if (chatGuid == null || chatGuid.isBlank() || responsiveness == null) {
      return;
    }
    if (responsiveness == AssistantResponsiveness.DEFAULT) {
      assistantResponsivenessByChat.remove(chatGuid);
      return;
    }
    assistantResponsivenessByChat.put(chatGuid, responsiveness);
  }

  public String getGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return null;
    }
    return globalNamesBySender.get(sender);
  }

  public void setGlobalNameForSender(String sender, String name) {
    if (sender == null || sender.isBlank() || name == null || name.isBlank()) {
      return;
    }
    globalNamesBySender.put(sender, name.trim());
  }

  public void removeGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return;
    }
    globalNamesBySender.remove(sender);
  }

  public static FunctionTool.Parameters jsonSchema(Map<String, Object> schema) {
    Map<String, Object> normalized = new LinkedHashMap<>(schema);
    normalized.putIfAbsent("additionalProperties", false);
    if (!normalized.containsKey("required")) {
      Object propertiesObj = normalized.get("properties");
      if (propertiesObj instanceof Map<?, ?> propertiesMap) {
        List<String> required = new ArrayList<>();
        for (Object key : propertiesMap.keySet()) {
          required.add(String.valueOf(key));
        }
        normalized.put("required", required);
      }
    }
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, Object> entry : normalized.entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
    }
    return builder.build();
  }

  public static String getRequired(JsonNode args, String field) {
    JsonNode value = args.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing field: " + field);
    }
    return value.asText();
  }

  public static String getOptionalText(JsonNode args, String field) {
    JsonNode value = args.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText();
  }
}
