package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(WorkflowClient.class)
@Slf4j
public class CadenceWorkflowLauncher {

  private final WorkflowClient workflowClient;
  private final AgentWorkflowProperties workflowProperties;

  public CadenceWorkflowLauncher(
      WorkflowClient workflowClient, AgentWorkflowProperties workflowProperties) {
    this.workflowClient = workflowClient;
    this.workflowProperties = workflowProperties;
  }

  public void startWorkflow(CadenceMessageWorkflowRequest request) {
    if (request == null || request.workflowContext() == null) {
      log.warn("Dropping cadence start workflow [ request or workflowContext is null ]");
      return;
    }
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setTaskList(workflowProperties.getCadenceTaskList())
            .setWorkflowId(request.workflowContext().workflowId())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.TerminateIfRunning)
            .setExecutionStartToCloseTimeout(Duration.ofMinutes(5))
            .setTaskStartToCloseTimeout(Duration.ofMinutes(1))
            .build();
    CadenceMessageWorkflow workflow =
        workflowClient.newWorkflowStub(CadenceMessageWorkflow.class, options);
    log.info("Submitting workflow: {}", workflow);
    WorkflowClient.start(workflow::run, request);
    log.info("Submitted workflow: {}", workflow);
  }
}
