package io.breland.bbagent.server.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void responseStatusExceptionPreservesStatusAndReason() {
    ResponseEntity<String> response =
        handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider request failed"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody()).isEqualTo("Provider request failed");
  }

  @Test
  void genericExceptionStillReturnsInternalServerError() {
    ResponseEntity<String> response = handler.handleGenericException(new RuntimeException("boom"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isEqualTo("boom");
  }
}
