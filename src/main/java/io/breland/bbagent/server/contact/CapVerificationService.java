package io.breland.bbagent.server.contact;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
@Slf4j
public class CapVerificationService {
  private final ContactProperties properties;
  private final WebClient.Builder webClientBuilder;

  public CapVerificationService(ContactProperties properties, WebClient.Builder webClientBuilder) {
    this.properties = properties;
    this.webClientBuilder = webClientBuilder;
  }

  public boolean isConfigured() {
    return StringUtils.isNoneBlank(
        properties.getCapBaseUrl(), properties.getCapSiteKey(), properties.getCapSecretKey());
  }

  public String apiEndpoint() {
    if (StringUtils.isAnyBlank(properties.getCapBaseUrl(), properties.getCapSiteKey())) {
      return null;
    }
    String baseUrl = StringUtils.stripEnd(StringUtils.trimToEmpty(properties.getCapBaseUrl()), "/");
    return baseUrl + "/" + properties.getCapSiteKey() + "/";
  }

  public boolean verify(String token) {
    if (!isConfigured() || StringUtils.isBlank(token)) {
      return false;
    }
    String verifyUrl = apiEndpoint() + "siteverify";
    try {
      CapVerifyResponse response =
          webClientBuilder
              .build()
              .post()
              .uri(verifyUrl)
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(new CapVerifyRequest(properties.getCapSecretKey(), token.trim()))
              .retrieve()
              .bodyToMono(CapVerifyResponse.class)
              .block(Duration.ofSeconds(Math.max(1, properties.getCapTimeoutSeconds())));
      return response != null && response.success();
    } catch (WebClientResponseException e) {
      log.warn("Cap verification failed with status {}", e.getStatusCode());
      return false;
    } catch (RuntimeException e) {
      log.warn("Cap verification failed: {}", e.toString());
      return false;
    }
  }

  private record CapVerifyRequest(String secret, String response) {}

  private record CapVerifyResponse(boolean success) {}
}
