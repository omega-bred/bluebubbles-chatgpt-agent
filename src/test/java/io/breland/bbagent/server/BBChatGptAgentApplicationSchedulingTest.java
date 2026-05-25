package io.breland.bbagent.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

class BBChatGptAgentApplicationSchedulingTest {

  @Test
  void enablesScheduledHealthChecks() {
    assertTrue(BBChatGptAgentApplication.class.isAnnotationPresent(EnableScheduling.class));
  }
}
