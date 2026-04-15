package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import org.junit.jupiter.api.Test;

class AgentToolLoopGuardTest {

  @Test
  void blocksToolSpecificLimits() {
    AgentToolLoopGuard guard = AgentToolLoopGuard.standard();

    assertFalse(guard.shouldBlock(SendTextAgentTool.TOOL_NAME, "{\"text\":\"one\"}"));
    assertFalse(guard.shouldBlock(SendTextAgentTool.TOOL_NAME, "{\"text\":\"two\"}"));
    assertTrue(guard.shouldBlock(SendTextAgentTool.TOOL_NAME, "{\"text\":\"three\"}"));
  }

  @Test
  void blocksRepeatedIdenticalToolSignatures() {
    AgentToolLoopGuard guard = AgentToolLoopGuard.standard();

    assertFalse(guard.shouldBlock("some_tool", "{\"same\":true}"));
    assertFalse(guard.shouldBlock("some_tool", "{\"same\":true}"));
    assertTrue(guard.shouldBlock("some_tool", "{\"same\":true}"));
  }

  @Test
  void blocksRepeatedWorkflowCallbackEvenWhenArgumentsDrift() {
    AgentToolLoopGuard guard = AgentToolLoopGuard.standard();

    assertFalse(
        guard.shouldBlock(
            WorkflowCallbackService.TOOL_NAME,
            "{\"purpose\":\"clone repo\",\"resume_instructions\":\"summarize commits\"}"));
    assertTrue(
        guard.shouldBlock(
            WorkflowCallbackService.TOOL_NAME,
            "{\"purpose\":\"clone repository\",\"resume_instructions\":\"summarize last commits\"}"));
  }
}
