package io.breland.bbagent.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SubscriptionControllerTest {
  @Test
  void receiveWebhookReturnsUnauthorizedForFailedVerification() {
    SubscriptionService service = mock(SubscriptionService.class);
    HttpHeaders headers = new HttpHeaders();
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    when(service.receiveWebhook("btcpay", headers, body))
        .thenReturn(new SubscriptionProviderWebhookResponse().status("unauthorized"));

    ResponseEntity<SubscriptionProviderWebhookResponse> response =
        new SubscriptionController(service).subscriptionReceiveWebhook("btcpay", headers, body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void receiveWebhookReturnsServerErrorForFailedProcessing() {
    SubscriptionService service = mock(SubscriptionService.class);
    HttpHeaders headers = new HttpHeaders();
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    when(service.receiveWebhook("btcpay", headers, body))
        .thenReturn(new SubscriptionProviderWebhookResponse().status("failed"));

    ResponseEntity<SubscriptionProviderWebhookResponse> response =
        new SubscriptionController(service).subscriptionReceiveWebhook("btcpay", headers, body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
