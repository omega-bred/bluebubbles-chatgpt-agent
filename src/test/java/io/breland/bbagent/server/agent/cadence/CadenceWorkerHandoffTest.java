package io.breland.bbagent.server.agent.cadence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.serviceclient.WorkflowServiceGrpc;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.WorkerFactory;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import io.breland.bbagent.server.blobstore.InMemoryBlobStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CadenceWorkerHandoffTest {
  @Test
  @Timeout(30)
  void aSecondWorkerRetriesWithThePayloadWrittenByTheFirstWorker() throws Exception {
    var blobs = new InMemoryBlobStore();
    var converter =
        new CadenceWorkflowConfig()
            .cadenceWorkflowClient(
                mock(WorkflowServiceGrpc.class), new AgentWorkflowProperties(), blobs)
            .getOptions()
            .getDataConverter();
    var environment =
        TestWorkflowEnvironment.newInstance(
            new TestEnvironmentOptions.Builder()
                .setDataConverter(converter)
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
                .build());
    var client = environment.newWorkflowClient();
    var firstFactory = WorkerFactory.newInstance(client);
    var secondFactory = WorkerFactory.newInstance(client);
    try {
      String queue = "shared-bluechat";
      environment
          .newWorker(queue)
          .registerWorkflowImplementationTypes(CadenceMessageWorkflowImpl.class);
      var first = firstFactory.newWorker(queue);
      var second = secondFactory.newWorker(queue);
      var firstActivities = mock(CadenceAgentActivities.class);
      var secondActivities = mock(CadenceAgentActivities.class);
      first.registerActivitiesImplementations(firstActivities);
      second.registerActivitiesImplementations(secondActivities);
      var message =
          new IncomingMessage(
              "chat",
              "message",
              null,
              "synthetic",
              false,
              "iMessage",
              "synthetic-sender",
              false,
              Instant.parse("2026-11-20T12:00:00Z"),
              List.of(),
              false);
      var workflow =
          new AgentWorkflowContext(
              "chat:turn:42", "chat", "message", message.timestamp(), DecisionRouting.EMPTY, 42L);
      String input = "é".repeat(1_100_000);
      when(firstActivities.getConversationHistory(message)).thenReturn(List.of());
      when(firstActivities.buildConversationInputJson(List.of(), message)).thenReturn(input);
      var activityStarted = new CompletableFuture<byte[]>();
      when(firstActivities.createResponseBundle(eq(input), eq(message), eq(workflow)))
          .thenAnswer(
              call -> {
                Activity.doNotCompleteOnReturn();
                activityStarted.complete(Activity.getTaskToken());
                return null;
              });
      var reply = new CadenceResponseBundle("{\"output\":[]}", "one reply", "[]", List.of());
      when(secondActivities.createResponseBundle(input, message, workflow)).thenReturn(reply);
      when(secondActivities.handleGeneratedImages(
              any(), eq("one reply"), eq(message), eq(workflow)))
          .thenReturn(new ImageSendResult(false, false));
      when(secondActivities.sendThreadAwareText(message, "one reply", workflow)).thenReturn(true);
      firstFactory.start();
      environment.start();

      var typed =
          client.newWorkflowStub(
              CadenceMessageWorkflow.class,
              new WorkflowOptions.Builder()
                  .setTaskList(queue)
                  .setWorkflowId(workflow.workflowId())
                  .setExecutionStartToCloseTimeout(Duration.ofMinutes(15))
                  .build());
      WorkflowClient.start(typed::run, new CadenceMessageWorkflowRequest(workflow, message, null));
      byte[] taskToken = activityStarted.get(8, TimeUnit.SECONDS);
      firstFactory.shutdownNow();
      firstFactory.awaitTermination(5, TimeUnit.SECONDS);
      secondFactory.start();
      environment
          .getWorkflowService()
          .RespondActivityTaskFailed(
              new RespondActivityTaskFailedRequest()
                  .setTaskToken(taskToken)
                  .setReason("first worker lost"));

      try {
        WorkflowStub.fromTyped(typed).getResultAsync(Void.class).get(12, TimeUnit.SECONDS);
      } catch (TimeoutException timeout) {
        throw new AssertionError(environment.getDiagnostics(), timeout);
      }

      verify(firstActivities).createResponseBundle(input, message, workflow);
      verify(secondActivities).createResponseBundle(input, message, workflow);
      verify(secondActivities).sendThreadAwareText(message, "one reply", workflow);
    } finally {
      firstFactory.shutdownNow();
      secondFactory.shutdownNow();
      environment.close();
    }
  }
}
