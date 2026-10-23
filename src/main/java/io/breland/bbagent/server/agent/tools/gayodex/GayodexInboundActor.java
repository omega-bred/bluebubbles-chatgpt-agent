package io.breland.bbagent.server.agent.tools.gayodex;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Establish provenance at ingress, before message text can be transformed by agent processing. */
@Component
@RequiredArgsConstructor
public class GayodexInboundActor {
  private final GayodexProperties properties;
  private final BBHttpClientWrapper blueBubbles;
  private final ObjectMapper mapper;

  public IncomingMessage authenticate(IncomingMessage incoming) {
    if (!properties.isConfigured() || phone(incoming).isEmpty()) return incoming;
    try {
      // The webhook is not an identity credential. Confirm its metadata and text against the
      // authenticated BlueBubbles API. Never trust a webhook-supplied attachment locator/bytes.
      var stored = blueBubbles.getMessage(incoming.messageGuid());
      if (stored == null
          || !UUID.fromString(incoming.messageGuid()).equals(stored.getGuid())
          || !Boolean.FALSE.equals(stored.getIsFromMe())
          || Boolean.TRUE.equals(stored.getIsSystemMessage())
          || Boolean.TRUE.equals(stored.getIsServiceMessage())
          || !Objects.equals(incoming.text(), stored.getText())
          || !Objects.equals(incoming.timestamp().toEpochMilli(), stored.getDateCreated())
          || stored.getChats() == null
          || stored.getChats().stream()
              .noneMatch(
                  chat ->
                      chat != null
                          && Objects.equals(
                              normalizedChat(incoming.chatGuid(), incoming.service()),
                              normalizedChat(chat.getGuid(), incoming.service())))) return incoming;
      var handle = mapper.valueToTree(stored.getHandle());
      if (!handle.path("address").isTextual()
          || !AgentAccountIdentifiers.equivalent(incoming.sender(), handle.path("address").asText())
          || !incoming.service().equalsIgnoreCase(handle.path("service").asText())) return incoming;
      var attachments = IncomingAttachment.fromHistory(stored.getAttachments(), mapper);
      return incoming.withVerifiedBlueBubblesInbound(attachments);
    } catch (Exception ignored) {
      // No exception detail: client errors may contain transport identities or credentials.
      return incoming;
    }
  }

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

  private static String normalizedChat(String guid, String service) {
    return BBHttpClientWrapper.normalizeDirectAnyChatGuid(guid, service);
  }
}
