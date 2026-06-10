package io.breland.bbagent.server.agent.transport.lxmf;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    if (destinationHash == null || destinationHash.isBlank()) {
      log.warn("Cannot send LXMF message without destination hash");
      return false;
    }
    if (content == null || content.isBlank()) {
      log.warn("Cannot send blank LXMF message");
      return false;
    }
    log.info("Sending LXMF bridge message contentLength={}", content.length());
    try {
      restClient
          .post()
          .uri("/api/v1/messages/send")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(headers -> headers.setBearerAuth(bridgeSecret))
          .body(new SendTextRequest(destinationHash, content))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (Exception e) {
      log.warn("Failed to send LXMF message", e);
      return false;
    }
  }

  private record SendTextRequest(
      @JsonProperty("destination_hash") String destinationHash, String content) {}
}
