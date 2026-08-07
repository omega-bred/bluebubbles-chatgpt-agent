package io.breland.bbagent.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;

public final class Jackson2WebClientConfigurer {
  private Jackson2WebClientConfigurer() {}

  @SuppressWarnings("removal")
  public static WebClient.Builder configure(WebClient.Builder builder, ObjectMapper objectMapper) {
    return builder.codecs(
        configurer -> {
          configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
          configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
        });
  }
}
