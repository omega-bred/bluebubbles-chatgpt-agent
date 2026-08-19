package io.breland.bbagent.server.agent.transport.bb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ChatApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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
    BBHttpClientWrapper wrapper = wrapper(chatApi, Duration.ZERO);

    assertThatCode(() -> wrapper.startTyping("group")).doesNotThrowAnyException();
    assertThatCode(() -> wrapper.stopTyping("group")).doesNotThrowAnyException();
  }

  @Test
  void stopTypingRetriesTransientPrivateApiFailure() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    when(chatApi.apiV1ChatChatGuidTypingDelete("group", "pw"))
        .thenReturn(Mono.error(new IllegalStateException("helper disconnected")), Mono.empty());
    BBHttpClientWrapper wrapper = wrapper(chatApi, Duration.ZERO);

    wrapper.stopTyping("group");

    verify(chatApi, times(2)).apiV1ChatChatGuidTypingDelete("group", "pw");
  }

  @Test
  void stopTypingDoesNotRetryAfterANewerTurnStarts() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    Sinks.One<Void> firstStop = Sinks.one();
    when(chatApi.apiV1ChatChatGuidTypingPost("group", "pw")).thenReturn(Mono.empty());
    when(chatApi.apiV1ChatChatGuidTypingDelete("group", "pw"))
        .thenReturn(firstStop.asMono(), Mono.empty());
    BBHttpClientWrapper wrapper = wrapper(chatApi, Duration.ZERO);

    wrapper.startTyping("group");
    wrapper.stopTyping("group");
    wrapper.startTyping("group");
    firstStop.tryEmitError(new IllegalStateException("helper disconnected"));

    verify(chatApi, times(1)).apiV1ChatChatGuidTypingDelete("group", "pw");
  }

  private static BBHttpClientWrapper wrapper(V1ChatApi chatApi) {
    return wrapper(chatApi, Duration.ofSeconds(1));
  }

  private static BBHttpClientWrapper wrapper(V1ChatApi chatApi, Duration retryDelay) {
    return new BBHttpClientWrapper(
        "pw",
        mock(V1MessageApi.class),
        mock(V1ContactApi.class),
        chatApi,
        new ObjectMapper().findAndRegisterModules(),
        Duration.ofSeconds(30),
        retryDelay);
  }
}
