package io.breland.bbagent.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BBChatGptAgentApplicationTests {

  @Value("${bbagent.memory.group.qa.window-message-count}")
  private int windowMessageCount;

  @Value("${bbagent.memory.group.qa.max-history-pages}")
  private int maxHistoryPages;

  @Value("${bbagent.memory.group.qa.max-batch-characters}")
  private int maxBatchCharacters;

  @Value("${bbagent.memory.group.qa.max-model-batches}")
  private int maxModelBatches;

  @Value("${bbagent.memory.group.qa.max-aggregate-characters}")
  private int maxAggregateCharacters;

  @Value("${bbagent.memory.group.qa.request-timeout}")
  private Duration requestTimeout;

  @Test
  void contextLoadsWithGroupQuestionAnsweringLimits() {
    assertThat(windowMessageCount).isEqualTo(500);
    assertThat(maxHistoryPages).isEqualTo(100);
    assertThat(maxBatchCharacters).isEqualTo(300_000);
    assertThat(maxModelBatches).isEqualTo(5);
    assertThat(maxAggregateCharacters).isEqualTo(600_000);
    assertThat(requestTimeout).isEqualTo(Duration.ofSeconds(90));
  }
}
