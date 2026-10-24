package io.breland.bbagent.server.agent.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TermsGateConfigurationTest {
  @Test
  void requiresAgreementWhenConfigurationIsOmitted() {
    checkConfiguration(true);
  }

  @Test
  void explicitConfigurationCanDisableAgreement() {
    checkConfiguration(false, "bbagent.terms.required=false");
  }

  @Test
  void environmentFlagCanDisableAgreement() {
    checkConfiguration(false, "BBAGENT_TERMS_REQUIRED=false");
  }

  @Test
  void environmentFlagCanRequireAgreement() {
    checkConfiguration(true, "BBAGENT_TERMS_REQUIRED=true");
  }

  private void checkConfiguration(boolean expectedRequired, String... properties) {
    AgentProfileService profile = mock(AgentProfileService.class);
    AgentOutboundService outbound = mock(AgentOutboundService.class);
    TermsAgreementValidator validator = mock(TermsAgreementValidator.class);
    AgentAccountEntity account = new AgentAccountEntity("account", Instant.EPOCH, Instant.EPOCH);
    IncomingMessage message =
        new IncomingMessage(
            "chat",
            "message",
            null,
            "hello",
            false,
            "iMessage",
            "sender",
            false,
            Instant.EPOCH,
            List.of(),
            false);
    when(profile.resolveOrCreateAccount(message))
        .thenReturn(Optional.of(new ResolvedAccount(account, List.of())));

    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withBean(AgentProfileService.class, () -> profile)
        .withBean(AgentOutboundService.class, () -> outbound)
        .withBean(TermsAgreementValidator.class, () -> validator)
        .withUserConfiguration(TermsGate.class)
        .withPropertyValues(properties)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(TermsGate.class)
                          .handle(new ConversationState(), message, (state, incoming) -> {}))
                  .isEqualTo(expectedRequired);
              assertThat(account.getTermsAcceptedAt()).isNull();
              verify(profile, never()).acceptTerms(any());
              verifyNoInteractions(validator);
              if (expectedRequired) {
                verify(outbound).sendThreadAwareTextUnmetered(eq(message), contains("Reply YES"));
              } else {
                verifyNoInteractions(outbound);
              }
            });
  }
}
