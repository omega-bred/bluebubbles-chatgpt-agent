package io.breland.bbagent.server.agent.workflowcallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.persistence.workflowcallback.AgentWorkflowCallbackEntity;
import io.breland.bbagent.server.agent.persistence.workflowcallback.AgentWorkflowCallbackRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Slf4j
public class WorkflowCallbackService {
  public static final String TOOL_NAME = "create_workflow_callback";
  private static final String CALLBACK_WORKFLOW_ID_PREFIX = "callback:";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_RECEIVED = "RECEIVED";
  private static final String STATUS_EXPIRED = "EXPIRED";

  private final AgentWorkflowCallbackRepository repository;
  private final StandardWebhookVerifier verifier;
  private final AgentWorkflowProperties workflowProperties;
  private final ObjectMapper objectMapper;
  private final @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher;
  private final SecureRandom secureRandom = new SecureRandom();
  private final TaskExecutor inlineCallbackExecutor =
      new SimpleAsyncTaskExecutor("workflow-callback-");
  private @Nullable ObjectProvider<BBMessageAgent> messageAgentProvider;

  public WorkflowCallbackService(
      AgentWorkflowCallbackRepository repository,
      StandardWebhookVerifier verifier,
      AgentWorkflowProperties workflowProperties,
      ObjectMapper objectMapper,
      @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.repository = repository;
    this.verifier = verifier;
    this.workflowProperties = workflowProperties;
    this.objectMapper = objectMapper;
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  @Autowired
  void setMessageAgentProvider(ObjectProvider<BBMessageAgent> messageAgentProvider) {
    this.messageAgentProvider = messageAgentProvider;
  }

  public static boolean isCallbackWorkflowId(String workflowId) {
    return workflowId != null && workflowId.startsWith(CALLBACK_WORKFLOW_ID_PREFIX);
  }

  @Transactional
  public CreatedCallback createCallback(
      IncomingMessage source,
      String purpose,
      String resumeInstructions,
      @Nullable Duration expiresIn) {
    if (source == null || source.chatGuid() == null || source.chatGuid().isBlank()) {
      throw new IllegalArgumentException("missing chat");
    }
    if (purpose == null || purpose.isBlank()) {
      throw new IllegalArgumentException("missing purpose");
    }
    if (resumeInstructions == null || resumeInstructions.isBlank()) {
      throw new IllegalArgumentException("missing resume instructions");
    }
    Duration ttl =
        expiresIn == null || expiresIn.isNegative() || expiresIn.isZero()
            ? Duration.ofHours(Math.max(1, workflowProperties.getCallbackDefaultTtlHours()))
            : expiresIn;
    String callbackId = UUID.randomUUID().toString();
    String signingSecret = createSigningSecret();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(ttl);
    AgentWorkflowCallbackEntity entity =
        new AgentWorkflowCallbackEntity(
            callbackId,
            signingSecret,
            source.chatGuid(),
            source.messageGuid(),
            source.threadOriginatorGuid(),
            source.service(),
            source.sender(),
            source.isGroup(),
            purpose.trim(),
            resumeInstructions.trim(),
            STATUS_PENDING,
            expiresAt,
            now,
            now);
    repository.save(entity);
    String callbackUrl = buildCallbackUrl(callbackId);
    return new CreatedCallback(
        callbackId,
        callbackUrl,
        signingSecret,
        expiresAt,
        callbackInstructions(callbackUrl, signingSecret));
  }

  @Transactional
  public ReceiveResult receiveCallback(String callbackId, HttpHeaders headers, byte[] body) {
    if (callbackId == null || callbackId.isBlank()) {
      return ReceiveResult.badRequest("missing callback_id");
    }
    Optional<AgentWorkflowCallbackEntity> optional = repository.findLockedByCallbackId(callbackId);
    if (optional.isEmpty()) {
      return ReceiveResult.notFound("unknown callback");
    }
    AgentWorkflowCallbackEntity entity = optional.get();
    String webhookId = firstHeader(headers, "webhook-id");
    String webhookTimestamp = firstHeader(headers, "webhook-timestamp");
    String webhookSignature = firstHeader(headers, "webhook-signature");
    Instant now = Instant.now();
    if (entity.getReceivedWebhookId() != null) {
      if (entity.getReceivedWebhookId().equals(webhookId)) {
        return ReceiveResult.duplicate("duplicate callback delivery");
      }
      return ReceiveResult.conflict("callback already consumed");
    }
    if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(now)) {
      entity.setStatus(STATUS_EXPIRED);
      entity.setUpdatedAt(now);
      repository.save(entity);
      return ReceiveResult.expired("callback expired");
    }
    StandardWebhookVerifier.VerificationResult verification =
        verifier.verify(
            entity.getSigningSecret(),
            webhookId,
            webhookTimestamp,
            webhookSignature,
            body,
            now,
            Duration.ofSeconds(
                Math.max(1, workflowProperties.getCallbackTimestampToleranceSeconds())));
    if (!verification.valid()) {
      return ReceiveResult.unauthorized(verification.error());
    }
    JsonNode payload;
    try {
      payload = objectMapper.readTree(body);
    } catch (Exception e) {
      return ReceiveResult.badRequest("invalid JSON payload");
    }
    entity.setStatus(STATUS_RECEIVED);
    entity.setReceivedWebhookId(webhookId);
    entity.setReceivedAt(now);
    entity.setUpdatedAt(now);
    repository.save(entity);
    WorkflowExecution execution = startCallbackWorkflow(entity, payload, body);
    if (execution == null) {
      throw new IllegalStateException("failed to start callback workflow");
    }
    return ReceiveResult.processed(
        "callback accepted", execution.getWorkflowId(), execution.getRunId());
  }

