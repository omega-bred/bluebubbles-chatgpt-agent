package io.breland.bbagent.server.agent.decisions;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.decisions")
@Getter
@Setter
public class DecisionsProperties {
  private boolean enabled = true;
  private String model = "openai/gpt-6-luna";
  private Duration timeout = Duration.ofSeconds(3);
  private Duration failureBackoff = Duration.ofSeconds(30);
  private int maxInputCharacters = 64_000;
  private boolean participationEnabled = true;
  private boolean reactionsEnabled = true;
  private boolean toolPreloadingEnabled = true;
  private boolean feedbackEnabled = true;
  private boolean memoryReviewEnabled = true;
  private boolean catchupEnabled = true;
  private boolean termsEnabled = true;
}
