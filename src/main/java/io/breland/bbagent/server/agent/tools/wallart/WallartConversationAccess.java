package io.breland.bbagent.server.agent.tools.wallart;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WallartConversationAccess {
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final WallartMcpProperties properties;

  public WallartConversationAccess(
      BBHttpClientWrapper bbHttpClientWrapper, WallartMcpProperties properties) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.properties = properties;
  }

  public boolean isAllowed(IncomingMessage message) {
    if (message == null || !message.isBlueBubblesTransport()) {
      return false;
    }
    if (matchesAllowedParticipant(message.sender())) {
      return true;
    }
    if (!message.isGroup()) {
      return false;
    }
    try {
      JsonNode conversation = bbHttpClientWrapper.getConversationInfoJson(message.chatGuid());
      JsonNode participants = conversation == null ? null : conversation.get("participants");
      if (participants == null || !participants.isArray()) {
        return false;
      }
      for (JsonNode participant : participants) {
        if (matchesAllowedParticipant(participant.path("address").asText(null))
            || matchesAllowedParticipant(participant.path("handle").asText(null))) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      log.warn(
          "Unable to verify wallart MCP access for group conversation: {}",
          e.getClass().getSimpleName());
    }
    return false;
  }

  private boolean matchesAllowedParticipant(String candidate) {
    return AgentAccountIdentifiers.equivalent(candidate, properties.getAllowedParticipant());
  }
}