  private WorkflowExecution startCallbackWorkflow(
      AgentWorkflowCallbackEntity entity, JsonNode payload, byte[] rawBody) {
    String workflowId = CALLBACK_WORKFLOW_ID_PREFIX + entity.getCallbackId();
    String messageGuid = "callback-" + entity.getCallbackId();
    String text = callbackMessageText(entity, payload, rawBody);
    IncomingMessage callbackMessage =
        new IncomingMessage(
            entity.getChatGuid(),
            messageGuid,
            entity.getThreadOriginatorGuid(),
            text,
            false,
            entity.getService() == null ? BBMessageAgent.IMESSAGE_SERVICE : entity.getService(),
            entity.getSender(),
            entity.isGroup(),
            Instant.now(),
            java.util.List.of(),
            false);
    if (cadenceWorkflowLauncher != null) {
      try {
        WorkflowExecution execution =
            cadenceWorkflowLauncher.startWorkflow(
                new CadenceMessageWorkflowRequest(
                    new AgentWorkflowContext(
                        workflowId, entity.getChatGuid(), messageGuid, Instant.now()),
                    callbackMessage,
                    null));
        if (execution != null) {
          return execution;
        }
      } catch (Exception e) {
        log.warn("Cadence callback workflow failed for {}; falling back to inline", workflowId, e);
      }
    }
    return startInlineCallbackWorkflow(workflowId, callbackMessage);
  }

  private WorkflowExecution startInlineCallbackWorkflow(
      String workflowId, IncomingMessage callbackMessage) {
    BBMessageAgent messageAgent =
        messageAgentProvider == null ? null : messageAgentProvider.getIfAvailable();
    if (messageAgent == null) {
      throw new IllegalStateException("No workflow processor is configured for callbacks");
    }
    inlineCallbackExecutor.execute(
        () -> {
          try {
            messageAgent.handleIncomingMessage(callbackMessage);
          } catch (Exception e) {
            log.warn("Inline callback workflow failed for {}", workflowId, e);
          }
        });
    WorkflowExecution execution = new WorkflowExecution();
    execution.setWorkflowId(workflowId);
    execution.setRunId("inline");
    return execution;
  }

