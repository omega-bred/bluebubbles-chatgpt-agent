package io.breland.bbagent.server.agent;

import static io.breland.bbagent.server.agent.AgentResponseHelper.parseReactionText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInfo;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.GeneratedImage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.assistant.AssistantResponsivenessAgentTool;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
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
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BBMessageAgent {

  public static final int MAX_HISTORY = 50;
  public static final String NO_RESPONSE_TEXT = "NO_RESPONSE";
  private static final int MAX_TOOL_LOOPS = 50;
  private static final int MAX_IMAGE_ATTACHMENTS = 4;
  private static final int MAX_FILE_ATTACHMENTS = 4;
  private static final int MAX_GENERATED_IMAGES = 1;
  public static final String IMESSAGE_SERVICE = "iMessage";

  public enum AssistantResponsiveness {
    DEFAULT,
    LESS_RESPONSIVE,
    MORE_RESPONSIVE,
    SILENT
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
  private static final List<String> REACTION_PREFIXES =
      List.of(
          "reacted ", "loved ", "liked ", "disliked ", "questioned ", "emphasized ", "laughed at ");

  @Getter private final ObjectMapper objectMapper;
  @Getter private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  private final AgentSettingsStore agentSettingsStore;
  private final AgentWorkflowProperties workflowProperties;
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;
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
      GiphyClient giphyClient,
      AgentSettingsStore agentSettingsStore,
      AgentWorkflowProperties workflowProperties,
      ObjectMapper objectMapper,
      @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mem0Client = mem0Client;
    this.gcalClient = gcalClient;
    this.giphyClient = giphyClient;
    this.agentSettingsStore = agentSettingsStore;
    this.workflowProperties = workflowProperties;
    this.objectMapper = objectMapper;
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    registerBuiltInTools();
  }

  BBMessageAgent(
      OpenAIClient openAIClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      GiphyClient giphyClient,
      AgentSettingsStore agentSettingsStore,
      AgentWorkflowProperties workflowProperties,
      @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.openAIClient = openAIClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mem0Client = mem0Client;
    this.gcalClient = gcalClient;
    this.giphyClient = giphyClient;
    this.agentSettingsStore = agentSettingsStore;
    this.workflowProperties = workflowProperties;
    this.objectMapper = new ObjectMapper();
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    registerBuiltInTools();
  }

  private ConversationState computeConversationState(String chatId, IncomingMessage message) {
    ConversationState stateToHydrate = new ConversationState();
    log.info("Hydrating conversation state for chat {}", chatId);
    try {
      var messages = bbHttpClientWrapper.getMessagesInChat(chatId);

      messages
          .reversed()
          .forEach(
              msg -> {
                if (msg.getText() != null && msg.getText().length() > 0) {
                  var turn =
                      msg.getIsFromMe() != null && msg.getIsFromMe() == true
                          ? ConversationTurn.assistant(
                              msg.getText(), Instant.ofEpochSecond(msg.getDateCreated()))
                          : ConversationTurn.user(
                              msg.getText(), Instant.ofEpochSecond(msg.getDateCreated()));
                  if (!msg.getGuid().equals(message.messageGuid())) {
                    if (!msg.getIsFromMe()) {
                      stateToHydrate.setLastProcessedMessageGuid(msg.getGuid());
                      if (msg.getHandle() != null && msg.getHandle().getAddress() != null) {
                        stateToHydrate.setLastProcessedMessageFingerprint(
                            IncomingMessage.create(msg).computeMessageFingerprint());
                      }
                    }
                    stateToHydrate.addTurn(turn);
                  }
                }
              });

      log.info(
          "Hydrated conversation state for chat {} got {} messages from history",
          chatId,
          stateToHydrate.history().size());
    } catch (Exception e) {
      log.warn("Failed to hydrate conversation state for chat {}", chatId, e);
    }
    return stateToHydrate;
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    if (!shouldProcess(message)) {
      log.debug("Dropping message {}", message);
      return;
    }
    log.info("Processing Message {}", message);
    ConversationState state =
        conversations.computeIfAbsent(
            message.chatGuid(), key -> this.computeConversationState(key, message));

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
      if (workflowProperties.useCadenceWorkflow()) {
        if (cadenceWorkflowLauncher == null) {
          log.error(
              "Cadence workflow launcher is not configured - but we should use cadence workflow; incorrectly dropping message {}",
              message);
          return;
        }
      }
    }
    String workflowId = resolveWorkflowId(message);
    AgentWorkflowContext workflowContext =
        new AgentWorkflowContext(
            workflowId, message.chatGuid(), message.messageGuid(), Instant.now());
    if (workflowProperties.useCadenceWorkflow()) {
      log.info("Responding via cadence workflow");
      CadenceMessageWorkflowRequest request =
          new CadenceMessageWorkflowRequest(workflowContext, message);
      WorkflowExecution execution = cadenceWorkflowLauncher.startWorkflow(request);
      state.setLatestWorkflowRunId(execution.getRunId());
      return;
    }
    log.info("Responding via inline workflow");
    runMessageWorkflow(state, message, workflowContext);
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
    if (isReactionMessage(message.text())) {
      return false;
    }
    AssistantResponsiveness responsiveness = getAssistantResponsiveness(message.chatGuid());
    if (responsiveness == AssistantResponsiveness.SILENT) {
      return isSilentInvocation(message.text());
    }
    return true;
  }

  private String resolveWorkflowId(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    // in cadecne - we will terminate an existing workflow with the same id
    // in both groups, and regular texts - we use the group chat as the main workflow id
    // such that new messages will cancel current ones.
    // this is useful if we have an active chat- and we want the response to kind of happen "all at
    // once"
    // instead of serially processing
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      return message.chatGuid();
    }
    log.warn("Message did not have a chat guid - this is unexpected");
    return UUID.randomUUID().toString();
  }

  private void runMessageWorkflow(
      ConversationState state, IncomingMessage message, AgentWorkflowContext workflowContext) {
    Response response = null;
    try {
      response = runAssistant(state, message, workflowContext);
    } catch (RuntimeException e) {
      log.warn("Workflow failed for {}", message, e);
    } finally {
      synchronized (state) {
        if (response != null) {
          Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
          state.addTurn(ConversationTurn.user(message.summaryForHistory(), timestamp));
        }
        state.setLastProcessedMessageGuid(message.messageGuid());
        state.setLastProcessedMessageFingerprint(message.computeMessageFingerprint());
        updateThreadContext(state, message);
      }
    }
  }

  public void runMessageWorkflowForCadence(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
      return;
    }
    ConversationState state =
        conversations.computeIfAbsent(
            message.chatGuid(), key -> computeConversationState(key, message));
    runMessageWorkflow(state, message, workflowContext);
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    if (workflowContext == null) {
      return true;
    }
    if (workflowContext.chatGuid() == null || workflowContext.chatGuid().isBlank()) {
      return true;
    }
    ConversationState state = conversations.get(workflowContext.chatGuid());
    if (state == null) {
      return true;
    }
    WorkflowInfo info = Workflow.getWorkflowInfo();
    if (info != null && info.getRunId() != null) {
      synchronized (state) {
        String latestWorkflowRunId = state.getLatestWorkflowRunId();

        // can be null until we persist state in a real db.
        if (latestWorkflowRunId != null && !latestWorkflowRunId.equals(info.getRunId())) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean isSilentInvocation(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    return trimmed.regionMatches(true, 0, "Chat", 0, 4);
  }

  static boolean isReactionMessage(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    if (trimmed.isBlank()) {
      return false;
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT);
    for (String prefix : REACTION_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  Response runAssistant(
      ConversationState state, IncomingMessage message, AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> inputItems = buildConversationInput(state.history(), message);
    log.trace("Getting response for {}", inputItems.toString());
    Response response = createResponse(inputItems, message, workflowContext);
    if (response == null) {
      log.warn("Got a null response for {}", message.text());
      return null;
    }
    log.debug(response.toString());
    boolean sentTextByTool = false;
    boolean sentReactionByTool = false;
    int loops = 0;
    while (loops < MAX_TOOL_LOOPS) {
      List<ResponseFunctionToolCall> toolCalls = AgentResponseHelper.extractFunctionCalls(response);
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
      toolContinuation.addAll(AgentResponseHelper.extractToolContextItems(response));
      toolContinuation.addAll(executeToolCalls(toolCalls, message, workflowContext));
      response = createResponse(toolContinuation, message, workflowContext);
      inputItems = toolContinuation;
      loops++;
    }
    String assistantText =
        AgentResponseHelper.normalizeAssistantText(
            objectMapper, AgentResponseHelper.extractResponseText(response));
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
      if (canSendResponses(workflowContext)) {
        sentImageByMultipart =
            bbHttpClientWrapper.sendMultipartMessage(message.chatGuid(), caption, attachments);
        if (sentImageByMultipart && caption != null && !caption.isBlank()) {
          sentTextByTool = true;
        }
      } else {
        log.info("Skipping multipart image send for outdated workflow {}", workflowContext);
      }
    }
    Optional<String> parsedReaction = parseReactionText(assistantText);
    if (parsedReaction.isPresent()) {
      String reaction = parsedReaction.get();
      if (sentReactionByTool) {
        log.debug("Skipping reaction text output since reaction tool already ran");
      } else if (canSendResponses(workflowContext)
          && bbHttpClientWrapper.sendReactionDirect(message, reaction)) {
        synchronized (state) {
          state.addTurn(ConversationTurn.assistant("[reaction: " + reaction + "]", Instant.now()));
        }
      } else {
        log.warn("Unable to send reaction for assistant text: {}", assistantText);
      }
      return response;
    }
    if (!assistantText.isBlank() && !NO_RESPONSE_TEXT.equalsIgnoreCase(assistantText.trim())) {
      log.info("Assistant reply text: {}", assistantText);
      if (!sentTextByTool && !sentImageByMultipart && canSendResponses(workflowContext)) {
        sendThreadAwareText(message, assistantText.trim());
      } else if (!canSendResponses(workflowContext)) {
        log.info("Skipping direct response send for outdated workflow {}", workflowContext);
      }
      if (canSendResponses(workflowContext)) {
        synchronized (state) {
          state.addTurn(ConversationTurn.assistant(assistantText.trim(), Instant.now()));
        }
      }
    } else {
      if (sentImageByMultipart) {
        if (canSendResponses(workflowContext)) {
          synchronized (state) {
            state.addTurn(ConversationTurn.assistant("[image]", Instant.now()));
          }
        }
      } else {
        log.info("No assistant reply generated");
      }
    }
    return response;
  }

  public void sendThreadAwareText(IncomingMessage message, String text) {
    if (message == null || text == null || text.isBlank()) {
      return;
    }
    String replyTarget = resolveThreadRootGuid(message);
    if (replyTarget == null || replyTarget.isBlank()) {
      bbHttpClientWrapper.sendTextDirect(message, text);
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(message.chatGuid());
    request.setMessage(text);
    request.setSelectedMessageGuid(replyTarget);
    bbHttpClientWrapper.sendTextDirect(request);
  }

  BBHttpClientWrapper getBbHttpClientWrapper() {
    return bbHttpClientWrapper;
  }

  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
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
            .model(ChatModel.GPT_5_2_CHAT_LATEST)
            .inputOfResponse(inputItems)
            .maxOutputTokens(1000)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
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

  public List<ResponseInputItem> executeToolCalls(
      List<ResponseFunctionToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> outputs = new ArrayList<>();
    for (ResponseFunctionToolCall toolCall : toolCalls) {
      outputs.add(runToolActivity(toolCall, message, workflowContext));
    }
    return outputs;
  }

  private ResponseInputItem runToolActivity(
      ResponseFunctionToolCall toolCall,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    log.info("Invoking tool {}", toolCall.name());
    AgentTool tool = tools.get(toolCall.name());
    String output;
    try {
      JsonNode args = objectMapper.readTree(toolCall.arguments());
      args = applyThreadReplyDefaults(toolCall.name(), args, message);
      if (tool == null) {
        output = "Unknown tool: " + toolCall.name();
      } else {
        output = tool.handler().apply(new ToolContext(this, message, workflowContext), args);
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
    return ResponseInputItem.ofFunctionCallOutput(toolOutput);
  }

  private JsonNode applyThreadReplyDefaults(
      String toolName, JsonNode args, IncomingMessage message) {
    if (toolName == null || args == null || message == null) {
      return args;
    }
    if (!SendTextAgentTool.TOOL_NAME.equals(toolName)) {
      return args;
    }
    if (args.hasNonNull("selectedMessageGuid")) {
      return args;
    }
    String replyTarget = resolveThreadRootGuid(message);
    if (replyTarget == null || replyTarget.isBlank()) {
      return args;
    }
    if (!(args instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode)) {
      return args;
    }
    objectNode.put("selectedMessageGuid", replyTarget);
    return objectNode;
  }

  private boolean shouldIncludeTool(AgentTool tool, IncomingMessage message) {
    if (GROUP_ONLY_TOOLS.contains(tool.name())) {
      return message.isGroup();
    }
    return true;
  }

  public List<GeneratedImage> extractGeneratedImages(Response response) {
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
      if (images.size() >= MAX_GENERATED_IMAGES) {
        break;
      }
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

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return;
    }
    String threadRootGuid = resolveThreadRootGuid(message);
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return;
    }
    List<String> imageUrls = resolveImageUrls(message);
    ConversationState.ThreadContext existing = state.getThreadContext(threadRootGuid);
    if ((imageUrls == null || imageUrls.isEmpty()) && existing != null) {
      imageUrls = existing.lastImageUrls();
    }
    String timestamp =
        message.timestamp() != null ? message.timestamp().toString() : Instant.now().toString();
    ConversationState.ThreadContext context =
        new ConversationState.ThreadContext(
            threadRootGuid,
            message.messageGuid(),
            message.text(),
            message.sender(),
            timestamp,
            imageUrls);
    state.recordThreadMessage(threadRootGuid, context);
  }

  private String resolveThreadRootGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      return message.threadOriginatorGuid();
    }
    return null;
  }

  public List<ResponseInputItem> buildConversationInput(
      List<ConversationTurn> history, IncomingMessage message) {
    List<ResponseInputItem> items = new ArrayList<>();
    boolean isGroupMessage = message.isGroup();
    items.add(ResponseInputItem.ofEasyInputMessage(systemMessage(isGroupMessage, message)));
    items.add(ResponseInputItem.ofEasyInputMessage(developerMessage()));
    if (history != null) {
      for (ConversationTurn turn : history) {
        items.add(ResponseInputItem.ofEasyInputMessage(turn.toEasyInputMessage()));
      }
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
              "Responsiveness: ALWAYS REPLY "
                  + NO_RESPONSE_TEXT
                  + " unless explicitly addressed, and do not issue any other response unless DIRECTLY ADDRESS. No reacting unless directly asked. Don't engage in casual conversation, only reply to direct asks. Do not assume a message was meant for you unless you're directly addressed by name.";
          case MORE_RESPONSIVE ->
              "Responsiveness: more responsive. Act like an active participant, reply when helpful, and use reactions more freely. ";
          case SILENT ->
              "Responsiveness: silent. Only respond when explicitly invoked with the activation prefix 'Chat' (case-insensitive).";
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
                + "iMessage does not support markdown - so do not use markdown semantics. You MUST constrain your output to plain text and emojis only."
                + "DO NOT USE ** FOR EMPHASIS - IT IS NOT SUPPORTED"
                + "Never reply to your own messages."
                + responsivenessInstruction
                + "Use the "
                + MemoryGetAgentTool.TOOL_NAME
                + " tool when memory could improve your response (skip if no reply is needed or another tool is more appropriate). "
                + " Always ask the memory tool before directly asking the user to see if memory already has the answer to your question. "
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
                + "All outgoing iMessage text must be plain text only. Do not use markdown or formatting markers such as **, __, backticks, or markdown lists. "
                + "Only call "
                + SendTextAgentTool.TOOL_NAME
                + " or "
                + SendReactionAgentTool.TOOL_NAME
                + " when you specifically need those actions; plain text is fine otherwise. "
                + "When sending a text, you may optionally apply an iMessage effect via the effect parameter, but use effects sparingly (e.g. happy_birthday for birthday wishes). "
                + "Use available tools for tasks like calendars or lookups when asked. "
                + "Use web_search for current info or external lookups when relevant. "
                + "If the user requests an image and has attached images, use those images as starting references for image generation. "
                + "If the user asks the assistant to be more or less responsive (especially in group chats), call "
                + AssistantResponsivenessAgentTool.TOOL_NAME
                + " to update the setting. The silent mode will only invoke responses when the message starts with 'Chat' (case-insensitive). "
                + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                + AssistantNameAgentTool.TOOL_NAME
                + " after they explicitly agree. "
                + "Use "
                + SearchConvoHistoryAgentTool.TOOL_NAME
                + " if you need to look up recent messages in this chat. "
                + "Use "
                + CurrentConversationInfoAgentTool.TOOL_NAME
                + " to see participants and metadata for the chat. "
                + "If the incoming message is part of a thread (replyToGuid or threadOriginatorGuid), reply in the same thread by setting selectedMessageGuid (and partIndex if provided). "
                + "Use "
                + GetThreadContextAgentTool.TOOL_NAME
                + " when asked about the last message or previously sent images in this thread. "
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
                + " to get an auth_url and have the user complete the OAuth flow in their browser. "
                + "If multiple calendar accounts are linked, pass account_key (the account id from manage_accounts list, or 'default') to the calendar tools to pick the right account; ask if ambiguous. "
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
                + ". "
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
    if (message.isGroup()) {
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
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      text.append(" [threadOriginatorGuid=").append(message.threadOriginatorGuid()).append("]");
    }
    if (resolveThreadRootGuid(message) != null) {
      text.append(" [threadReply=true]");
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
    registerTool(new GetThreadContextAgentTool(bbHttpClientWrapper).getTool());
  }

  private void registerTool(AgentTool tool) {
    tools.put(tool.name(), tool);
  }

  public AssistantResponsiveness getAssistantResponsiveness(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return AssistantResponsiveness.DEFAULT;
    }
    return agentSettingsStore
        .findAssistantResponsiveness(chatGuid)
        .orElse(AssistantResponsiveness.DEFAULT);
  }

  public void setAssistantResponsiveness(String chatGuid, AssistantResponsiveness responsiveness) {
    if (chatGuid == null || chatGuid.isBlank() || responsiveness == null) {
      return;
    }
    if (responsiveness == AssistantResponsiveness.DEFAULT) {
      agentSettingsStore.deleteAssistantResponsiveness(chatGuid);
      return;
    }
    agentSettingsStore.saveAssistantResponsiveness(chatGuid, responsiveness);
  }

  public String getGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return null;
    }
    return agentSettingsStore.findGlobalName(sender).orElse(null);
  }

  public void setGlobalNameForSender(String sender, String name) {
    if (sender == null || sender.isBlank() || name == null || name.isBlank()) {
      return;
    }
    agentSettingsStore.saveGlobalName(sender, name);
  }

  public void removeGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return;
    }
    agentSettingsStore.deleteGlobalName(sender);
  }
}
