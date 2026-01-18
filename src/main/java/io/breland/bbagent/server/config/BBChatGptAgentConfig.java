package io.breland.bbagent.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootConfiguration
@Slf4j
public class BBChatGptAgentConfig {

  @Bean
  public RestClient.Builder restClientBuilder() {
    return RestClient.builder();
  }

  @Bean
  public ObjectMapper getObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }
}
