package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolHandler {
  public String apply(ToolContext context, JsonNode arguments);
}
