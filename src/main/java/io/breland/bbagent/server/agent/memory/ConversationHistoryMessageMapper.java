package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantDescriptor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import java.time.Instant;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryMessageMapper {
  private final ConversationParticipantResolver participantResolver;

  public ConversationHistoryMessageMapper(ConversationParticipantResolver participantResolver) {
    this.participantResolver = participantResolver;
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
    ParticipantDescriptor participant =
        participantResolver.resolve(incoming, requestingAccountId, session.participants());
    return Optional.of(
        new QuestionMessage(
            incoming.messageGuid(),
            participant.label(),
            incoming.timestamp(),
            incoming.text().trim(),
            participant.hint()));
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
    ParticipantDescriptor participant =
        participantResolver.resolve(
            message.senderAccountId(), requestingAccountId, session.participants());
    return Optional.of(
        new QuestionMessage(
            message.messageGuid(),
            participant.label(),
            message.sourceTimestamp(),
            message.text().trim(),
            participant.hint()));
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

  public static final class MappingSession {
    private final ConversationParticipantResolver.Session participants;

    public MappingSession() {
      this(Instant.MAX);
    }

    public MappingSession(Instant deadline) {
      participants = new ConversationParticipantResolver.Session(deadline);
    }

    ConversationParticipantResolver.Session participants() {
      return participants;
    }
  }
}
