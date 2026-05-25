package io.breland.bbagent.server.agent.transport.bb;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.breland.bbagent.generated.bluebubblesclient.ApiClient;
import io.breland.bbagent.generated.bluebubblesclient.api.*;
import io.breland.bbagent.generated.bluebubblesclient.model.*;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeoutException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Slf4j
@Component
public class BBHttpClientWrapper {

  private static final Duration DEFAULT_API_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration DIRECT_SEND_CONFIRMATION_DELAY = Duration.ofSeconds(5);
  private static final Duration DIRECT_SEND_PING_TIMEOUT = Duration.ofSeconds(5);
  private static final int DIRECT_SEND_MAX_ATTEMPTS = 3;
  private static final int DIRECT_SEND_PING_ATTEMPTS = 2;
  private static final Duration DIRECT_SEND_MATCH_WINDOW_SKEW = Duration.ofSeconds(10);
  private static final String ANY_DIRECT_CHAT_PREFIX = "any;-;";

  private final V1ContactApi contactApi;
  private ApiClient apiClient;
  private final V1MessageApi messageApi;
  private final V1AttachmentApi attachmentApi;
  private final V1ChatApi chatApi;
  private final V1OtherApi otherApi;
  private final V1ICloudApi icloudApi;

  private final String password;
  private final Duration apiTimeout;
  private final @Nullable OperationalMetricsService operationalMetricsService;

  @Getter private final ObjectMapper objectMapper;

  @Autowired
  public BBHttpClientWrapper(
      @Value("${bluebubbles.basePath}") String basePath,
      @Value("${bluebubbles.password}") String password,
      @Value("${bluebubbles.request-timeout-seconds:30}") long requestTimeoutSeconds,
      ObjectMapper objectMapper,
      @Nullable OperationalMetricsService operationalMetricsService) {
    this.password = password;
    this.apiTimeout = normalizedTimeout(requestTimeoutSeconds);
    this.apiClient = new ApiClient();
    this.apiClient.setBasePath(basePath);
    this.messageApi = new V1MessageApi(apiClient);
    this.contactApi = new V1ContactApi(apiClient);
    this.attachmentApi = new V1AttachmentApi(apiClient);
    this.chatApi = new V1ChatApi(apiClient);
    this.otherApi = new V1OtherApi(apiClient);
    this.icloudApi = new V1ICloudApi(apiClient);
    this.objectMapper = objectMapper;
    this.operationalMetricsService = operationalMetricsService;
  }

  public BBHttpClientWrapper(String password, V1MessageApi messageApi, V1ContactApi contactApi) {
    this(password, messageApi, contactApi, new V1ICloudApi(new ApiClient()));
  }

  public BBHttpClientWrapper(
      String password, V1MessageApi messageApi, V1ContactApi contactApi, V1ICloudApi icloudApi) {
    this(
        password,
        messageApi,
        contactApi,
        icloudApi,
        new ObjectMapper().registerModule(new JavaTimeModule()),
        null);
  }

  public BBHttpClientWrapper(
      String password,
      V1MessageApi messageApi,
      V1ContactApi contactApi,
      V1ICloudApi icloudApi,
      OperationalMetricsService operationalMetricsService) {
    this(
        password,
        messageApi,
        contactApi,
        icloudApi,
        new ObjectMapper().registerModule(new JavaTimeModule()),
        operationalMetricsService);
  }

  private BBHttpClientWrapper(
      String password,
      V1MessageApi messageApi,
      V1ContactApi contactApi,
      V1ICloudApi icloudApi,
      ObjectMapper objectMapper,
      @Nullable OperationalMetricsService operationalMetricsService) {
    this.password = password;
    this.apiTimeout = DEFAULT_API_TIMEOUT;
    this.apiClient = new ApiClient();
    this.messageApi = messageApi;
    this.contactApi = contactApi;
    this.attachmentApi = new V1AttachmentApi(apiClient);
    this.chatApi = new V1ChatApi(apiClient);
    this.otherApi = new V1OtherApi(apiClient);
    this.icloudApi = icloudApi;
    this.objectMapper = objectMapper;
    this.operationalMetricsService = operationalMetricsService;
  }

