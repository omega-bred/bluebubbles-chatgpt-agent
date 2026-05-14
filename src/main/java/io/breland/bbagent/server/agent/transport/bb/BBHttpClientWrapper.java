package io.breland.bbagent.server.agent.transport.bb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.breland.bbagent.generated.bluebubblesclient.ApiClient;
import io.breland.bbagent.generated.bluebubblesclient.api.*;
import io.breland.bbagent.generated.bluebubblesclient.model.*;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Slf4j
@Component
public class BBHttpClientWrapper {

  private static final Duration API_TIMEOUT = Duration.ofSeconds(10);
  private static final String ANY_DIRECT_CHAT_PREFIX = "any;-;";

  private final V1ContactApi contactApi;
  private ApiClient apiClient;
  private final V1MessageApi messageApi;
  private final V1AttachmentApi attachmentApi;
  private final V1ChatApi chatApi;
  private final V1OtherApi otherApi;
  private final V1ICloudApi icloudApi;

  private final String password;

  @Getter private final ObjectMapper objectMapper;

  @Autowired
  public BBHttpClientWrapper(
      @Value("${bluebubbles.basePath}") String basePath,
      @Value("${bluebubbles.password}") String password,
      ObjectMapper objectMapper) {
    this.password = password;
    this.apiClient = new ApiClient();
    this.apiClient.setBasePath(basePath);
    this.messageApi = new V1MessageApi(apiClient);
    this.contactApi = new V1ContactApi(apiClient);
    this.attachmentApi = new V1AttachmentApi(apiClient);
    this.chatApi = new V1ChatApi(apiClient);
    this.otherApi = new V1OtherApi(apiClient);
    this.icloudApi = new V1ICloudApi(apiClient);
    this.objectMapper = objectMapper;
  }