  private String callbackMessageText(
      AgentWorkflowCallbackEntity entity, JsonNode payload, byte[] rawBody) {
    String payloadText;
    try {
      payloadText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    } catch (Exception e) {
      payloadText =
          new String(
              rawBody == null ? new byte[0] : rawBody, java.nio.charset.StandardCharsets.UTF_8);
    }
    return "Async workflow callback received.\n\n"
        + "Original purpose: "
        + entity.getPurpose()
        + "\n\nResume instructions: "
        + entity.getResumeInstructions()
        + "\n\nCallback payload:\n"
        + payloadText
        + "\n\nContinue now. Notify the user with the final result or a useful status update.";
  }

  private String buildCallbackUrl(String callbackId) {
    String baseUrl = workflowProperties.getCallbackBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "http://localhost:8080";
    }
    URI base =
        URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    return UriComponentsBuilder.fromUri(base)
        .path("/api/v1/workflowCallback/receive.workflowCallbacks")
        .queryParam("callback_id", callbackId)
        .build()
        .toUriString();
  }

  private String callbackInstructions(String callbackUrl, String signingSecret) {
    return """
        When the async work completes or fails, call this Standard Webhooks callback exactly once.
        POST %s
        Use headers: Content-Type=application/json, webhook-id=<unique stable id>, webhook-timestamp=<unix seconds>, webhook-signature=v1,<base64 HMAC-SHA256>.
        Signing secret: %s
        Sign the exact raw request body using HMAC-SHA256 over: webhook-id + '.' + webhook-timestamp + '.' + raw_body.
        Send JSON shaped like {"type":"agent.async_task.completed","timestamp":"<ISO-8601>","data":{"status":"completed","summary":"...","details":"...","artifacts":[]}}.
        Use type agent.async_task.failed and include data.errors if the work fails. Keep payloads thin; include IDs or links instead of huge logs.
        """
        .formatted(callbackUrl, signingSecret)
        .trim();
  }

  private String createSigningSecret() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return "whsec_" + Base64.getEncoder().encodeToString(bytes);
  }

  private static String firstHeader(HttpHeaders headers, String name) {
    return headers == null ? null : headers.getFirst(name);
  }

  public record CreatedCallback(
      String callbackId,
      String callbackUrl,
      String signingSecret,
      Instant expiresAt,
      String callbackInstructions) {}

  public record ReceiveResult(
      ReceiveStatus status, String message, @Nullable String workflowId, @Nullable String runId) {
    static ReceiveResult processed(String message, String workflowId, String runId) {
      return new ReceiveResult(ReceiveStatus.PROCESSED, message, workflowId, runId);
    }

    static ReceiveResult duplicate(String message) {
      return withoutWorkflow(ReceiveStatus.DUPLICATE, message);
    }

    static ReceiveResult badRequest(String message) {
      return withoutWorkflow(ReceiveStatus.BAD_REQUEST, message);
    }

    static ReceiveResult unauthorized(String message) {
      return withoutWorkflow(ReceiveStatus.UNAUTHORIZED, message);
    }

    static ReceiveResult notFound(String message) {
      return withoutWorkflow(ReceiveStatus.NOT_FOUND, message);
    }

    static ReceiveResult conflict(String message) {
      return withoutWorkflow(ReceiveStatus.CONFLICT, message);
    }

    static ReceiveResult expired(String message) {
      return withoutWorkflow(ReceiveStatus.EXPIRED, message);
    }

    private static ReceiveResult withoutWorkflow(ReceiveStatus status, String message) {
      return new ReceiveResult(status, message, null, null);
    }

    public HttpStatus httpStatus() {
      return status.httpStatus();
    }

    public Map<String, Object> body() {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("status", status.responseValue());
      response.put("message", message);
      if (workflowId != null) {
        response.put("workflow_id", workflowId);
      }
      if (runId != null) {
        response.put("run_id", runId);
      }
      return response;
    }
  }

  public enum ReceiveStatus {
    PROCESSED(HttpStatus.OK),
    DUPLICATE(HttpStatus.OK),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    EXPIRED(HttpStatus.GONE);

    private final HttpStatus httpStatus;

    ReceiveStatus(HttpStatus httpStatus) {
      this.httpStatus = httpStatus;
    }

    HttpStatus httpStatus() {
      return httpStatus;
    }

    String responseValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
