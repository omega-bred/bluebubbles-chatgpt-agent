package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataHandle;
import io.breland.bbagent.server.agent.persistence.MessageIngressEventEntity;
import io.breland.bbagent.server.agent.persistence.MessageIngressEventRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MessageIngressPipelineTest {

  private final BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final MessageIngressEventRepository eventRepository =
      Mockito.mock(MessageIngressEventRepository.class);
  private final MessageIngressPipeline pipeline =
      new MessageIngressPipeline(messageAgent, eventRepository, objectMapper, 20);

  @AfterEach
  void afterEach() {
    pipeline.shutdown();
  }

  @Test
  void validateMessageEventPayloadRequiresGuidChatAndSender() {
    assertEquals("missing_data", pipeline.validateMessageEventPayload(null));

    BlueBubblesMessageReceivedRequestData missingGuid = baseData();
    missingGuid.setGuid(" ");
    assertEquals("missing_message_guid", pipeline.validateMessageEventPayload(missingGuid));

    BlueBubblesMessageReceivedRequestData missingChat = baseData();
    missingChat.setChats(List.of());
    assertEquals("missing_chat_guid", pipeline.validateMessageEventPayload(missingChat));

    BlueBubblesMessageReceivedRequestData missingSender = baseData();
    missingSender.setHandle(new BlueBubblesMessageReceivedRequestDataHandle().address(" "));
    assertEquals("missing_sender_address", pipeline.validateMessageEventPayload(missingSender));
  }

  @Test
  void normalizeWebhookMessageCleansAndDefaultsFields() {
    BlueBubblesMessageReceivedRequestData data = baseData();
    data.setGuid(" msg-1 ");
    data.setThreadOriginatorGuid("  thread-1 ");
    data.setText(" hello world   ");
    data.setHandle(
        new BlueBubblesMessageReceivedRequestDataHandle()
            .address(" user@example.com ")
            .service(" ")
            .uncanonicalizedId(" fallback@example.com "));
    data.setChats(
        List.of(
            new BlueBubblesMessageReceivedRequestDataChatsInner().guid(" "),
            new BlueBubblesMessageReceivedRequestDataChatsInner().guid(" iMessage;+;chat-1 ")));
    data.setAttachments(
        List.of(
            new BlueBubblesMessageReceivedRequestDataAttachmentsInner()
                .guid(" att-1 ")
                .mimeType(" image/png ")
                .transferName(" photo.png ")));

    IncomingMessage normalized = pipeline.normalizeWebhookMessage(data);
    assertNotNull(normalized);
    assertEquals("msg-1", normalized.messageGuid());
    assertEquals("thread-1", normalized.threadOriginatorGuid());
    assertEquals("hello world", normalized.text());
    assertEquals(BBMessageAgent.IMESSAGE_SERVICE, normalized.service());
    assertEquals("user@example.com", normalized.sender());
    assertEquals("iMessage;+;chat-1", normalized.chatGuid());
    assertEquals(1, normalized.attachments().size());
    assertEquals("att-1", normalized.attachments().getFirst().guid());
  }

  @Test
  void enqueueSkipsDuplicateIdempotencyKey() {
    IncomingMessage normalized = pipeline.normalizeWebhookMessage(baseData());
    when(eventRepository.countByStatusIn(any())).thenReturn(0L);
    when(eventRepository.findByIdempotencyKey(any()))
        .thenReturn(Optional.of(new MessageIngressEventEntity()));

    boolean accepted = pipeline.enqueue(normalized, baseData());

    assertTrue(accepted);
    verify(eventRepository, never()).save(any(MessageIngressEventEntity.class));
  }

  @Test
  void enqueuePersistsPendingEvent() {
    IncomingMessage normalized = pipeline.normalizeWebhookMessage(baseData());
    when(eventRepository.countByStatusIn(any())).thenReturn(0L);
    when(eventRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

    boolean accepted = pipeline.enqueue(normalized, baseData());

    assertTrue(accepted);
    verify(eventRepository, atLeastOnce()).save(any(MessageIngressEventEntity.class));
  }

  @Test
  void workerDispatchesPendingEventAndMarksCompleted() throws Exception {
    IncomingMessage normalized = pipeline.normalizeWebhookMessage(baseData());
    MessageIngressEventEntity pending = new MessageIngressEventEntity();
    pending.setId("evt-1");
    pending.setStatus("PENDING");
    pending.setNormalizedMessageJson(objectMapper.writeValueAsString(normalized));
    when(eventRepository.findByStatus("PROCESSING")).thenReturn(List.of());
    when(eventRepository.findFirstByStatusOrderByCreatedAtAsc("PENDING"))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.empty());

    pipeline.start();
    verify(messageAgent, timeout(1000)).handleIncomingMessage(any(IncomingMessage.class));
    verify(eventRepository, timeout(1000).atLeast(2)).save(any(MessageIngressEventEntity.class));
  }

  @Test
  void normalizeWebhookMessageReturnsNullForNullData() {
    assertNull(pipeline.normalizeWebhookMessage(null));
  }

  private BlueBubblesMessageReceivedRequestData baseData() {
    BlueBubblesMessageReceivedRequestData data = new BlueBubblesMessageReceivedRequestData();
    data.setGuid("msg-1");
    data.setText("hello");
    data.setDateCreated(1_700_000_000L);
    data.setIsFromMe(false);
    data.setHandle(
        new BlueBubblesMessageReceivedRequestDataHandle()
            .address("alice@example.com")
            .service("iMessage"));
    data.setChats(
        List.of(new BlueBubblesMessageReceivedRequestDataChatsInner().guid("iMessage;+;chat-1")));
    return data;
  }
}
