package io.breland.bbagent.server.nativeapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.nativeapp.NativeAppSessionEntity;
import io.breland.bbagent.server.agent.persistence.nativeapp.NativeAppSessionRepository;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import io.breland.bbagent.server.texting.TextingNumberService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

class NativeAppSessionServiceTest {

  @Test
  void claimStartTokenLinksIncomingSenderAndStripsCode() {
    NativeAppSessionRepository sessionRepository = mock(NativeAppSessionRepository.class);
    AgentAccountResolver accountResolver = mock(AgentAccountResolver.class);
    String startToken = "BC-ABCDEFGH";
    NativeAppSessionEntity session =
        new NativeAppSessionEntity(
            DigestUtils.sha256Hex("session-token"),
            "account-1",
            "5d8f8306-5970-3d7e-872b-44cb8d27690c",
            DigestUtils.sha256Hex(startToken),
            Instant.now().plusSeconds(3600),
            Instant.now().plusSeconds(7200),
            Instant.now(),
            Instant.now());
    when(sessionRepository.findByStartTokenHash(DigestUtils.sha256Hex(startToken)))
        .thenReturn(Optional.of(session));
    NativeAppSessionService service =
        new NativeAppSessionService(
            sessionRepository,
            mock(AgentAccountRepository.class),
            accountResolver,
            mock(SubscriptionService.class),
            mock(TextingNumberService.class),
            365,
            365);
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "chat-guid",
            "message-guid",
            null,
            "Hi BlueChatAI, let's start. Code: " + startToken,
            false,
            "iMessage",
            "+15551234567",
            false,
            Instant.now(),
            List.of(),
            false);

    IncomingMessage cleaned = service.claimStartToken(message);

    verify(accountResolver).linkIncomingMessageToAccount("account-1", message);
    verify(sessionRepository).save(session);
    assertThat(session.getClaimedAt()).isNotNull();
    assertThat(cleaned.text()).isEqualTo("Hi BlueChatAI, let's start.");
  }
}
