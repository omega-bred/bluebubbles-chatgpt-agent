package io.breland.bbagent.server.agent.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public final class OpenAiClientProvider implements Supplier<OpenAIClient> {
  private OpenAIClient client;

  @Override
  public synchronized OpenAIClient get() {
    if (client == null) {
      client =
          OpenAIOkHttpClient.fromEnv()
              .withOptions(builder -> builder.timeout(Duration.ofSeconds(120)));
    }
    return client;
  }
}
