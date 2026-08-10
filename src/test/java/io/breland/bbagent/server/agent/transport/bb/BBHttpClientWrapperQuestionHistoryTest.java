package io.breland.bbagent.server.agent.transport.bb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ChatApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200Response;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ContactGet200Response;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageQueryPost200Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class BBHttpClientWrapperQuestionHistoryTest {
  private static final Instant FROM = Instant.parse("2026-08-09T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T14:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void exactQuestionHistoryBlocksForTheLesserOfRemainingTimeAndApiTimeout() {
    V1MessageApi messageApi = mock(V1MessageApi.class);
    Mono<ApiV1MessageQueryPost200Response> response = mock(Mono.class);
    when(messageApi.apiV1MessageQueryPost(any(), any())).thenReturn(response);
    when(response.block(Duration.ofSeconds(5)))
        .thenReturn(
            ApiV1MessageQueryPost200Response.builder()
                .status(200)
                .message("success")
                .data(List.of())
                .build());
    BBHttpClientWrapper wrapper = wrapper(messageApi, mock(V1ChatApi.class));

    assertThat(
            wrapper.searchConversationHistoryForQuestion(
                "group", "Wordle", FROM, TO, 10, 0, Duration.ofSeconds(5)))
        .isEmpty();

    verify(response).block(Duration.ofSeconds(5));
  }

  @Test
  @SuppressWarnings("unchecked")
  void chronologicalQuestionHistoryCapsBlockingAtTheNormalApiTimeout() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    Mono<ApiV1ChatChatGuidMessageGet200Response> response = mock(Mono.class);
    when(chatApi.apiV1ChatChatGuidMessageGet(
            any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(response);
    when(response.block(Duration.ofSeconds(30)))
        .thenReturn(
            ApiV1ChatChatGuidMessageGet200Response.builder()
                .status(200)
                .message("ok")
                .data(List.of())
                .build());
    BBHttpClientWrapper wrapper = wrapper(mock(V1MessageApi.class), chatApi);

    assertThat(
            wrapper.getMessagesInChatForQuestion(
                "group", FROM, TO, 0, 10, "ASC", Duration.ofMinutes(1)))
        .isEmpty();

    verify(response).block(Duration.ofSeconds(30));
  }

  @Test
  @SuppressWarnings("unchecked")
  void newestQuestionWindowForwardsDescendingFiveHundredMessageBounds() {
    V1ChatApi chatApi = mock(V1ChatApi.class);
    Mono<ApiV1ChatChatGuidMessageGet200Response> response = mock(Mono.class);
    when(chatApi.apiV1ChatChatGuidMessageGet(
            any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(response);
    when(response.block(Duration.ofSeconds(5)))
        .thenReturn(
            ApiV1ChatChatGuidMessageGet200Response.builder()
                .status(200)
                .message("ok")
                .data(List.of())
                .build());
    BBHttpClientWrapper wrapper = wrapper(mock(V1MessageApi.class), chatApi);

    assertThat(
            wrapper.getMessagesInChatForQuestion(
                "group", FROM, TO, 0, 500, "DESC", Duration.ofSeconds(5)))
        .isEmpty();

    verify(chatApi)
        .apiV1ChatChatGuidMessageGet(
            eq("group"),
            eq("pw"),
            eq("handle,chats"),
            eq(Long.toString(FROM.getEpochSecond())),
            eq(Long.toString(TO.getEpochSecond())),
            eq(0),
            eq(500),
            eq("DESC"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void questionContactDirectoryUsesTheRemainingRequestTime() {
    V1ContactApi contactApi = mock(V1ContactApi.class);
    Mono<ApiV1ContactGet200Response> response = mock(Mono.class);
    when(contactApi.apiV1ContactGet("pw")).thenReturn(response);
    when(response.block(Duration.ofSeconds(5)))
        .thenReturn(
            ApiV1ContactGet200Response.builder()
                .status(200)
                .message("Successfully fetched contacts")
                .data(List.of())
                .build());
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper(
            "pw",
            mock(V1MessageApi.class),
            contactApi,
            mock(V1ChatApi.class),
            new ObjectMapper().findAndRegisterModules(),
            Duration.ofSeconds(30));

    assertThat(wrapper.getContactIdentitiesForQuestion(Duration.ofSeconds(5))).isEmpty();

    verify(response).block(Duration.ofSeconds(5));
  }

  private static BBHttpClientWrapper wrapper(V1MessageApi messageApi, V1ChatApi chatApi) {
    return new BBHttpClientWrapper(
        "pw",
        messageApi,
        mock(V1ContactApi.class),
        chatApi,
        new ObjectMapper().findAndRegisterModules(),
        Duration.ofSeconds(30));
  }
}
