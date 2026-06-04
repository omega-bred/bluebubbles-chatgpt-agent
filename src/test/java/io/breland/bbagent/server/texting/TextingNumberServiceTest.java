package io.breland.bbagent.server.texting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class TextingNumberServiceTest {

  @Test
  void returnsConfiguredNumberAndSmsUrl() {
    TextingNumberService service =
        new TextingNumberService(
            "+14158674956", "+1 (415) 867-4956", "Hi BlueChatAI, let's start.", 5, fixedClock());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.10");

    var response = service.getPublicNumber(request);

    assertThat(response.getPhoneNumberE164()).isEqualTo("+14158674956");
    assertThat(response.getDisplayNumber()).isEqualTo("+1 (415) 867-4956");
    assertThat(response.getDefaultMessage()).isEqualTo("Hi BlueChatAI, let's start.");
    assertThat(response.getSmsUrl())
        .isEqualTo("sms:+14158674956&body=Hi%20BlueChatAI%2C%20let%27s%20start.");
  }

  @Test
  void rateLimitsPerClientIpWithinOneSecondWindow() {
    TextingNumberService service =
        new TextingNumberService(
            "+14158674956", "+1 (415) 867-4956", "Hi BlueChatAI, let's start.", 2, fixedClock());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "198.51.100.1, 198.51.100.2");

    service.getPublicNumber(request);
    service.getPublicNumber(request);

    assertThatThrownBy(() -> service.getPublicNumber(request))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-06-03T12:00:00Z"), ZoneOffset.UTC);
  }
}
