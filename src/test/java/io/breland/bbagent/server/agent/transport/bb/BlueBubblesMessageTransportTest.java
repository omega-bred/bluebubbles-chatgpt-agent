package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BlueBubblesMessageTransportTest {

  @Test
  void sendTextNormalizesAnyDirectGuidUsingIncomingService() {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper();
    BlueBubblesMessageTransport transport = new BlueBubblesMessageTransport(wrapper);

    boolean sent =
        transport.sendText(
            incomingMessage("any;-;mindstorms6+apple@gmail.com", "iMessage", false),
            OutgoingTextMessage.plain("hello"));

    assertTrue(sent);
    assertEquals("iMessage;-;mindstorms6+apple@gmail.com", wrapper.lastText.getChatGuid());
  }

  @Test
  void sendTextKeepsAnyGroupGuid() {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper();
    BlueBubblesMessageTransport transport = new BlueBubblesMessageTransport(wrapper);

    boolean sent =
        transport.sendText(
            incomingMessage("any;+;chat293505621450166166", "iMessage", true),
            OutgoingTextMessage.plain("hello"));

    assertTrue(sent);
    assertEquals("any;+;chat293505621450166166", wrapper.lastText.getChatGuid());
  }

  @Test
  void sendTextReturnsFalseWhenWrapperCannotConfirmSend() {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(false);
    BlueBubblesMessageTransport transport = new BlueBubblesMessageTransport(wrapper);

    boolean sent =
        transport.sendText(
            incomingMessage("any;-;mindstorms6+apple@gmail.com", "iMessage", false),
            OutgoingTextMessage.plain("hello"));

    assertFalse(sent);
  }

  @Test
  void wrapperDefaultsAnyDirectGuidForRequestOnlySends() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", messageApi, Mockito.mock(V1ContactApi.class));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any())).thenReturn(Mono.empty());

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("any;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();
    assertTrue(wrapper.sendTextDirect(request));

    ArgumentCaptor<ApiV1MessageTextPostRequest> requestCaptor =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);
    verify(messageApi).apiV1MessageTextPost(eq("pw"), requestCaptor.capture());
    assertEquals("iMessage;-;mindstorms6+apple@gmail.com", requestCaptor.getValue().getChatGuid());
  }

  @Test
  void wrapperReturnsFalseWhenDirectSendFails() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", messageApi, Mockito.mock(V1ContactApi.class));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any()))
        .thenReturn(Mono.error(new RuntimeException("boom")));

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertFalse(wrapper.sendTextDirect(request));
  }

  @Test
  void wrapperTreatsDirectSendTimeoutAsSubmitted() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", messageApi, Mockito.mock(V1ContactApi.class));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any()))
        .thenReturn(Mono.error(new RuntimeException(new TimeoutException("slow ack"))));

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertTrue(wrapper.sendTextDirect(request));
  }

  private static IncomingMessage incomingMessage(String chatGuid, String service, boolean isGroup) {
    return new IncomingMessage(
        chatGuid,
        "message-guid",
        null,
        "hello",
        false,
        service,
        "mindstorms6+apple@gmail.com",
        isGroup,
        Instant.now(),
        List.of(),
        false);
  }

  private static final class CapturingBBHttpClientWrapper extends BBHttpClientWrapper {
    private ApiV1MessageTextPostRequest lastText;
    private final boolean sendResult;

    CapturingBBHttpClientWrapper() {
      this(true);
    }

    CapturingBBHttpClientWrapper(boolean sendResult) {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
      this.sendResult = sendResult;
    }

    @Override
    public boolean sendTextDirect(ApiV1MessageTextPostRequest request) {
      this.lastText = request;
      return sendResult;
    }
  }
}
