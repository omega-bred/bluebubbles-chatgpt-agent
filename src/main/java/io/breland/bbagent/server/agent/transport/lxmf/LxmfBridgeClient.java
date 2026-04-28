package io.breland.bbagent.server.agent.transport.lxmf;

import java.util.LinkedHashMap;
import java.util.Map;
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
    log.info("Sending message to Lxmf bridge {}: {}", destinationHash, content);
    if (destinationHash == null || destinationHash.isBlank()) {
      log.warn("Cannot send LXMF message without destination hash");
      return false;
    }
    if (content == null || content.isBlank()) {
      log.warn("Cannot send blank LXMF message");
      return false;
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("destination_hash", destinationHash);
    request.put("content", content);
    try {
      restClient
          .post()
          .uri("/api/v1/messages/send")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(headers -> headers.setBearerAuth(bridgeSecret))
          .body(request)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (Exception e) {
      log.warn("Failed to send LXMF message to {}", destinationHash, e);
      return false;
    }
  }
}