  public BBHttpClientWrapper(String password, V1MessageApi messageApi, V1ContactApi contactApi) {
    this(
        password,
        messageApi,
        contactApi,
        new V1ICloudApi(new ApiClient()),
        new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  public BBHttpClientWrapper(
      String password, V1MessageApi messageApi, V1ContactApi contactApi, V1ICloudApi icloudApi) {
    this(
        password,
        messageApi,
        contactApi,
        icloudApi,
        new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  private BBHttpClientWrapper(
      String password,
      V1MessageApi messageApi,
      V1ContactApi contactApi,
      V1ICloudApi icloudApi,
      ObjectMapper objectMapper) {
    this.password = password;
    this.apiClient = new ApiClient();
    this.messageApi = messageApi;
    this.contactApi = contactApi;
    this.attachmentApi = new V1AttachmentApi(apiClient);
    this.chatApi = new V1ChatApi(apiClient);
    this.otherApi = new V1OtherApi(apiClient);
    this.icloudApi = icloudApi;
    this.objectMapper = objectMapper;
  }

  public record AttachmentData(String filename, byte[] bytes) {}

  private static void requireSuccessfulResponse(Integer status, String message, String operation) {
    if (status != null
        && status == 200
        && message != null
        && message.toLowerCase().contains("success")) {
      return;
    }
    throw new IllegalStateException(
        "BlueBubbles " + operation + " failed with status=" + status + " message=" + message);
  }

  private static <T> T requirePresent(T value, String operation) {
    if (value == null) {
      throw new IllegalStateException("BlueBubbles " + operation + " returned no data");
    }
    return value;
  }

  public FindMyFriendLocation getFindMyLocation(String userId) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    ApiResponseFindMyFriendsLocations response =
        this.icloudApi.apiV1IcloudFindmyFriendsRefreshPost(password).block(API_TIMEOUT);
    response = requirePresent(response, "refresh Find My friends locations");
    requireSuccessfulResponse(
        response.getStatus(), response.getMessage(), "refresh Find My friends locations");
    return findFindMyLocation(response.getData(), userId).orElse(null);
  }

  private static Optional<FindMyFriendLocation> findFindMyLocation(
      List<FindMyFriendLocation> locations, String userId) {
    if (locations == null || locations.isEmpty()) {
      return Optional.empty();
    }
    String normalizedUserId = normalizeFindMyIdentifier(userId);
    String normalizedUserPhone = normalizePhoneIdentifier(userId);
    return locations.stream()
        .filter(Objects::nonNull)
        .filter(
            location ->
                matchesFindMyIdentifier(
                    location.getHandle(), normalizedUserId, normalizedUserPhone))
        .findFirst();
  }

  private static boolean matchesFindMyIdentifier(
      String candidate, String normalizedUserId, String normalizedUserPhone) {
    String normalizedCandidate = normalizeFindMyIdentifier(candidate);
    if (normalizedCandidate == null) {
      return false;
    }
    if (normalizedCandidate.equals(normalizedUserId)) {
      return true;
    }
    String normalizedCandidatePhone = normalizePhoneIdentifier(candidate);
    return normalizedCandidatePhone != null
        && normalizedUserPhone != null
        && normalizedCandidatePhone.equals(normalizedUserPhone);
  }

  private static String normalizeFindMyIdentifier(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return null;
    }
    if (normalized.startsWith("mailto:")) {
      normalized = normalized.substring("mailto:".length());
    } else if (normalized.startsWith("tel:")) {
      normalized = normalized.substring("tel:".length());
    }
    normalized = normalized.replaceAll("\\s+", "");
    return normalized.isBlank() ? null : normalized;
  }

  private static String normalizePhoneIdentifier(String value) {
    if (value == null) {
      return null;
    }
    String digits = value.replaceAll("\\D+", "");
    return digits.isBlank() ? null : digits;
  }

  public Path getAttachment(String attachmentGuid) {
    try {
      Path tempPath = Files.createTempFile("bb-attachment-", ".bin");
      AsynchronousFileChannel channel =
          AsynchronousFileChannel.open(
              tempPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
      this.attachmentApi
          .apiV1AttachmentAttachmentGuidDownloadGetWithResponseSpec(
              attachmentGuid, password, null, null, null, 0)
          .bodyToFlux(DataBuffer.class)
          .transform(
              data ->
                  DataBufferUtils.write(data, channel)
                      .doFinally(
                          signal -> {
                            try {
                              channel.close();
                            } catch (IOException ignored) {
                              // best effort cleanup
                            }
                          }))
          .blockLast(Duration.of(30, ChronoUnit.SECONDS));
      return tempPath;
    } catch (Exception e) {
      throw new RuntimeException("Failed to download attachment with guid " + attachmentGuid, e);
    }
  }

  public Message getMessage(String messageGuid) {
    ApiResponseMessage response =
        this.messageApi
            .apiV1MessageMessageGuidGet(messageGuid, password, "chats,participants")
            .block(API_TIMEOUT);
    response = requirePresent(response, "get message");
    requireSuccessfulResponse(response.getStatus(), response.getMessage(), "get message");
    return requirePresent(response.getData(), "get message");
  }

  public String uploadAttachment(Path path) {
    if (path == null) {
      return null;
    }
    if (attachmentApi == null) {
      log.warn("Attachment API not configured");
      return null;
    }
    try {
      ApiV1AttachmentUploadPost200Response response =
          attachmentApi
              .apiV1AttachmentUploadPost(path.toFile(), password)
              .block(Duration.of(30, ChronoUnit.SECONDS));
      return Optional.ofNullable(response)
          .filter(r1 -> r1.getStatus() != null && r1.getStatus() == 200)
          .map(ApiV1AttachmentUploadPost200Response::getData)
          .map(ApiV1AttachmentUploadPost200ResponseData::getPath)
          .orElse(null);
    } catch (Exception e) {
      log.warn("Failed to upload attachment {}", path, e);
      return null;
    }
  }

  public String uploadAttachment(String filename, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    String safeName = (filename == null || filename.isBlank()) ? "attachment" : filename;
    Path tempPath = null;
    try {
      String suffix = "";
      int dot = safeName.lastIndexOf('.');
      if (dot > 0 && dot < safeName.length() - 1) {
        suffix = safeName.substring(dot);
      }
      tempPath = Files.createTempFile("bb-attach-", suffix);
      Files.write(tempPath, bytes);
      return uploadAttachment(tempPath);
    } catch (IOException e) {
      log.warn("Failed to write attachment temp file {}", safeName, e);
      return null;
    } finally {
      if (tempPath != null) {
        try {
          Files.deleteIfExists(tempPath);
        } catch (IOException ignored) {
          // best effort cleanup
        }
      }
    }
  }

  public boolean sendMultipartMessage(
      String chatGuid, String message, List<AttachmentData> attachments) {
    if (chatGuid == null || chatGuid.isBlank()) {
      log.warn("Cannot send multipart message without chatGuid");
      return false;
    }
    chatGuid = normalizeDirectAnyChatGuid(chatGuid);
    log.info("Sending multipart message with chatGuid {} - message {}", chatGuid, message);
    List<Map<String, Object>> parts = new ArrayList<>();
    int partIndex = 0;
    if (message != null && !message.isBlank()) {
      Map<String, Object> textPart = new LinkedHashMap<>();
      textPart.put("partIndex", partIndex++);
      textPart.put("text", message);
      parts.add(textPart);
    }
    if (attachments != null) {
      for (AttachmentData attachment : attachments) {
        if (attachment == null || attachment.bytes() == null || attachment.bytes().length == 0) {
          continue;
        }
        String filename = attachment.filename() != null ? attachment.filename() : "attachment";
        String serverPath = uploadAttachment(filename, attachment.bytes());
        if (serverPath == null || serverPath.isBlank()) {
          continue;
        }
        Map<String, Object> attachmentPart = new LinkedHashMap<>();
        attachmentPart.put("partIndex", partIndex++);
        attachmentPart.put("attachment", serverPath);
        attachmentPart.put("name", filename);
        parts.add(attachmentPart);
      }
    }
    if (parts.isEmpty()) {
      log.warn("No parts to send for multipart message");
      return false;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("chatGuid", chatGuid);
    body.put("parts", parts);
    try {
      messageApi.apiV1MessageMultipartPost(password, body).block(API_TIMEOUT);
      log.info("Sent multipart message to {}", chatGuid);
      return true;
    } catch (Exception e) {
      log.warn("Failed to send multipart message", e);
      return false;
    }
  }

  public List<Message> searchConversationHistory(
      String chatGuid, String query, Integer limit, Integer offset) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return null;
    }
    ApiV1MessageQueryPostRequest.Builder requestBuilder =
        ApiV1MessageQueryPostRequest.builder()
            .chatGuid(chatGuid)
            .sort(ApiV1MessageQueryPostRequest.SortEnum.DESC)
            .after(Instant.now().minus(30, ChronoUnit.DAYS).getEpochSecond())
            .offset(offset != null && offset >= 0 ? offset : 0)
            .limit(limit != null && limit > 0 ? limit : 20)
            .with(Set.of(ApiV1MessageQueryPostRequest.WithEnum.HANDLE));

    List<WhereClause> whereClauses = new ArrayList<>();

    if (query != null && !query.isBlank()) {
      whereClauses.add(
          WhereClause.builder()
              .statement("message.text LIKE :text")
              .args(Map.of("text", "%" + query + "%"))
              .build());
    }
    requestBuilder.where(whereClauses);

    ApiV1MessageQueryPost200Response response =
        this.messageApi
            .apiV1MessageQueryPost(password, requestBuilder.build())
            .block(Duration.of(120, ChronoUnit.SECONDS));
    response = requirePresent(response, "search conversation history");
    requireSuccessfulResponse(
        response.getStatus(), response.getMessage(), "search conversation history");
    return requirePresent(response.getData(), "search conversation history");
  }

  public Chat getConversationInfo(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return null;
    }
    ApiResponseGetChat chat =
        chatApi.apiV1ChatChatGuidGet(chatGuid, password, "participants").block(API_TIMEOUT);
    chat = requirePresent(chat, "get conversation info");
    requireSuccessfulResponse(chat.getStatus(), chat.getMessage(), "get conversation info");
    return requirePresent(chat.getData(), "get conversation info");
  }

  public boolean renameConversation(String chatGuid, String displayName) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return false;
    }
    ApiResponseUpdateChat result =
        chatApi
            .apiV1ChatChatGuidPut(
                chatGuid,
                password,
                ApiV1ChatChatGuidPutRequest.builder().displayName(displayName).build())
            .block(API_TIMEOUT);
    result = requirePresent(result, "rename conversation");
    requireSuccessfulResponse(result.getStatus(), result.getMessage(), "rename conversation");
    return true;
  }

  public boolean setConversationIcon(String chatGuid, Path iconPath) {
    if (chatGuid == null || chatGuid.isBlank() || iconPath == null) {
      return false;
    }
    try {
      Map<String, Object> pathParams = new LinkedHashMap<>();
      pathParams.put("guid", chatGuid);
      MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
      queryParams.putAll(apiClient.parameterToMultiValueMap(null, "password", password));
      HttpHeaders headerParams = new HttpHeaders();
      MultiValueMap<String, String> cookieParams = new LinkedMultiValueMap<>();
      MultiValueMap<String, Object> formParams = new LinkedMultiValueMap<>();
      formParams.add("icon", new FileSystemResource(iconPath.toFile()));
      List<MediaType> accept = apiClient.selectHeaderAccept(new String[] {"application/json"});
      MediaType contentType =
          apiClient.selectHeaderContentType(new String[] {"multipart/form-data"});
      JsonNode response =
          apiClient
              .invokeAPI(
                  "/api/v1/chat/{guid}/icon",
                  org.springframework.http.HttpMethod.POST,
                  pathParams,
                  queryParams,
                  null,
                  headerParams,
                  cookieParams,
                  formParams,
                  accept,
                  contentType,
                  new String[] {},
                  new ParameterizedTypeReference<JsonNode>() {})
              .bodyToMono(JsonNode.class)
              .block(API_TIMEOUT);
      log.debug("Set conversation icon response: {}", response);
      return true;
    } catch (Exception e) {
      log.warn("Failed to set conversation icon {}", chatGuid, e);
      return false;
    }
  }

  public void sendTextDirect(IncomingMessage message, String text) {
    if (message.chatGuid() == null || message.chatGuid().isBlank()) {
      log.warn("Cannot send message without chatGuid");
      return;
    }
    this.sendTextDirect(normalizeDirectAnyChatGuid(message.chatGuid(), message.service()), text);
  }

  public void sendTextDirect(ApiV1MessageTextPostRequest request) {
    try {
      request.setChatGuid(normalizeDirectAnyChatGuid(request.getChatGuid()));
      applyTextFormatting(request);
      log.info("Attempting to send direct text message {}", request.toString());
      messageApi.apiV1MessageTextPost(password, request).block(API_TIMEOUT);
      log.info("Sent direct message to {}", request.getChatGuid());
    } catch (Exception e) {
      log.warn("Failed to send direct message", e);
    }
  }

  private void applyTextFormatting(ApiV1MessageTextPostRequest request) {
    if (request == null) {
      return;
    }
    String message = request.getMessage();
    if (message == null || message.isBlank()) {
      return;
    }
    if (request.getTextFormatting() != null && !request.getTextFormatting().isEmpty()) {
      return;
    }
    TextFormattingParser.Result parsed = TextFormattingParser.parse(message);
    if (parsed.formatting().isEmpty()) {
      return;
    }
    request.setMessage(parsed.text());
    request.setTextFormatting(parsed.formatting());
    String method = request.getMethod();
    if (method == null || method.isBlank() || !"private-api".equalsIgnoreCase(method.trim())) {
      request.setMethod("private-api");
    }
  }

  public void sendTextDirect(String chatGuid, String text) {
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.chatGuid(chatGuid);
    request.tempGuid(UUID.randomUUID().toString());
    request.message(text);
    this.sendTextDirect(request);
  }

  public boolean sendReactionDirect(IncomingMessage message, String reaction) {
    if (message.chatGuid() == null || message.chatGuid().isBlank()) {
      log.warn("Cannot send reaction without chatGuid");
      return false;
    }
    if (message.messageGuid() == null || message.messageGuid().isBlank()) {
      log.warn("Cannot send reaction without messageGuid");
      return false;
    }

    ApiV1MessageReactPostRequest request = new ApiV1MessageReactPostRequest();
    request.reaction(reaction);
    request.setChatGuid(normalizeDirectAnyChatGuid(message.chatGuid(), message.service()));
    request.setSelectedMessageGuid(message.messageGuid());
    return this.sendReactionDirect(request);
  }

  public boolean sendReactionDirect(ApiV1MessageReactPostRequest request) {
    request.setChatGuid(normalizeDirectAnyChatGuid(request.getChatGuid()));
    if (!BBMessageAgent.SUPPORTED_REACTIONS.contains(request.getReaction())) {
      log.warn("Unsupported reaction {}", request.getReaction());
      return false;
    }
    try {
      messageApi.apiV1MessageReactPost(password, request).block(API_TIMEOUT);
      log.info("Sent reaction {} to {}", request.getReaction(), request.getChatGuid());
      return true;
    } catch (Exception e) {
      log.warn("Failed to send reaction", e);
      return false;
    }
  }

  static String normalizeDirectAnyChatGuid(String chatGuid) {
    return normalizeDirectAnyChatGuid(chatGuid, BBMessageAgent.IMESSAGE_SERVICE);
  }

  static String normalizeDirectAnyChatGuid(String chatGuid, String service) {
    if (chatGuid == null || !chatGuid.startsWith(ANY_DIRECT_CHAT_PREFIX)) {
      return chatGuid;
    }
    String resolvedService =
        service == null || service.isBlank() || "any".equalsIgnoreCase(service)
            ? BBMessageAgent.IMESSAGE_SERVICE
            : service;
    return resolvedService + chatGuid.substring("any".length());
  }

  public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(String chatGuid) {
    ApiV1ChatChatGuidMessageGet200Response response =
        this.chatApi
            .apiV1ChatChatGuidMessageGet(
                chatGuid, password, "handle", null, null, null, 100, "DESC")
            .block(API_TIMEOUT);
    response = requirePresent(response, "get messages in chat");
    return requirePresent(response.getData(), "get messages in chat");
  }

  public boolean ping() {
    try {
      this.otherApi.apiV1PingGet(password).block(API_TIMEOUT);
      return true;
    } catch (Exception e) {
      log.warn("Failed to ping", e);
      return false;
    }
  }

  public IcloudAccountInfo getAccount() {
    ApiResponseIcloudAccount account =
        this.icloudApi.apiV1IcloudAccountGet(password).block(API_TIMEOUT);
    account = requirePresent(account, "get iCloud account");
    requireSuccessfulResponse(account.getStatus(), account.getMessage(), "get iCloud account");
    return requirePresent(account.getData(), "get iCloud account");
  }
}
