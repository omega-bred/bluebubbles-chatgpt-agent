package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class ToolContextFactory {
  private final AgentOutboundService outboundService;
  private final ConversationStateStore conversationStateStore;
  private final ObjectMapper objectMapper;
  private final AgentProfileService profileService;

  public ToolContext create(IncomingMessage message, AgentWorkflowContext workflowContext) {
    return new ToolContext(
        outboundService,
        conversationStateStore,
        objectMapper,
        profileService,
        message,
        workflowContext);
  }
}
