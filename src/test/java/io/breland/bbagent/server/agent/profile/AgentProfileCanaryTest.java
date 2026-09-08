package io.breland.bbagent.server.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.canary.AgentCanaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentProfileCanaryTest {
  private final AgentCanaryService canaryService = mock(AgentCanaryService.class);
  private final AgentProfileService profileService =
      new AgentProfileService(mock(AgentSettingsStore.class), null, canaryService);

  @Test
  void nullMessageIsNotACanaryAndDoesNotPerformALookup() {
    assertThat(profileService.isCanaryAccount(null)).isFalse();

    verifyNoInteractions(canaryService);
  }

  @Test
  void missingCanaryServiceTreatsMessagesAsNormalAccounts() {
    AgentProfileService withoutCanaryService =
        new AgentProfileService(mock(AgentSettingsStore.class), null, null);

    assertThat(withoutCanaryService.isCanaryAccount(mock(IncomingMessage.class))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void returnsCanaryLookupResult(boolean canary) {
    IncomingMessage message = mock(IncomingMessage.class);
    when(canaryService.isCanaryAccount(message)).thenReturn(canary);

    assertThat(profileService.isCanaryAccount(message)).isEqualTo(canary);

    verify(canaryService).isCanaryAccount(message);
  }

  @Test
  void lookupFailureStillTreatsMessageAsANormalAccount() {
    IncomingMessage message = mock(IncomingMessage.class);
    when(canaryService.isCanaryAccount(message))
        .thenThrow(new IllegalStateException("Canary lookup unavailable"));

    assertThat(profileService.isCanaryAccount(message)).isFalse();
  }
}
