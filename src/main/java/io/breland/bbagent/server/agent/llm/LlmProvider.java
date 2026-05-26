package io.breland.bbagent.server.agent.llm;

import com.openai.models.responses.Response;

public interface LlmProvider {
  String providerKey();

  Response createResponse(LlmRequest request);
}
