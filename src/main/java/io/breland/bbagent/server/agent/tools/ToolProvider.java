package io.breland.bbagent.server.agent.tools;

@FunctionalInterface
public interface ToolProvider {
  AgentTool getTool();
}
