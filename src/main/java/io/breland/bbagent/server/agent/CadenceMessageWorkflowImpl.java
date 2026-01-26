package io.breland.bbagent.server.agent;

import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Workflow;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CadenceMessageWorkflowImpl implements CadenceMessageWorkflow {

  private final CadenceAgentActivities activities =
      Workflow.newActivityStub(
          CadenceAgentActivities.class,
          new ActivityOptions.Builder()
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .build());

  @Override
  public void run(CadenceMessageWorkflowRequest request) {
    if (request == null || request.message() == null || request.workflowContext() == null) {
      return;
    }
    log.info("Handling message via cadence: {}", request.workflowContext());
    activities.runMessageWorkflow(request.message(), request.workflowContext());
  }
}
