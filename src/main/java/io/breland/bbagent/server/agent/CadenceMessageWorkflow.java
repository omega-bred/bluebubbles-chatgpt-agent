package io.breland.bbagent.server.agent;

import com.uber.cadence.workflow.WorkflowMethod;

public interface CadenceMessageWorkflow {
  @WorkflowMethod
  void run(CadenceMessageWorkflowRequest request);
}
