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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryMessageMapper {
  private static final String YOU = "you";
  private static final String UNKNOWN_PARTICIPANT = "unknown participant";
  private static final int MAX_PARTICIPANT_LABEL_CHARACTERS = 160;
  private static final int MAX_PARTICIPANT_LABEL_WORDS = 8;

  private final AgentAccountResolver accountResolver;

  public ConversationHistoryMessageMapper(AgentAccountResolver accountResolver) {
    this.accountResolver = accountResolver;
  }

  public Optional<QuestionMessage> fromBlueBubbles(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner rawMessage, String requestingAccountId) {
    return fromBlueBubbles(rawMessage, requestingAccountId, new MappingSession());
  }

  public Optional<QuestionMessage> fromBlueBubbles(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner rawMessage,
      String requestingAccountId,
      MappingSession session) {
    if (session == null) {
      throw new IllegalArgumentException("mapping session must not be null");
    }
    IncomingMessage incoming = IncomingMessage.create(rawMessage);
    if (!eligible(incoming)
        || StringUtils.isBlank(incoming.messageGuid())
        || incoming.timestamp() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new QuestionMessage(
            incoming.messageGuid(),
            participantLabel(incoming, requestingAccountId, session),
            incoming.timestamp(),
            incoming.text().trim()));
  }

  public Optional<QuestionMessage> fromJournal(JournalMessage message, String requestingAccountId) {
    return fromJournal(message, requestingAccountId, new MappingSession());
  }

  public Optional<QuestionMessage> fromJournal(
      JournalMessage message, String requestingAccountId, MappingSession session) {
    if (session == null) {
      throw new IllegalArgumentException("mapping session must not be null");
    }
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
            participantLabel(message.senderAccountId(), requestingAccountId, session),
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

  private String participantLabel(
      IncomingMessage message, String requestingAccountId, MappingSession session) {
    Optional<AgentAccountIdentifiers.NormalizedIdentifier> normalizedIdentity =
        AgentAccountIdentifiers.normalizeMessageIdentity(
            message.transportOrDefault(), message.sender());
    if (normalizedIdentity.isEmpty()) {
      return maskedIdentity(message);
    }
    AgentAccountIdentifiers.NormalizedIdentifier identity = normalizedIdentity.get();
    String cacheKey = identity.type() + '\0' + identity.value();
    Optional<ResolvedAccount> account =
        session.identityAccounts.computeIfAbsent(
            cacheKey, ignored -> accountResolver.resolve(message));
    if (account.isPresent()) {
      return participantLabel(account.get(), requestingAccountId)
          .orElseGet(() -> maskedIdentity(message));
    }
    return maskedIdentity(message);
  }

  private String participantLabel(
      String senderAccountId, String requestingAccountId, MappingSession session) {
    if (StringUtils.equals(senderAccountId, requestingAccountId)) {
      return YOU;
    }
    Optional<ResolvedAccount> resolved =
        session.accountIds.computeIfAbsent(senderAccountId, accountResolver::resolveById);
    return resolved
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
    String globalContactName = StringUtils.trimToNull(account.account().getGlobalContactName());
    return isSafeParticipantLabel(globalContactName)
        ? Optional.of(globalContactName)
        : Optional.empty();
  }

  private static boolean isSafeParticipantLabel(String label) {
    if (StringUtils.isBlank(label) || label.length() > MAX_PARTICIPANT_LABEL_CHARACTERS) {
      return false;
    }
    return participantLabelTokenCount(label) <= MAX_PARTICIPANT_LABEL_WORDS;
  }

  private static int participantLabelTokenCount(String label) {
    int tokenCount = 0;
    int index = 0;
    while (index < label.length()) {
      while (index < label.length() && !Character.isLetterOrDigit(label.charAt(index))) {
        index++;
      }
      if (index == label.length()) {
        break;
      }
      tokenCount++;
      index++;
      while (index < label.length()) {
        char character = label.charAt(index);
        if (Character.isLetterOrDigit(character)
            || (isParticipantLabelTokenSeparator(character)
                && index + 1 < label.length()
                && Character.isLetterOrDigit(label.charAt(index + 1)))) {
          index++;
        } else {
          break;
        }
      }
    }
    return tokenCount;
  }

  private static boolean isParticipantLabelTokenSeparator(char value) {
    return ",.'/-".indexOf(value) >= 0;
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

  public static final class MappingSession {
    private final Map<String, Optional<ResolvedAccount>> identityAccounts = new LinkedHashMap<>();
    private final Map<String, Optional<ResolvedAccount>> accountIds = new LinkedHashMap<>();
  }
}
