package io.breland.bbagent.server.agent.tools;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import org.springframework.lang.Nullable;

public final class ToolContextFixture {
  private final IncomingMessage message;
  private AgentOutboundService outboundService = mock(AgentOutboundService.class);
  private ConversationStateStore conversationStateStore = new ConversationStateStore();
  private ObjectMapper objectMapper = new ObjectMapper();
  private @Nullable AgentProfile profile;
  private @Nullable AgentWorkflowContext workflowContext;

  private ToolContextFixture(IncomingMessage message) {
    this.message = message;
  }

  public static ToolContextFixture with(IncomingMessage message) {
    return new ToolContextFixture(message);
  }

  public ToolContextFixture outboundService(AgentOutboundService outboundService) {
    this.outboundService = outboundService;
    return this;
  }

  public ToolContextFixture objectMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    return this;
  }

  public ToolContextFixture profile(AgentProfile profile) {
    this.profile = profile;
    return this;
  }

  public ToolContextFixture workflowContext(AgentWorkflowContext workflowContext) {
    this.workflowContext = workflowContext;
    return this;
  }

  public ToolContextFixture conversationState(String chatGuid, ConversationState state) {
    conversationStateStore.conversations().put(chatGuid, state);
    return this;
  }

  public ToolContext build() {
    return new ToolContext(
        outboundService, conversationStateStore, objectMapper, profile, message, workflowContext);
  }
}
