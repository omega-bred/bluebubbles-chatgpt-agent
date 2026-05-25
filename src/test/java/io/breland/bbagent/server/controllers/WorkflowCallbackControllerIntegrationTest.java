package io.breland.bbagent.server.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.persistence.workflowcallback.AgentWorkflowCallbackRepository;
import io.breland.bbagent.server.agent.workflowcallback.StandardWebhookVerifier;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowCallbackControllerIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private WorkflowCallbackService callbackService;
  @Autowired private AgentWorkflowCallbackRepository callbackRepository;
  @Autowired private StandardWebhookVerifier verifier;

  @MockBean private CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @BeforeEach
  void clean() {
    callbackRepository.deleteAll();
  }

  @Test
  void signedCallbackWithoutContentTypeIsAcceptedAndProcessedWithCadence() throws Exception {
    WorkflowCallbackService.CreatedCallback callback =
        callbackService.createCallback(
            incomingMessage(),
            "Clone repo and summarize commits",
            "Send the summary.",
            Duration.ofMinutes(30));
    WorkflowExecution execution = new WorkflowExecution();
    execution.setWorkflowId("callback:" + callback.callbackId());
    execution.setRunId("run-1");
    when(cadenceWorkflowLauncher.startWorkflow(any(CadenceMessageWorkflowRequest.class)))
        .thenReturn(execution);
    byte[] payload = payload();
    String webhookId = "evt_callback_integration_1";
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String signature = verifier.sign(callback.signingSecret(), webhookId, timestamp, payload);

    mockMvc
        .perform(
            post("/api/v1/workflowCallback/receive.workflowCallbacks")
                .queryParam("callback_id", callback.callbackId())
                .header("webhook-id", webhookId)
                .header("webhook-timestamp", timestamp)
                .header("webhook-signature", signature)
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("processed"))
        .andExpect(jsonPath("$.workflow_id").value("callback:" + callback.callbackId()))
        .andExpect(jsonPath("$.run_id").value("run-1"));

    ArgumentCaptor<CadenceMessageWorkflowRequest> requestCaptor =
        ArgumentCaptor.forClass(CadenceMessageWorkflowRequest.class);
    verify(cadenceWorkflowLauncher).startWorkflow(requestCaptor.capture());
    String callbackText = requestCaptor.getValue().message().text();
    org.junit.jupiter.api.Assertions.assertTrue(callbackText.contains("Callback payload"));
    org.junit.jupiter.api.Assertions.assertTrue(
        callbackText.contains("Clone repo and summarize commits"));
    org.junit.jupiter.api.Assertions.assertTrue(callbackText.contains("\"summary\" : \"done\""));
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

  private byte[] payload() {
    return "{\"type\":\"agent.async_task.completed\",\"timestamp\":\"2024-01-01T00:00:00Z\",\"data\":{\"status\":\"completed\",\"summary\":\"done\"}}"
        .getBytes(StandardCharsets.UTF_8);
  }
}
