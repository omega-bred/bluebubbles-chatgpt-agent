package io.breland.bbagent.server.agent.llm;

import com.openai.models.responses.Response;

public interface LlmProvider {
  Response createResponse(LlmRequest request);
}
