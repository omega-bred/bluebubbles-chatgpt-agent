package io.breland.bbagent.server.agent.transport.bb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ChatApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class BBHttpClientWrapperTypingTest {

  @Test
  void startTypingSubscribesWithoutWaitingForCompletion() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    AtomicBoolean subscribed = new AtomicBoolean();
    when(chatApi.apiV1ChatChatGuidTypingPost("group", "pw"))
        .thenReturn(Mono.<Void>never().doOnSubscribe(ignored -> subscribed.set(true)));
    BBHttpClientWrapper wrapper = wrapper(chatApi);

    assertThatCode(() -> wrapper.startTyping("group")).doesNotThrowAnyException();

    assertThat(subscribed).isTrue();
  }

  @Test
  void stopTypingSubscribesWithoutWaitingForCompletion() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    AtomicBoolean subscribed = new AtomicBoolean();
    when(chatApi.apiV1ChatChatGuidTypingDelete("group", "pw"))
        .thenReturn(Mono.<Void>never().doOnSubscribe(ignored -> subscribed.set(true)));
    BBHttpClientWrapper wrapper = wrapper(chatApi);

    assertThatCode(() -> wrapper.stopTyping("group")).doesNotThrowAnyException();

    assertThat(subscribed).isTrue();
  }

  @Test
  void typingFailuresNeverEscapeToTheTurn() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    when(chatApi.apiV1ChatChatGuidTypingPost("group", "pw"))
        .thenThrow(new IllegalStateException("private API unavailable"));
    when(chatApi.apiV1ChatChatGuidTypingDelete("group", "pw"))
        .thenReturn(Mono.error(new IllegalStateException("connection closed")));
    BBHttpClientWrapper wrapper = wrapper(chatApi);

    assertThatCode(() -> wrapper.startTyping("group")).doesNotThrowAnyException();
    assertThatCode(() -> wrapper.stopTyping("group")).doesNotThrowAnyException();
  }

  private static BBHttpClientWrapper wrapper(V1ChatApi chatApi) {
    return new BBHttpClientWrapper(
        "pw",
        mock(V1MessageApi.class),
        mock(V1ContactApi.class),
        chatApi,
        new ObjectMapper().findAndRegisterModules(),
        Duration.ofSeconds(30));
  }
}
