package io.breland.bbagent.server.agent.tools;

import com.openai.models.responses.FunctionTool;

public record AgentTool(
    String name,
    String description,
    FunctionTool.Parameters parameters,
    boolean strict,
    ToolHandler handler) {

  public FunctionTool asFunctionTool() {
    return FunctionTool.builder()
        .name(name)
        .description(description)
        .parameters(parameters)
        .strict(strict)
        .build();
  }
}
