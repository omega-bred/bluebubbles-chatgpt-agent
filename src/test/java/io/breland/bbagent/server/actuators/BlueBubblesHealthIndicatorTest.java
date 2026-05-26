package io.breland.bbagent.server.actuators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.junit.jupiter.api.Test;

class BlueBubblesHealthIndicatorTest {
  @Test
  void pingFailureStoresDegradedHealthWhenLoginStatusUnavailable() {
    BBHttpClientWrapper bbHttpClientWrapper = mock(BBHttpClientWrapper.class);
    when(bbHttpClientWrapper.ping()).thenReturn(false);

    BlueBubblesHealthIndicator indicator =
        new BlueBubblesHealthIndicator(bbHttpClientWrapper, null);

    assertThatCode(indicator::pingBB).doesNotThrowAnyException();
    assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(indicator.health().getDetails())
        .containsEntry("ping", "failed")
        .containsEntry("icloudLoginStatus", "unknown")
        .containsEntry("error", "Ping failed");
  }

  @Test
  void nullExceptionMessageUsesExceptionClassName() {
    BBHttpClientWrapper bbHttpClientWrapper = mock(BBHttpClientWrapper.class);
    when(bbHttpClientWrapper.ping()).thenThrow(new IllegalStateException());

    BlueBubblesHealthIndicator indicator =
        new BlueBubblesHealthIndicator(bbHttpClientWrapper, null);

    assertThatCode(indicator::pingBB).doesNotThrowAnyException();
    assertThat(indicator.health().getDetails()).containsEntry("error", "IllegalStateException");
  }
}
