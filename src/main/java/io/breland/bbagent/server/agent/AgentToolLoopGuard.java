package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SetGroupIconAgentTool;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AgentToolLoopGuard {

  private static final int DEFAULT_MAX_IDENTICAL_CALLS = 2;

  private static final Map<String, Integer> STANDARD_MAX_CALLS_BY_TOOL =
      Map.of(
          SendGiphyAgentTool.TOOL_NAME, 1,
          SendTextAgentTool.TOOL_NAME, 2,
          SendReactionAgentTool.TOOL_NAME, 2,
          RenameConversationAgentTool.TOOL_NAME, 1,
          SetGroupIconAgentTool.TOOL_NAME, 1,
          WorkflowCallbackService.TOOL_NAME, 1);

  private final Map<String, Integer> maxCallsByTool;
  private final int maxIdenticalCalls;
  private final Map<String, Integer> callsByTool = new HashMap<>();
  private final Map<String, Integer> callsBySignature = new HashMap<>();

  private AgentToolLoopGuard(Map<String, Integer> maxCallsByTool, int maxIdenticalCalls) {
    this.maxCallsByTool = maxCallsByTool == null ? Map.of() : Map.copyOf(maxCallsByTool);
    this.maxIdenticalCalls = maxIdenticalCalls;
  }

  public static AgentToolLoopGuard standard() {
    return new AgentToolLoopGuard(STANDARD_MAX_CALLS_BY_TOOL, DEFAULT_MAX_IDENTICAL_CALLS);
  }

  public boolean shouldBlock(String toolName, String arguments) {
    if (toolName == null || toolName.isBlank()) {
      return false;
    }
    int toolCalls = callsByTool.getOrDefault(toolName, 0) + 1;
    callsByTool.put(toolName, toolCalls);

    String signature = signature(toolName, arguments);
    int signatureCalls = callsBySignature.getOrDefault(signature, 0) + 1;
    callsBySignature.put(signature, signatureCalls);

    Integer maxCalls = maxCallsByTool.get(toolName);
    if (maxCalls != null && toolCalls > maxCalls) {
      log.warn(
          "Blocking repeated tool call for {} after {} calls in one turn", toolName, toolCalls);
      return true;
    }
    if (signatureCalls > maxIdenticalCalls) {
      log.warn("Blocking repeated tool call signature {}", signature);
      return true;
    }
    return false;
  }

  private static String signature(String toolName, String arguments) {
    String args = arguments == null ? "" : arguments.trim();
    return toolName + "|" + args;
  }
}
