package io.breland.bbagent.server.agent.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TermsGateThreadRoutingTest {
  private final AgentOutboundService outbound = mock(AgentOutboundService.class);
  private final AgentProfileService profile = mock(AgentProfileService.class);
  private final TermsAgreementValidator validator = mock(TermsAgreementValidator.class);
  private final TermsGate.AcceptedMessageProcessor processor =
      mock(TermsGate.AcceptedMessageProcessor.class);
  private final TermsGate gate = new TermsGate(outbound, profile, validator, "https://example.com");

  @ParameterizedTest
  @MethodSource("threadTargets")
  void recordsGroupPromptWithExistingThreadOrOriginalMessageGuid(
      String threadGuid, String replyGuid, String messageGuid, String expectedRoot) {
    ConversationState state = spy(new ConversationState());
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "chat",
            messageGuid,
            threadGuid,
            "What can you do?",
            false,
            "iMessage",
            "sender",
            true,
            Instant.EPOCH,
            List.of(),
            null,
            null,
            replyGuid,
            false);
    when(profile.resolveOrCreateAccount(message))
        .thenReturn(
            Optional.of(
                new ResolvedAccount(
                    new AgentAccountEntity("account", Instant.EPOCH, Instant.EPOCH), List.of())));

    assertThat(gate.handle(state, message, processor)).isTrue();

    verify(state).recordPendingTermsAcceptance(message, expectedRoot);
    verifyNoInteractions(validator, processor);
  }

  private static Stream<Arguments> threadTargets() {
    return Stream.of(
        Arguments.of("thread", "reply", "message", "thread"),
        Arguments.of("thread", null, "message", "thread"),
        Arguments.of(null, "reply", "message", "reply"),
        Arguments.of("", "reply", "message", "reply"),
        Arguments.of(" \t", "reply", "message", "reply"),
        Arguments.of(null, null, "message", "message"),
        Arguments.of("", " \t", "message", "message"),
        Arguments.of(" thread ", "reply", "message", " thread "),
        Arguments.of(null, " reply ", "message", " reply "),
        Arguments.of(null, null, " message ", " message "),
        Arguments.of(null, null, "", ""),
        Arguments.of(null, null, null, null));
  }

  @Test
  void ignoresNullMessage() {
    assertThat(gate.handle(new ConversationState(), null, processor)).isFalse();

    verifyNoInteractions(outbound, profile, validator, processor);
  }
}