  public record AttachmentData(String filename, byte[] bytes) {}

  public record PollSendOption(String text, String optionIdentifier) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record MultipartMessageRequest(String chatGuid, List<MultipartMessagePart> parts) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record MultipartMessagePart(
      Integer partIndex, String text, String attachment, String name) {
    static MultipartMessagePart text(int partIndex, String text) {
      return new MultipartMessagePart(partIndex, text, null, null);
    }

    static MultipartMessagePart attachment(int partIndex, String attachment, String name) {
      return new MultipartMessagePart(partIndex, null, attachment, name);
    }
  }

  protected Duration directSendConfirmationDelay() {
    return DIRECT_SEND_CONFIRMATION_DELAY;
  }

  private static Duration normalizedTimeout(long timeoutSeconds) {
    return Duration.ofSeconds(Math.max(1, timeoutSeconds));
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private static boolean isTimeout(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void requireSuccessfulResponse(Integer status, String message, String operation) {
    if (status != null && status == 200 && StringUtils.containsIgnoreCase(message, "success")) {
      return;
    }
    throw new IllegalStateException(
        "BlueBubbles " + operation + " failed with status=" + status + " message=" + message);
  }

  private static void requireOkStatus(Integer status, String message, String operation) {
    if (status != null && status == 200) {
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

  private <T> T measuredOperation(String operation, OperationSupplier<T> supplier) {
    long startedNanos = System.nanoTime();
    try {
      T result = supplier.get();
      recordOperationMetric(operation, true, null, startedNanos);
      return result;
    } catch (RuntimeException e) {
      recordOperationMetric(
          operation, false, OperationalMetricsService.failureType(e), startedNanos);
      throw e;
    }
  }

  private boolean measuredBooleanOperation(String operation, OperationBooleanSupplier supplier) {
    long startedNanos = System.nanoTime();
    boolean success = false;
    String failureType = null;
    try {
      success = supplier.getAsBoolean();
      failureType = success ? null : "false_result";
      return success;
    } catch (RuntimeException e) {
      failureType = OperationalMetricsService.failureType(e);
      throw e;
    } finally {
      recordOperationMetric(operation, success, failureType, startedNanos);
    }
  }

  private void recordOperationMetric(
      String operation, boolean success, @Nullable String failureType, long startedNanos) {
    if (operationalMetricsService == null) {
      return;
    }
    operationalMetricsService.recordBlueBubblesOperation(
        operation, success, failureType, Duration.ofNanos(System.nanoTime() - startedNanos));
  }

  @FunctionalInterface
  private interface OperationSupplier<T> {
    T get();
  }

  @FunctionalInterface
  private interface OperationBooleanSupplier {
    boolean getAsBoolean();
  }

  public FindMyFriendLocation getFindMyLocation(String userId) {
    if (StringUtils.isBlank(userId)) {
      return null;
    }
    return getFindMyLocation(List.of(userId));
  }

  public FindMyFriendLocation getFindMyLocation(Collection<String> userIds) {
    List<String> candidates =
        userIds == null ? List.of() : userIds.stream().filter(StringUtils::isNotBlank).toList();
    if (candidates.isEmpty()) {
      return null;
    }
    return measuredOperation(
        "refresh_find_my_locations",
        () -> {
          ApiResponseFindMyFriendsLocations response =
              this.icloudApi.apiV1IcloudFindmyFriendsRefreshPost(password).block(apiTimeout);
          response = requirePresent(response, "refresh Find My friends locations");
          requireSuccessfulResponse(
              response.getStatus(), response.getMessage(), "refresh Find My friends locations");
          return findFindMyLocation(response.getData(), candidates).orElse(null);
        });
  }

  private static Optional<FindMyFriendLocation> findFindMyLocation(
      List<FindMyFriendLocation> locations, Collection<String> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Optional.empty();
    }
    return userIds.stream()
        .map(userId -> findFindMyLocation(locations, userId))
        .flatMap(Optional::stream)
        .findFirst();
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
    String normalized =
        AgentAccountIdentifiers.stripAddressScheme(value).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return null;
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

  public List<String> getContactAddressesFor(String address) {
    if (StringUtils.isBlank(address)) {
      return List.of();
    }
    return measuredOperation(
        "get_contacts",
        () -> {
          ApiV1ContactGet200Response response =
              contactApi.apiV1ContactGet(password).block(apiTimeout);
          response = requirePresent(response, "get contacts");
          requireSuccessfulResponse(response.getStatus(), response.getMessage(), "get contacts");
          if (response.getData() == null || response.getData().isEmpty()) {
            return List.of();
          }
          LinkedHashSet<String> matches = new LinkedHashSet<>();
          for (Contact contact : response.getData()) {
            List<String> contactAddresses = contactAddresses(contact);
            boolean containsAddress =
                contactAddresses.stream()
                    .anyMatch(candidate -> AgentAccountIdentifiers.equivalent(candidate, address));
            if (containsAddress) {
              matches.addAll(contactAddresses);
            }
          }
          return List.copyOf(matches);
        });
  }

  private static List<String> contactAddresses(Contact contact) {
    if (contact == null) {
      return List.of();
    }
    LinkedHashSet<String> addresses = new LinkedHashSet<>();
    addAddressEntries(addresses, contact.getPhoneNumbers());
    addAddressEntries(addresses, contact.getEmails());
    return List.copyOf(addresses);
  }

  private static void addAddressEntries(
      LinkedHashSet<String> addresses, List<AddressEntry> entries) {
    if (entries == null) {
      return;
    }
    entries.stream()
        .filter(Objects::nonNull)
        .map(AddressEntry::getAddress)
        .filter(StringUtils::isNotBlank)
        .map(String::trim)
        .forEach(addresses::add);
  }

  public Path getAttachment(String attachmentGuid) {
    long startedNanos = System.nanoTime();
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
      recordOperationMetric("download_attachment", true, null, startedNanos);
      return tempPath;
    } catch (Exception e) {
      recordOperationMetric(
          "download_attachment", false, OperationalMetricsService.failureType(e), startedNanos);
      throw new RuntimeException("Failed to download attachment with guid " + attachmentGuid, e);
    }
  }

  public Message getMessage(String messageGuid) {
    return measuredOperation(
        "get_message",
        () -> {
          ApiResponseMessage response =
              this.messageApi
                  .apiV1MessageMessageGuidGet(messageGuid, password, "chats,participants")
                  .block(apiTimeout);
          response = requirePresent(response, "get message");
          requireSuccessfulResponse(response.getStatus(), response.getMessage(), "get message");
          return requirePresent(response.getData(), "get message");
        });
  }

  public String uploadAttachment(Path path) {
    if (path == null) {
      return null;
    }
    if (attachmentApi == null) {
      log.warn("Attachment API not configured");
      return null;
    }
    long startedNanos = System.nanoTime();
    try {
      ApiV1AttachmentUploadPost200Response response =
          attachmentApi
              .apiV1AttachmentUploadPost(path.toFile(), password)
              .block(Duration.of(30, ChronoUnit.SECONDS));
      String uploadedPath =
          Optional.ofNullable(response)
              .filter(r1 -> r1.getStatus() != null && r1.getStatus() == 200)
              .map(ApiV1AttachmentUploadPost200Response::getData)
              .map(ApiV1AttachmentUploadPost200ResponseData::getPath)
              .orElse(null);
      recordOperationMetric(
          "upload_attachment",
          StringUtils.isNotBlank(uploadedPath),
          "empty_response",
          startedNanos);
      return uploadedPath;
    } catch (Exception e) {
      log.warn("Failed to upload attachment {}", path, e);
      recordOperationMetric(
          "upload_attachment", false, OperationalMetricsService.failureType(e), startedNanos);
      return null;
    }
  }

  public String uploadAttachment(String filename, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    String safeName = StringUtils.defaultIfBlank(filename, "attachment");
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
    if (StringUtils.isBlank(chatGuid)) {
      log.warn("Cannot send multipart message without chatGuid");
      return false;
    }
    String normalizedChatGuid = normalizeDirectAnyChatGuid(chatGuid);
    log.info(
        "Sending multipart message with chatGuid {} - message {}", normalizedChatGuid, message);
    List<MultipartMessagePart> parts = new ArrayList<>();
    int partIndex = 0;
    if (StringUtils.isNotBlank(message)) {
      parts.add(MultipartMessagePart.text(partIndex++, message));
    }
    if (attachments != null) {
      for (AttachmentData attachment : attachments) {
        if (attachment == null || attachment.bytes() == null || attachment.bytes().length == 0) {
          continue;
        }
        String filename = StringUtils.defaultIfBlank(attachment.filename(), "attachment");
        String serverPath = uploadAttachment(filename, attachment.bytes());
        if (StringUtils.isBlank(serverPath)) {
          continue;
        }
        parts.add(MultipartMessagePart.attachment(partIndex++, serverPath, filename));
      }
    }
    if (parts.isEmpty()) {
      log.warn("No parts to send for multipart message");
      return false;
    }
    MultipartMessageRequest body = new MultipartMessageRequest(normalizedChatGuid, parts);
    long startedNanos = System.nanoTime();
    try {
      messageApi.apiV1MessageMultipartPost(password, body).block(apiTimeout);
      log.info("Sent multipart message to {}", normalizedChatGuid);
      recordOperationMetric("send_multipart_message", true, null, startedNanos);
      return true;
    } catch (Exception e) {
      log.warn("Failed to send multipart message", e);
      recordOperationMetric(
          "send_multipart_message", false, OperationalMetricsService.failureType(e), startedNanos);
      return false;
    }
  }

  public List<Message> searchConversationHistory(
      String chatGuid, String query, Integer limit, Integer offset) {
    if (StringUtils.isBlank(chatGuid)) {
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

    if (StringUtils.isNotBlank(query)) {
      whereClauses.add(
          WhereClause.builder()
              .statement("message.text LIKE :text")
              .args(Map.of("text", "%" + query + "%"))
              .build());
    }
    requestBuilder.where(whereClauses);

    return measuredOperation(
        "search_conversation_history",
        () -> {
          ApiV1MessageQueryPost200Response response =
              this.messageApi
                  .apiV1MessageQueryPost(password, requestBuilder.build())
                  .block(Duration.of(120, ChronoUnit.SECONDS));
          response = requirePresent(response, "search conversation history");
          requireSuccessfulResponse(
              response.getStatus(), response.getMessage(), "search conversation history");
          return requirePresent(response.getData(), "search conversation history");
        });
  }

  public JsonNode sendPollJson(String chatGuid, String title, List<PollSendOption> options) {
    if (StringUtils.isBlank(chatGuid)) {
      throw new IllegalArgumentException("Cannot send poll without chatGuid");
    }
    List<io.breland.bbagent.generated.bluebubblesclient.model.PollSendOption> optionPayloads =
        options == null
            ? List.of()
            : options.stream()
                .filter(Objects::nonNull)
                .filter(option -> StringUtils.isNotBlank(option.text()))
                .map(BBHttpClientWrapper::toGeneratedPollSendOption)
                .toList();
    if (optionPayloads.size() < 2) {
      throw new IllegalArgumentException("Polls require at least two non-empty options");
    }
    ApiV1MessagePollPostRequest request =
        ApiV1MessagePollPostRequest.builder()
            .chatGuid(normalizeDirectAnyChatGuid(chatGuid))
            .title(StringUtils.defaultIfBlank(StringUtils.trim(title), "Poll"))
            .options(optionPayloads)
            .build();
    return measuredOperation(
        "send_poll",
        () -> {
          ApiResponseSendPoll response =
              messageApi.apiV1MessagePollPost(request, password).block(apiTimeout);
          response = requirePresent(response, "send poll");
          requireOkStatus(response.getStatus(), response.getMessage(), "send poll");
          return objectMapper.valueToTree(requirePresent(response.getData(), "send poll"));
        });
  }

  public JsonNode readPollJson(String messageGuid) {
    return objectMapper.valueToTree(readPoll(messageGuid));
  }

  public PollData readPoll(String messageGuid) {
    if (StringUtils.isBlank(messageGuid)) {
      throw new IllegalArgumentException("Cannot read poll without messageGuid");
    }
    return measuredOperation(
        "read_poll",
        () -> {
          ApiResponsePoll response =
              messageApi.apiV1MessageMessageGuidPollGet(messageGuid, password).block(apiTimeout);
          response = requirePresent(response, "read poll");
          requireOkStatus(response.getStatus(), response.getMessage(), "read poll");
          return requirePresent(response.getData(), "read poll");
        });
  }

  private static io.breland.bbagent.generated.bluebubblesclient.model.PollSendOption
      toGeneratedPollSendOption(PollSendOption option) {
    return io.breland.bbagent.generated.bluebubblesclient.model.PollSendOption.builder()
        .text(option.text().trim())
        .optionIdentifier(StringUtils.trimToNull(option.optionIdentifier()))
        .build();
  }

  public Chat getConversationInfo(String chatGuid) {
    if (StringUtils.isBlank(chatGuid)) {
      return null;
    }
    return measuredOperation(
        "get_conversation_info",
        () -> {
          ApiResponseGetChat chat =
              chatApi.apiV1ChatChatGuidGet(chatGuid, password, "participants").block(apiTimeout);
          chat = requirePresent(chat, "get conversation info");
          requireSuccessfulResponse(chat.getStatus(), chat.getMessage(), "get conversation info");
          return requirePresent(chat.getData(), "get conversation info");
        });
  }

  public JsonNode getConversationInfoJson(String chatGuid) {
    if (StringUtils.isBlank(chatGuid)) {
      return null;
    }
    return measuredOperation(
        "get_conversation_info_json",
        () -> {
          JsonNode response =
              chatApi
                  .apiV1ChatChatGuidGetWithResponseSpec(chatGuid, password, "participants")
                  .bodyToMono(JsonNode.class)
                  .block(apiTimeout);
          response = requirePresent(response, "get conversation info");
          requireSuccessfulResponse(
              response.path("status").isInt() ? response.path("status").asInt() : null,
              response.path("message").asText(null),
              "get conversation info");
          return requirePresent(response.get("data"), "get conversation info");
        });
  }

  public boolean renameConversation(String chatGuid, String displayName) {
    if (StringUtils.isBlank(chatGuid)) {
      return false;
    }
    return measuredBooleanOperation(
        "rename_conversation",
        () -> {
          ApiResponseUpdateChat result =
              chatApi
                  .apiV1ChatChatGuidPut(
                      chatGuid,
                      password,
                      ApiV1ChatChatGuidPutRequest.builder().displayName(displayName).build())
                  .block(apiTimeout);
          result = requirePresent(result, "rename conversation");
          requireSuccessfulResponse(result.getStatus(), result.getMessage(), "rename conversation");
          return true;
        });
  }

  public boolean setConversationIcon(String chatGuid, Path iconPath) {
    if (StringUtils.isBlank(chatGuid) || iconPath == null) {
      return false;
    }
    long startedNanos = System.nanoTime();
    try {
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
                  HttpMethod.POST,
                  Map.of("guid", chatGuid),
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
              .block(apiTimeout);
      log.debug("Set conversation icon response: {}", response);
      recordOperationMetric("set_conversation_icon", true, null, startedNanos);
      return true;
    } catch (Exception e) {
      log.warn("Failed to set conversation icon {}", chatGuid, e);
      recordOperationMetric(
          "set_conversation_icon", false, OperationalMetricsService.failureType(e), startedNanos);
      return false;
    }
  }

  public boolean sendTextDirect(ApiV1MessageTextPostRequest request) {
    if (request == null) {
      return false;
    }
    String confirmationChatGuid = request.getChatGuid();
    request.setChatGuid(normalizeDirectAnyChatGuid(request.getChatGuid()));
    confirmationChatGuid = StringUtils.defaultIfBlank(confirmationChatGuid, request.getChatGuid());
    if (StringUtils.isBlank(request.getTempGuid())) {
      request.setTempGuid(UUID.randomUUID().toString());
    }
    applyTextFormatting(request);
    if (StringUtils.isBlank(request.getChatGuid()) || StringUtils.isBlank(request.getMessage())) {
      log.warn(
          "Cannot send direct text message without chatGuid and message chatGuid={} tempGuid={}",
          request.getChatGuid(),
          request.getTempGuid());
      return false;
    }

    Instant firstAttemptStartedAt = Instant.now();
    long overallStartedNanos = System.nanoTime();
    boolean success = false;
    String failureType = "not_confirmed";
    try {
      for (int attempt = 1; attempt <= DIRECT_SEND_MAX_ATTEMPTS; attempt++) {
        if (!warmUpDirectSendPath(request, attempt)) {
          failureType = "warmup_failed";
          return false;
        }

        log.info(
            "Attempting to send direct text message chatGuid={} confirmationChatGuid={} tempGuid={} attempt={}/{} timeout={} request={}",
            request.getChatGuid(),
            confirmationChatGuid,
            request.getTempGuid(),
            attempt,
            DIRECT_SEND_MAX_ATTEMPTS,
            apiTimeout,
            request);
        submitDirectTextMessage(request, attempt, overallStartedNanos);
        if (confirmDirectTextSend(
            request, confirmationChatGuid, firstAttemptStartedAt, attempt, overallStartedNanos)) {
          success = true;
          return true;
        }
      }
      log.warn(
          "Failed to confirm BlueBubbles direct text send after {} attempts chatGuid={} tempGuid={} elapsedMs={}",
          DIRECT_SEND_MAX_ATTEMPTS,
          request.getChatGuid(),
          request.getTempGuid(),
          elapsedMillis(overallStartedNanos));
      return false;
    } catch (RuntimeException e) {
      failureType = OperationalMetricsService.failureType(e);
      throw e;
    } finally {
      recordOperationMetric(
          "send_text_direct", success, success ? null : failureType, overallStartedNanos);
    }
  }

  private boolean warmUpDirectSendPath(ApiV1MessageTextPostRequest request, int attempt) {
    Duration pingTimeout =
        apiTimeout.compareTo(DIRECT_SEND_PING_TIMEOUT) <= 0 ? apiTimeout : DIRECT_SEND_PING_TIMEOUT;
    for (int pingAttempt = 1; pingAttempt <= DIRECT_SEND_PING_ATTEMPTS; pingAttempt++) {
      long startedNanos = System.nanoTime();
      try {
        pingBlueBubbles(pingTimeout);
        recordOperationMetric("direct_send_ping_warmup", true, null, startedNanos);
        if (pingAttempt > 1) {
          log.info(
              "BlueBubbles ping warmup recovered chatGuid={} tempGuid={} attempt={} pingAttempt={}",
              request.getChatGuid(),
              request.getTempGuid(),
              attempt,
              pingAttempt);
        }
        return true;
      } catch (Exception e) {
        recordOperationMetric(
            "direct_send_ping_warmup",
            false,
            OperationalMetricsService.failureType(e),
            startedNanos);
        log.warn(
            "BlueBubbles ping warmup failed chatGuid={} tempGuid={} attempt={} pingAttempt={}/{} timeout={} message={}",
            request.getChatGuid(),
            request.getTempGuid(),
            attempt,
            pingAttempt,
            DIRECT_SEND_PING_ATTEMPTS,
            pingTimeout,
            e.getMessage());
        log.debug("BlueBubbles ping warmup failure", e);
      }
    }
    log.warn(
        "BlueBubbles ping warmup failed twice; not attempting direct text send chatGuid={} tempGuid={} attempt={}",
        request.getChatGuid(),
        request.getTempGuid(),
        attempt);
    return false;
  }

  private void submitDirectTextMessage(
      ApiV1MessageTextPostRequest request, int attempt, long overallStartedNanos) {
    long attemptStartedNanos = System.nanoTime();
    boolean success = false;
    String failureType = null;
    try {
      messageApi.apiV1MessageTextPost(password, request).block(apiTimeout);
      success = true;
      log.info(
          "BlueBubbles direct text send request completed chatGuid={} tempGuid={} attempt={} attemptElapsedMs={} totalElapsedMs={}",
          request.getChatGuid(),
          request.getTempGuid(),
          attempt,
          elapsedMillis(attemptStartedNanos),
          elapsedMillis(overallStartedNanos));
    } catch (Exception e) {
      if (isTimeout(e)) {
        failureType = "timeout";
        log.warn(
            "Timed out waiting for BlueBubbles direct text send response chatGuid={} tempGuid={} attempt={} timeout={} attemptElapsedMs={} totalElapsedMs={}. Checking chat history before retrying.",
            request.getChatGuid(),
            request.getTempGuid(),
            attempt,
            apiTimeout,
            elapsedMillis(attemptStartedNanos),
            elapsedMillis(overallStartedNanos));
        log.debug("BlueBubbles direct text send timeout", e);
        return;
      }
      failureType = OperationalMetricsService.failureType(e);
      log.warn(
          "Failed to send direct message chatGuid={} tempGuid={} attempt={} attemptElapsedMs={} totalElapsedMs={}. Checking chat history before retrying.",
          request.getChatGuid(),
          request.getTempGuid(),
          attempt,
          elapsedMillis(attemptStartedNanos),
          elapsedMillis(overallStartedNanos),
          e);
    } finally {
      recordOperationMetric(
          "send_text_direct_submit",
          success,
          success ? null : StringUtils.defaultIfBlank(failureType, "exception"),
          attemptStartedNanos);
    }
  }

  private boolean confirmDirectTextSend(
      ApiV1MessageTextPostRequest request,
      String confirmationChatGuid,
      Instant firstAttemptStartedAt,
      int attempt,
      long overallStartedNanos) {
    long startedNanos = System.nanoTime();
    boolean success = false;
    String failureType = "not_found";
    if (!waitBeforeDirectSendConfirmation(request, attempt)) {
      recordOperationMetric("send_text_direct_confirm", false, "interrupted", startedNanos);
      return false;
    }
    try {
      String historyChatGuid =
          StringUtils.defaultIfBlank(confirmationChatGuid, request.getChatGuid());
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages =
          getMessagesInChat(historyChatGuid);
      boolean confirmed =
          messages != null
              && messages.stream()
                  .anyMatch(message -> isMatchingSentText(message, request, firstAttemptStartedAt));
      if (confirmed) {
        success = true;
        log.info(
            "Confirmed BlueBubbles direct text send in chat history chatGuid={} historyChatGuid={} tempGuid={} attempt={} elapsedMs={}",
            request.getChatGuid(),
            historyChatGuid,
            request.getTempGuid(),
            attempt,
            elapsedMillis(overallStartedNanos));
        return true;
      }
      failureType = "not_found";
      log.warn(
          "BlueBubbles direct text send not found in chat history chatGuid={} historyChatGuid={} tempGuid={} attempt={} elapsedMs={}",
          request.getChatGuid(),
          historyChatGuid,
          request.getTempGuid(),
          attempt,
          elapsedMillis(overallStartedNanos));
      return false;
    } catch (Exception e) {
      failureType = OperationalMetricsService.failureType(e);
      log.warn(
          "Failed to confirm BlueBubbles direct text send from chat history chatGuid={} confirmationChatGuid={} tempGuid={} attempt={} elapsedMs={}",
          request.getChatGuid(),
          confirmationChatGuid,
          request.getTempGuid(),
          attempt,
          elapsedMillis(overallStartedNanos),
          e);
      return false;
    } finally {
      recordOperationMetric(
          "send_text_direct_confirm", success, success ? null : failureType, startedNanos);
    }
  }

  private boolean waitBeforeDirectSendConfirmation(
      ApiV1MessageTextPostRequest request, int attempt) {
    Duration delay = directSendConfirmationDelay();
    if (delay == null || delay.isZero() || delay.isNegative()) {
      return true;
    }
    try {
      Thread.sleep(delay.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "Interrupted while waiting to confirm BlueBubbles direct text send chatGuid={} tempGuid={} attempt={}",
          request.getChatGuid(),
          request.getTempGuid(),
          attempt);
      return false;
    }
  }

  private boolean isMatchingSentText(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner message,
      ApiV1MessageTextPostRequest request,
      Instant firstAttemptStartedAt) {
    if (message == null || !Boolean.TRUE.equals(message.getIsFromMe())) {
      return false;
    }
    if (!Objects.equals(message.getText(), request.getMessage())) {
      return false;
    }
    Instant lowerBound = firstAttemptStartedAt.minus(DIRECT_SEND_MATCH_WINDOW_SKEW);
    Instant messageCreatedAt = parseBlueBubblesTimestamp(message.getDateCreated());
    return messageCreatedAt == null || !messageCreatedAt.isBefore(lowerBound);
  }

  private static Instant parseBlueBubblesTimestamp(Long value) {
    if (value == null) {
      return null;
    }
    if (value > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(value);
    }
    return Instant.ofEpochSecond(value);
  }

  private void applyTextFormatting(ApiV1MessageTextPostRequest request) {
    if (request == null) {
      return;
    }
    String message = request.getMessage();
    if (StringUtils.isBlank(message)) {
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
    if (StringUtils.isBlank(method) || !"private-api".equalsIgnoreCase(method.trim())) {
      request.setMethod("private-api");
    }
  }

  public boolean sendReactionDirect(IncomingMessage message, String reaction) {
    if (StringUtils.isBlank(message.chatGuid())) {
      log.warn("Cannot send reaction without chatGuid");
      return false;
    }
    if (StringUtils.isBlank(message.messageGuid())) {
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
    if (!MessageReactionSupport.SUPPORTED_REACTIONS.contains(request.getReaction())) {
      log.warn("Unsupported reaction {}", request.getReaction());
      return false;
    }
    long startedNanos = System.nanoTime();
    try {
      messageApi.apiV1MessageReactPost(password, request).block(apiTimeout);
      log.info("Sent reaction {} to {}", request.getReaction(), request.getChatGuid());
      recordOperationMetric("send_reaction", true, null, startedNanos);
      return true;
    } catch (Exception e) {
      log.warn("Failed to send reaction", e);
      recordOperationMetric(
          "send_reaction", false, OperationalMetricsService.failureType(e), startedNanos);
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
        StringUtils.isBlank(service) || "any".equalsIgnoreCase(service)
            ? BBMessageAgent.IMESSAGE_SERVICE
            : service;
    return resolvedService + chatGuid.substring("any".length());
  }

  public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(String chatGuid) {
    return measuredOperation(
        "get_messages_in_chat",
        () -> {
          ApiV1ChatChatGuidMessageGet200Response response =
              this.chatApi
                  .apiV1ChatChatGuidMessageGet(
                      chatGuid, password, "handle", null, null, null, 100, "DESC")
                  .block(apiTimeout);
          response = requirePresent(response, "get messages in chat");
          return requirePresent(response.getData(), "get messages in chat");
        });
  }

  public boolean ping() {
    long startedNanos = System.nanoTime();
    try {
      pingBlueBubbles(apiTimeout);
      recordOperationMetric("ping", true, null, startedNanos);
      return true;
    } catch (Exception e) {
      log.warn("Failed to ping", e);
      recordOperationMetric("ping", false, OperationalMetricsService.failureType(e), startedNanos);
      return false;
    }
  }

  protected void pingBlueBubbles(Duration timeout) {
    this.otherApi.apiV1PingGet(password).block(timeout);
  }

  public IcloudAccountInfo getAccount() {
    return measuredOperation(
        "get_icloud_account",
        () -> {
          ApiResponseIcloudAccount account =
              this.icloudApi.apiV1IcloudAccountGet(password).block(apiTimeout);
          account = requirePresent(account, "get iCloud account");
          requireSuccessfulResponse(
              account.getStatus(), account.getMessage(), "get iCloud account");
          return requirePresent(account.getData(), "get iCloud account");
        });
  }
}
