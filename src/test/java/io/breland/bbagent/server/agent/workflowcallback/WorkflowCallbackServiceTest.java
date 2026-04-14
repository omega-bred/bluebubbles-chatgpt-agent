package io.breland.bbagent.server.agent.workflowcallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.persistence.workflowcallback.AgentWorkflowCallbackEntity;
import io.breland.bbagent.server.agent.persistence.workflowcallback.AgentWorkflowCallbackRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

class WorkflowCallbackServiceTest {
  private final AgentWorkflowCallbackRepository repository =
      Mockito.mock(AgentWorkflowCallbackRepository.class);
  private final StandardWebhookVerifier verifier = new StandardWebhookVerifier();
  private final AgentWorkflowProperties properties = new AgentWorkflowProperties();
  private final CadenceWorkflowLauncher launcher = Mockito.mock(CadenceWorkflowLauncher.class);
  private final WorkflowCallbackService service =
      new WorkflowCallbackService(repository, verifier, properties, new ObjectMapper(), launcher);

  @Test
  void createCallbackReturnsUrlSecretAndInstructions() {
    properties.setCallbackBaseUrl("https://chatagent.example");
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    WorkflowCallbackService.CreatedCallback callback =
        service.createCallback(
            incomingMessage(), "Summarize repo", "Send the summary to the user.", null);

    assertTrue(
        callback
            .callbackUrl()
            .startsWith(
                "https://chatagent.example/api/v1/workflowCallback/receive.workflowCallbacks?callback_id="));
    assertTrue(callback.signingSecret().startsWith("whsec_"));
    assertTrue(callback.callbackInstructions().contains(callback.callbackUrl()));
    assertTrue(callback.callbackInstructions().contains(callback.signingSecret()));
  }

  @Test
  void receiveCallbackRejectsUnknownCallback() {
    when(repository.findLockedByCallbackId("missing")).thenReturn(Optional.empty());

    WorkflowCallbackService.ReceiveResult result =
        service.receiveCallback("missing", new HttpHeaders(), payload());

    assertEquals(WorkflowCallbackService.ReceiveStatus.NOT_FOUND, result.status());
  }

  @Test
  void receiveCallbackRejectsExpiredCallback() {
    AgentWorkflowCallbackEntity entity = callbackEntity();
    entity.setStatus("PENDING");
    when(repository.findLockedByCallbackId("callback-1")).thenReturn(Optional.of(entity));

    WorkflowCallbackService.ReceiveResult result =
        service.receiveCallback("callback-1", signedHeaders(entity, payload()), payload());

    assertEquals(WorkflowCallbackService.ReceiveStatus.EXPIRED, result.status());
  }

  @Test
  void receiveCallbackAcknowledgesDuplicateWebhookId() {
    AgentWorkflowCallbackEntity entity = callbackEntity(Instant.now().plusSeconds(60));
    entity.setReceivedWebhookId("msg_1");
    when(repository.findLockedByCallbackId("callback-1")).thenReturn(Optional.of(entity));

    WorkflowCallbackService.ReceiveResult result =
        service.receiveCallback("callback-1", headers("msg_1", "0", "ignored"), payload());

    assertEquals(WorkflowCallbackService.ReceiveStatus.DUPLICATE, result.status());
  }

  @Test
  void receiveCallbackStartsCallbackWorkflowForValidPayload() {
    byte[] payload = payload();
    AgentWorkflowCallbackEntity entity = callbackEntity(Instant.now().plusSeconds(60));
    when(repository.findLockedByCallbackId("callback-1")).thenReturn(Optional.of(entity));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    WorkflowExecution execution = new WorkflowExecution();
    execution.setWorkflowId("callback:callback-1");
    execution.setRunId("run-1");
    when(launcher.startWorkflow(any(CadenceMessageWorkflowRequest.class))).thenReturn(execution);

    WorkflowCallbackService.ReceiveResult result =
        service.receiveCallback("callback-1", signedHeaders(entity, payload), payload);

    assertEquals(WorkflowCallbackService.ReceiveStatus.PROCESSED, result.status());
    ArgumentCaptor<CadenceMessageWorkflowRequest> requestCaptor =
        ArgumentCaptor.forClass(CadenceMessageWorkflowRequest.class);
    verify(launcher).startWorkflow(requestCaptor.capture());
    assertEquals("callback:callback-1", requestCaptor.getValue().workflowContext().workflowId());
    assertTrue(requestCaptor.getValue().message().text().contains("Callback payload"));
  }

  private IncomingMessage incomingMessage() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "start task",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }

  private AgentWorkflowCallbackEntity callbackEntity() {
    return callbackEntity(Instant.now().minusSeconds(60));
  }

  private AgentWorkflowCallbackEntity callbackEntity(Instant expiresAt) {
    Instant now = Instant.now();
    return new AgentWorkflowCallbackEntity(
        "callback-1",
        signingSecret(),
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "iMessage",
        "Alice",
        false,
        "Do async work",
        "Summarize result",
        "PENDING",
        expiresAt,
        now,
        now);
  }

  private HttpHeaders signedHeaders(AgentWorkflowCallbackEntity entity, byte[] payload) {
    String webhookId = "msg_1";
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = verifier.sign(entity.getSigningSecret(), webhookId, timestamp, payload);
    return headers(webhookId, timestamp, signature);
  }

  private HttpHeaders headers(String webhookId, String timestamp, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("webhook-id", webhookId);
    headers.add("webhook-timestamp", timestamp);
    headers.add("webhook-signature", signature);
    return headers;
  }

  private String signingSecret() {
    return "whsec_"
        + Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
  }

  private byte[] payload() {
    return "{\"type\":\"agent.async_task.completed\",\"timestamp\":\"2024-01-01T00:00:00Z\",\"data\":{\"status\":\"completed\",\"summary\":\"done\"}}"
        .getBytes(StandardCharsets.UTF_8);
  }
}
