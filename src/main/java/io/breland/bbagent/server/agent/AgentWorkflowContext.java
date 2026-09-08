package io.breland.bbagent.server.agent;

import java.time.Instant;

public record AgentWorkflowContext(
    String workflowId, String chatGuid, String messageGuid, Instant startedAt) {
  public static String reactionWorkflowId(String chatGuid) {
    return chatGuid + ":reaction";
  }

  public boolean isReactionWorkflow() {
    return chatGuid != null && reactionWorkflowId(chatGuid).equals(workflowId);
  }
}
