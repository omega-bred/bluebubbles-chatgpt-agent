package io.breland.bbagent.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BBChatGptAgentApplicationTests {

  @Value("${bbagent.memory.group.qa.max-search-terms}")
  private int maxSearchTerms;

  @Value("${bbagent.memory.group.qa.search-page-size}")
  private int searchPageSize;

  @Value("${bbagent.memory.group.qa.max-history-pages}")
  private int maxHistoryPages;

  @Value("${bbagent.memory.group.qa.neighbor-message-count}")
  private int neighborMessageCount;

  @Value("${bbagent.memory.group.qa.max-batch-messages}")
  private int maxBatchMessages;

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
    assertThat(maxSearchTerms).isEqualTo(5);
    assertThat(searchPageSize).isEqualTo(500);
    assertThat(maxHistoryPages).isEqualTo(100);
    assertThat(neighborMessageCount).isEqualTo(3);
    assertThat(maxBatchMessages).isEqualTo(100);
    assertThat(maxBatchCharacters).isEqualTo(60_000);
    assertThat(maxModelBatches).isEqualTo(5);
    assertThat(maxAggregateCharacters).isEqualTo(300_000);
    assertThat(requestTimeout).isEqualTo(Duration.ofSeconds(90));
  }
}
