package io.breland.bbagent.server.agent.transport.lxmf;

import io.breland.bbagent.generated.model.LxmfSendReceipt;
import io.breland.bbagent.generated.model.LxmfSendRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class LxmfBridgeClient {
  private final RestClient restClient;
  private final String bridgeSecret;

  public LxmfBridgeClient(
      RestClient.Builder restClientBuilder,
      @Value("${lxmf.bridge.base-url}") String baseUrl,
      @Value("${lxmf.bridge.secret}") String bridgeSecret) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.bridgeSecret = bridgeSecret;
  }

  public boolean sendText(String destinationHash, String content) {
    return sendText(destinationHash, content, null);
  }

  public boolean sendText(String destinationHash, String content, String deliveryId) {
    if (destinationHash == null || destinationHash.isBlank()) {
      log.warn("Cannot send LXMF message without destination hash");
      return false;
    }
    if (content == null || content.isBlank()) {
      log.warn("Cannot send blank LXMF message");
      return false;
    }
    var request = new LxmfSendRequest().destinationHash(destinationHash).content(content).title("");
    if (deliveryId != null) request.setRequestId(UUID.fromString(deliveryId));
    try {
      var receipt =
          restClient
              .post()
              .uri("/api/v1/messages/send")
              .contentType(MediaType.APPLICATION_JSON)
              .headers(headers -> headers.setBearerAuth(bridgeSecret))
              .body(request)
              .retrieve()
              .body(LxmfSendReceipt.class);
      return queued(receipt);
    } catch (Exception e) {
      log.warn("Failed to send LXMF message to {}", destinationHash, e);
      return false;
    }
  }

  public boolean confirmText(String deliveryId) {
    if (deliveryId == null) return false;
    try {
      return queued(
          restClient
              .get()
              .uri(
                  b ->
                      b.path("/api/v1/lxmfBridge/get.delivery")
                          .queryParam("request_id", deliveryId)
                          .build())
              .headers(headers -> headers.setBearerAuth(bridgeSecret))
              .retrieve()
              .body(LxmfSendReceipt.class));
    } catch (RuntimeException e) {
      log.debug("LXMF bridge receipt unavailable: {}", e.getClass().getSimpleName());
      return false;
    }
  }

  private static boolean queued(LxmfSendReceipt receipt) {
    return receipt != null && receipt.getStatus() == LxmfSendReceipt.StatusEnum.QUEUED;
  }
}
