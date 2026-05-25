package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiResponsePoll;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiResponseSendPoll;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessagePollPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.PollData;
import io.breland.bbagent.generated.bluebubblesclient.model.PollSendResult;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BlueBubblesMessageTransportTest {

  @Test
  void sendTextPreservesAnyDirectGuidForWrapperConfirmation() {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper();
    BlueBubblesMessageTransport transport = new BlueBubblesMessageTransport(wrapper);

    boolean sent =
        transport.sendText(
            incomingMessage("any;-;mindstorms6+apple@gmail.com", "iMessage", false),
            OutgoingTextMessage.plain("hello"));

    assertTrue(sent);
    assertEquals("any;-;mindstorms6+apple@gmail.com", wrapper.lastText.getChatGuid());
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
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.confirmationSnapshots(
        List.of(sentMessage("iMessage;-;mindstorms6+apple@gmail.com", "hello")));
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
    assertEquals("any;-;mindstorms6+apple@gmail.com", wrapper.lastMessageLookupChatGuid);
  }

  @Test
  void wrapperReturnsFalseWhenDirectSendFails() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.confirmationSnapshots(List.of(), List.of(), List.of());
    when(messageApi.apiV1MessageTextPost(eq("pw"), any()))
        .thenReturn(Mono.error(new RuntimeException("boom")));

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertFalse(wrapper.sendTextDirect(request));
    verify(messageApi, times(3)).apiV1MessageTextPost(eq("pw"), any());
    assertEquals(3, wrapper.messageLookupCalls);
  }

  @Test
  void wrapperConfirmsDirectSendTimeoutFromChatHistory() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.confirmationSnapshots(
        List.of(sentMessage("iMessage;-;mindstorms6+apple@gmail.com", "hello")));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any()))
        .thenReturn(Mono.error(new RuntimeException(new TimeoutException("slow ack"))));

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertTrue(wrapper.sendTextDirect(request));
    verify(messageApi).apiV1MessageTextPost(eq("pw"), any());
    assertEquals(1, wrapper.messageLookupCalls);
  }

  @Test
  void wrapperRetriesDirectSendUntilChatHistoryConfirms() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.confirmationSnapshots(
        List.of(), List.of(sentMessage("iMessage;-;mindstorms6+apple@gmail.com", "hello")));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any())).thenReturn(Mono.empty());

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertTrue(wrapper.sendTextDirect(request));
    verify(messageApi, times(2)).apiV1MessageTextPost(eq("pw"), any());
    assertEquals(2, wrapper.messageLookupCalls);
  }

  @Test
  void wrapperRetriesPingWarmupBeforeSending() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.pingResults(false, true);
    wrapper.confirmationSnapshots(
        List.of(sentMessage("iMessage;-;mindstorms6+apple@gmail.com", "hello")));
    when(messageApi.apiV1MessageTextPost(eq("pw"), any())).thenReturn(Mono.empty());

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertTrue(wrapper.sendTextDirect(request));
    assertEquals(2, wrapper.pingCalls);
    verify(messageApi).apiV1MessageTextPost(eq("pw"), any());
  }

  @Test
  void wrapperReturnsFalseWhenPingWarmupFailsTwice() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    SendConfirmingBBHttpClientWrapper wrapper = new SendConfirmingBBHttpClientWrapper(messageApi);
    wrapper.pingResults(false, false);

    ApiV1MessageTextPostRequest request =
        ApiV1MessageTextPostRequest.builder()
            .chatGuid("iMessage;-;mindstorms6+apple@gmail.com")
            .tempGuid("tmp")
            .message("hello")
            .build();

    assertFalse(wrapper.sendTextDirect(request));
    assertEquals(2, wrapper.pingCalls);
    verify(messageApi, never()).apiV1MessageTextPost(eq("pw"), any());
  }

  @Test
  void wrapperBuildsTypedMultipartPayload() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    PayloadBBHttpClientWrapper wrapper = new PayloadBBHttpClientWrapper(messageApi);
    when(messageApi.apiV1MessageMultipartPost(eq("pw"), any())).thenReturn(Mono.empty());

    boolean sent =
        wrapper.sendMultipartMessage(
            "any;-;mindstorms6+apple@gmail.com",
            "caption",
            List.of(new BBHttpClientWrapper.AttachmentData("photo.jpg", new byte[] {1, 2, 3})));

    assertTrue(sent);
    ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
    verify(messageApi).apiV1MessageMultipartPost(eq("pw"), bodyCaptor.capture());

    JsonNode body = wrapper.getObjectMapper().valueToTree(bodyCaptor.getValue());
    assertEquals("iMessage;-;mindstorms6+apple@gmail.com", body.path("chatGuid").asText());
    JsonNode parts = body.path("parts");
    assertEquals(2, parts.size());
    assertEquals(0, parts.get(0).path("partIndex").asInt());
    assertEquals("caption", parts.get(0).path("text").asText());
    assertFalse(parts.get(0).has("attachment"));
    assertEquals(1, parts.get(1).path("partIndex").asInt());
    assertEquals("uploaded-photo.jpg", parts.get(1).path("attachment").asText());
    assertEquals("photo.jpg", parts.get(1).path("name").asText());
    assertFalse(parts.get(1).has("text"));
  }

  @Test
  void wrapperUsesGeneratedPollRequestAndReturnsGeneratedDataAsJson() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", messageApi, Mockito.mock(V1ContactApi.class));
    when(messageApi.apiV1MessagePollPost(any(), eq("pw")))
        .thenReturn(
            Mono.just(
                ApiResponseSendPoll.builder()
                    .status(200)
                    .message("Poll sent!")
                    .data(
                        PollSendResult.builder()
                            .poll(
                                PollData.builder().messageGuid("poll-guid").title("Lunch?").build())
                            .build())
                    .build()));

    JsonNode data =
        wrapper.sendPollJson(
            "any;-;mindstorms6+apple@gmail.com",
            " Lunch? ",
            List.of(
                new BBHttpClientWrapper.PollSendOption(" Pizza ", " opt-1 "),
                new BBHttpClientWrapper.PollSendOption("Sushi", null)));

    ArgumentCaptor<ApiV1MessagePollPostRequest> requestCaptor =
        ArgumentCaptor.forClass(ApiV1MessagePollPostRequest.class);
    verify(messageApi).apiV1MessagePollPost(requestCaptor.capture(), eq("pw"));
    ApiV1MessagePollPostRequest request = requestCaptor.getValue();
    assertEquals("iMessage;-;mindstorms6+apple@gmail.com", request.getChatGuid());
    assertEquals("Lunch?", request.getTitle());
    assertEquals("Pizza", request.getOptions().get(0).getText());
    assertEquals("opt-1", request.getOptions().get(0).getOptionIdentifier());
    assertEquals("Sushi", request.getOptions().get(1).getText());
    assertEquals("poll-guid", data.path("poll").path("messageGuid").asText());
  }

  @Test
  void wrapperUsesGeneratedPollReadResponse() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", messageApi, Mockito.mock(V1ContactApi.class));
    when(messageApi.apiV1MessageMessageGuidPollGet("poll-guid", "pw"))
        .thenReturn(
            Mono.just(
                ApiResponsePoll.builder()
                    .status(200)
                    .message("Poll read!")
                    .data(PollData.builder().messageGuid("poll-guid").title("Lunch?").build())
                    .build()));

    JsonNode data = wrapper.readPollJson("poll-guid");

    verify(messageApi).apiV1MessageMessageGuidPollGet("poll-guid", "pw");
    assertEquals("Lunch?", data.path("title").asText());
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

  private static ApiV1ChatChatGuidMessageGet200ResponseDataInner sentMessage(
      String chatGuid, String text) {
    return new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
        .guid(UUID.randomUUID().toString())
        .text(text)
        .isFromMe(true)
        .dateCreated(Instant.now().getEpochSecond());
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

  private static final class SendConfirmingBBHttpClientWrapper extends BBHttpClientWrapper {
    private final Queue<Boolean> pingResults = new ArrayDeque<>();
    private final Queue<List<ApiV1ChatChatGuidMessageGet200ResponseDataInner>>
        confirmationSnapshots = new ArrayDeque<>();
    private int pingCalls;
    private int messageLookupCalls;
    private String lastMessageLookupChatGuid;

    SendConfirmingBBHttpClientWrapper(V1MessageApi messageApi) {
      super("pw", messageApi, Mockito.mock(V1ContactApi.class));
    }

    void pingResults(Boolean... results) {
      pingResults.addAll(List.of(results));
    }

    @SafeVarargs
    final void confirmationSnapshots(
        List<ApiV1ChatChatGuidMessageGet200ResponseDataInner>... snapshots) {
      confirmationSnapshots.addAll(List.of(snapshots));
    }

    @Override
    protected void pingBlueBubbles(Duration timeout) {
      pingCalls++;
      boolean success = pingResults.isEmpty() || pingResults.remove();
      if (!success) {
        throw new RuntimeException("ping failed");
      }
    }

    @Override
    public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(
        String chatGuid) {
      messageLookupCalls++;
      lastMessageLookupChatGuid = chatGuid;
      return confirmationSnapshots.isEmpty() ? List.of() : confirmationSnapshots.remove();
    }

    @Override
    protected Duration directSendConfirmationDelay() {
      return Duration.ZERO;
    }
  }

  private static final class PayloadBBHttpClientWrapper extends BBHttpClientWrapper {
    PayloadBBHttpClientWrapper(V1MessageApi messageApi) {
      super("pw", messageApi, Mockito.mock(V1ContactApi.class));
    }

    @Override
    public String uploadAttachment(String filename, byte[] bytes) {
      return "uploaded-" + filename;
    }
  }
}
