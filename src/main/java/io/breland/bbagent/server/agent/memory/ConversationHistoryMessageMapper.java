package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryMessageMapper {
  private static final String YOU = "you";
  private static final String UNKNOWN_PARTICIPANT = "unknown participant";

  private final AgentAccountResolver accountResolver;

  public ConversationHistoryMessageMapper(AgentAccountResolver accountResolver) {
    this.accountResolver = accountResolver;
  }

  public Optional<QuestionMessage> fromBlueBubbles(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner rawMessage, String requestingAccountId) {
    IncomingMessage incoming = IncomingMessage.create(rawMessage);
    if (!eligible(incoming)
        || StringUtils.isBlank(incoming.messageGuid())
        || incoming.timestamp() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new QuestionMessage(
            incoming.messageGuid(),
            participantLabel(incoming, requestingAccountId),
            incoming.timestamp(),
            incoming.text().trim()));
  }

  public Optional<QuestionMessage> fromJournal(JournalMessage message, String requestingAccountId) {
    if (message == null
        || message.fromAgent()
        || message.systemMessage()
        || StringUtils.isBlank(message.messageGuid())
        || StringUtils.isBlank(message.text())
        || message.sourceTimestamp() == null
        || MessageReactionSupport.isReactionMessage(message.text())) {
      return Optional.empty();
    }
    return Optional.of(
        new QuestionMessage(
            message.messageGuid(),
            participantLabel(message.senderAccountId(), requestingAccountId),
            message.sourceTimestamp(),
            message.text().trim()));
  }

  private boolean eligible(IncomingMessage message) {
    if (message == null
        || Boolean.TRUE.equals(message.fromMe())
        || message.isSystemMessage()
        || StringUtils.isBlank(IncomingMessage.chatGuidOrNull(message))
        || StringUtils.isBlank(message.text())
        || MessageReactionSupport.isReactionMessage(message.text())) {
      return false;
    }
    if (message.isBlueBubblesTransport()) {
      return message.service() == null
          || BBMessageAgent.IMESSAGE_SERVICE.equalsIgnoreCase(message.service());
    }
    return message.isLxmfTransport();
  }

  private String participantLabel(IncomingMessage message, String requestingAccountId) {
    Optional<ResolvedAccount> account = accountResolver.resolve(message);
    if (account.isPresent()) {
      return participantLabel(account.get(), requestingAccountId)
          .orElseGet(() -> maskedIdentity(message));
    }
    return maskedIdentity(message);
  }

  private String participantLabel(String senderAccountId, String requestingAccountId) {
    if (StringUtils.equals(senderAccountId, requestingAccountId)) {
      return YOU;
    }
    return accountResolver
        .resolveById(senderAccountId)
        .flatMap(account -> participantLabel(account, requestingAccountId))
        .orElse(UNKNOWN_PARTICIPANT);
  }

  private Optional<String> participantLabel(ResolvedAccount account, String requestingAccountId) {
    if (account == null || account.account() == null) {
      return Optional.empty();
    }
    if (StringUtils.equals(account.account().getAccountId(), requestingAccountId)) {
      return Optional.of(YOU);
    }
    return Optional.ofNullable(StringUtils.trimToNull(account.account().getGlobalContactName()));
  }

  private String maskedIdentity(IncomingMessage message) {
    String normalizedIdentity =
        AgentAccountIdentifiers.normalizeMessageIdentity(
                message.transportOrDefault(), message.sender())
            .map(AgentAccountIdentifiers.NormalizedIdentifier::value)
            .orElse(null);
    if (StringUtils.isBlank(normalizedIdentity)) {
      return UNKNOWN_PARTICIPANT;
    }
    StringBuilder alphanumeric = new StringBuilder();
    normalizedIdentity
        .codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(alphanumeric::appendCodePoint);
    if (alphanumeric.length() < 4) {
      return UNKNOWN_PARTICIPANT;
    }
    return "participant ending " + alphanumeric.substring(alphanumeric.length() - 4);
  }
}
