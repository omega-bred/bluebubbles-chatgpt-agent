package io.breland.bbagent.server.agent;

import com.uber.cadence.workflow.Workflow;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

final class WorkflowResponseGate {
  private final Map<String, ConversationState> conversations;

  WorkflowResponseGate(Map<String, ConversationState> conversations) {
    this.conversations = conversations;
  }

  boolean canSendResponses(AgentWorkflowContext workflowContext) {
    if (workflowContext == null) {
      return true;
    }
    String currentRunId = null;
    try {
      currentRunId = Workflow.getWorkflowInfo().getRunId();
    } catch (Error e) {
      if (e.getMessage() == null || !e.getMessage().contains("non workflow")) {
        throw e;
      }
      // Running outside Cadence, such as in unit tests or direct tool calls.
    }
    return canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    if (workflowContext == null) {
      return true;
    }
    if (ScheduledEventTool.isScheduledWorkflowId(workflowContext.workflowId())) {
      return true;
    }
    if (workflowContext.chatGuid() == null || workflowContext.chatGuid().isBlank()) {
      return true;
    }
    ConversationState state = conversations.get(workflowContext.chatGuid());
    if (state == null) {
      return true;
    }
    synchronized (state) {
      String latestWorkflowMessageGuid = state.getLatestWorkflowMessageGuid();
      if (StringUtils.isNotBlank(latestWorkflowMessageGuid)
          && StringUtils.isNotBlank(workflowContext.messageGuid())
          && !latestWorkflowMessageGuid.equals(workflowContext.messageGuid())) {
        return false;
      }
      if (currentRunId == null || currentRunId.isBlank()) {
        return latestWorkflowMessageGuid == null
            || latestWorkflowMessageGuid.isBlank()
            || workflowContext.messageGuid() == null
            || latestWorkflowMessageGuid.equals(workflowContext.messageGuid());
      }
      String latestWorkflowRunId = state.getLatestWorkflowRunId();

      // can be null until we persist state in a real db.
      return latestWorkflowRunId == null || latestWorkflowRunId.equals(currentRunId);
    }
  }
}
