package io.breland.bbagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BBChatGptAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(BBChatGptAgentApplication.class, args);
  }
}
