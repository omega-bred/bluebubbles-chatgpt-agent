package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantDescriptor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantHint;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesContactIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConversationParticipantResolver {
  private static final String YOU = "you";
  private static final String UNKNOWN_PARTICIPANT = "unknown participant";
  private static final int MAX_PARTICIPANT_LABEL_CHARACTERS = 160;
  private static final int MAX_PARTICIPANT_LABEL_WORDS = 8;
  private static final int MAX_HINT_IDENTITY_CHARACTERS = 512;

  private final AgentAccountResolver accountResolver;
  private final BBHttpClientWrapper bb;
  private final Clock clock;

  @Autowired
  public ConversationParticipantResolver(
      AgentAccountResolver accountResolver, BBHttpClientWrapper bb) {
    this(accountResolver, bb, Clock.systemUTC());
  }

  ConversationParticipantResolver(
      AgentAccountResolver accountResolver, BBHttpClientWrapper bb, Clock clock) {
    this.accountResolver = Objects.requireNonNull(accountResolver, "account resolver");
    this.bb = Objects.requireNonNull(bb, "BlueBubbles client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  ParticipantDescriptor resolve(
      IncomingMessage message, String requestingAccountId, Session session) {
    Objects.requireNonNull(message, "incoming message");
    requireSessionAndRequester(requestingAccountId, session);
    Optional<AgentAccountIdentifiers.NormalizedIdentifier> normalizedIdentity =
        AgentAccountIdentifiers.normalizeMessageIdentity(
            message.transportOrDefault(), message.sender());
    if (normalizedIdentity.isEmpty()) {
      return new ParticipantDescriptor(UNKNOWN_PARTICIPANT, null);
    }
    AgentAccountIdentifiers.NormalizedIdentifier identity = normalizedIdentity.get();
    String cacheKey = identity.type() + '\0' + identity.value();
    Optional<ResolvedAccount> account =
        session.identityAccounts.computeIfAbsent(
            cacheKey, ignored -> accountResolver.resolve(message));
    Optional<String> accountLabel =
        account.flatMap(resolved -> accountLabel(resolved, requestingAccountId));
    if (accountLabel.isPresent()) {
      return new ParticipantDescriptor(accountLabel.get(), null);
    }
    if (message.isBlueBubblesTransport()) {
      Optional<String> contactLabel = contactLabel(identity.value(), session);
      if (contactLabel.isPresent()) {
        return new ParticipantDescriptor(contactLabel.get(), null);
      }
    }
    return masked(identity.value());
  }

  ParticipantDescriptor resolve(
      String senderAccountId, String requestingAccountId, Session session) {
    requireSessionAndRequester(requestingAccountId, session);
    if (StringUtils.equals(senderAccountId, requestingAccountId)) {
      return new ParticipantDescriptor(YOU, null);
    }
    if (StringUtils.isBlank(senderAccountId)) {
      return new ParticipantDescriptor(UNKNOWN_PARTICIPANT, null);
    }
    Optional<ResolvedAccount> account =
        session.accountIds.computeIfAbsent(senderAccountId, accountResolver::resolveById);
    return account
        .flatMap(resolved -> accountLabel(resolved, requestingAccountId))
        .map(label -> new ParticipantDescriptor(label, null))
        .orElseGet(() -> new ParticipantDescriptor(UNKNOWN_PARTICIPANT, null));
  }

  private Optional<String> accountLabel(ResolvedAccount resolved, String requestingAccountId) {
    if (resolved == null || resolved.account() == null) {
      return Optional.empty();
    }
    if (StringUtils.equals(resolved.account().getAccountId(), requestingAccountId)) {
      return Optional.of(YOU);
    }
    String globalContactName = StringUtils.trimToNull(resolved.account().getGlobalContactName());
    if (isSafeParticipantLabel(globalContactName)) {
      return Optional.of(globalContactName);
    }
    String websiteDisplayName = StringUtils.trimToNull(resolved.account().getWebsiteDisplayName());
    return isSafeParticipantLabel(websiteDisplayName)
        ? Optional.of(websiteDisplayName)
        : Optional.empty();
  }

  private Optional<String> contactLabel(String normalizedIdentity, Session session) {
    return contacts(session).stream()
        .filter(
            contact ->
                contact.addresses().stream()
                    .anyMatch(
                        address -> AgentAccountIdentifiers.equivalent(address, normalizedIdentity)))
        .map(BlueBubblesContactIdentity::displayName)
        .filter(ConversationParticipantResolver::isSafeParticipantLabel)
        .findFirst();
  }

  private List<BlueBubblesContactIdentity> contacts(Session session) {
    if (session.contactLookupAttempted) {
      return session.contacts;
    }
    session.contactLookupAttempted = true;
    Duration remaining = Duration.between(clock.instant(), session.deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      session.contacts = List.of();
      return session.contacts;
    }
    try {
      session.contacts = List.copyOf(bb.getContactIdentitiesForQuestion(remaining));
    } catch (RuntimeException ignored) {
      session.contacts = List.of();
    }
    return session.contacts;
  }

  private ParticipantDescriptor masked(String normalizedIdentity) {
    StringBuilder alphanumeric = new StringBuilder();
    normalizedIdentity
        .codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(alphanumeric::appendCodePoint);
    if (alphanumeric.length() < 4) {
      return new ParticipantDescriptor(UNKNOWN_PARTICIPANT, null);
    }
    String label = "participant ending " + alphanumeric.substring(alphanumeric.length() - 4);
    ParticipantHint hint =
        normalizedIdentity.length() <= MAX_HINT_IDENTITY_CHARACTERS
            ? new ParticipantHint(label, normalizedIdentity)
            : null;
    return new ParticipantDescriptor(label, hint);
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

  private static void requireSessionAndRequester(String requestingAccountId, Session session) {
    if (StringUtils.isBlank(requestingAccountId)) {
      throw new IllegalArgumentException("requesting account id must not be blank");
    }
    Objects.requireNonNull(session, "participant resolution session");
  }

  static final class Session {
    private final Instant deadline;
    private final Map<String, Optional<ResolvedAccount>> identityAccounts = new LinkedHashMap<>();
    private final Map<String, Optional<ResolvedAccount>> accountIds = new LinkedHashMap<>();
    private boolean contactLookupAttempted;
    private List<BlueBubblesContactIdentity> contacts = List.of();

    Session(Instant deadline) {
      this.deadline = Objects.requireNonNull(deadline, "participant resolution deadline");
    }
  }
}
