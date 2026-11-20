package io.breland.bbagent.server.agent.tools.gayodex;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import java.util.Optional;
import java.util.UUID;

/** Accepts only verified BlueBubbles phone identities for Gayodex. */
public final class GayodexInboundActor {
  private GayodexInboundActor() {}

  public static Optional<String> verifiedPhone(IncomingMessage message) {
    return message != null && message.verifiedBlueBubblesInbound()
        ? phone(message)
        : Optional.empty();
  }

  private static Optional<String> phone(IncomingMessage message) {
    if (message == null
        || !IncomingMessage.TRANSPORT_BLUEBUBBLES.equals(message.transport())
        || !Boolean.FALSE.equals(message.fromMe())
        || message.isSystemMessage()
        || !"iMessage".equalsIgnoreCase(message.service())
        || message.timestamp() == null
        || message.chatGuid() == null
        || message.chatGuid().isBlank()
        || message.messageGuid() == null
        || message.sender() == null
        || !message.sender().matches("(?:tel:)?\\+?[0-9 () .-]+")) return Optional.empty();
    try {
      UUID.fromString(message.messageGuid()); // Reject synthetic/scheduled message identifiers.
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
    return AgentAccountIdentifiers.normalizeMessageIdentity(message.transport(), message.sender())
        .filter(id -> AgentAccountIdentifiers.IMESSAGE_PHONE.equals(id.type()))
        .map(AgentAccountIdentifiers.NormalizedIdentifier::value)
        .filter(value -> value.matches("\\+[1-9][0-9]{7,14}"));
  }
}
