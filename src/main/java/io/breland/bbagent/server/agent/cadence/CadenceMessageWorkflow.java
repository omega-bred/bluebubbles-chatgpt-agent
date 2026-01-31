package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.workflow.WorkflowMethod;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;

public interface CadenceMessageWorkflow {
  @WorkflowMethod
  void run(CadenceMessageWorkflowRequest request);
}
