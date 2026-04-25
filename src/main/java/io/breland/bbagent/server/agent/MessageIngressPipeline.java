package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.persistence.MessageIngressEventEntity;
import io.breland.bbagent.server.agent.persistence.MessageIngressEventRepository;
import io.breland.bbagent.server.controllers.BluebubblesWebhookController;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MessageIngressPipeline {
  private static final int MIN_QUEUE_CAPACITY = 1;
  private static final long IDLE_SLEEP_MILLIS = 100L;
  private static final long SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 250L;

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final String STATUS_COMPLETED = "COMPLETED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_REJECTED = "REJECTED";

  private static final List<String> BACKLOG_STATUSES = List.of(STATUS_PENDING, STATUS_PROCESSING);

  private final BBMessageAgent messageAgent;
  private final MessageIngressEventRepository eventRepository;
  private final ObjectMapper objectMapper;
  private final int queueCapacity;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final String workerThreadName;
  private volatile Thread workerThread;

  public MessageIngressPipeline(
      BBMessageAgent messageAgent,
      MessageIngressEventRepository eventRepository,
      ObjectMapper objectMapper,
      @Value("${agent.ingress.queue-capacity:200}") int configuredQueueCapacity) {
    this.messageAgent = messageAgent;
    this.eventRepository = eventRepository;
    this.objectMapper = objectMapper;
    this.queueCapacity = Math.max(configuredQueueCapacity, MIN_QUEUE_CAPACITY);
    this.workerThreadName = "message-ingress-queue";
  }

  @PostConstruct
  void start() {
    if (running.compareAndSet(false, true)) {
      Thread thread = new Thread(this::runWorkerLoop, workerThreadName);
      thread.setDaemon(true);
      workerThread = thread;
      thread.start();
      log.info("Started message ingress worker with queueCapacity={}", queueCapacity);
    }
  }

  @PreDestroy
  void shutdown() {
    running.set(false);
    Thread thread = workerThread;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(SHUTDOWN_DRAIN_TIMEOUT_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public String validateMessageEventPayload(BlueBubblesMessageReceivedRequestData data) {
    if (data == null) {
      return "missing_data";
    }
    if (isBlank(data.getGuid())) {
      return "missing_message_guid";
    }
    if (isBlank(firstChatGuid(data.getChats()))) {
      return "missing_chat_guid";
    }
    if (isBlank(resolveSender(data))) {
      return "missing_sender_address";
    }
    return null;
  }

  public IncomingMessage normalizeWebhookMessage(BlueBubblesMessageReceivedRequestData data) {
    if (data == null) {
      return null;
    }
    return new IncomingMessage(
        firstChatGuid(data.getChats()),
        clean(data.getGuid()),
        clean(data.getThreadOriginatorGuid()),
        cleanText(data.getText()),
        data.getIsFromMe(),
        clean(resolveService(data)),
        clean(resolveSender(data)),
        BluebubblesWebhookController.resolveIsGroup(data),
        parseTimestamp(data.getDateCreated()),
        parseAttachments(data.getAttachments()),
        false);
  }

  public boolean enqueue(
      IncomingMessage message, BlueBubblesMessageReceivedRequestData rawPayload) {
    if (message == null) {
      return false;
    }

    long backlogCount = eventRepository.countByStatusIn(BACKLOG_STATUSES);
    if (backlogCount >= queueCapacity) {
      log.warn(
          "Dropping message because ingress queue is full chat={} guid={} backlog={} capacity={}",
          message.chatGuid(),
          message.messageGuid(),
          backlogCount,
          queueCapacity);
      return false;
    }

    String idempotencyKey = resolveIdempotencyKey(message, rawPayload);
    if (idempotencyKey != null
        && eventRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
      return true;
    }

    Instant now = Instant.now();
    MessageIngressEventEntity event = new MessageIngressEventEntity();
    event.setId(UUID.randomUUID().toString());
    event.setIdempotencyKey(idempotencyKey);
    event.setStatus(STATUS_PENDING);
    event.setPayloadJson(writeJson(rawPayload));
    event.setNormalizedMessageJson(writeJson(message));
    event.setAttemptCount(0);
    event.setCreatedAt(now);
    event.setUpdatedAt(now);
    eventRepository.save(event);
    return true;
  }

  public void captureMalformedPayload(
      BlueBubblesMessageReceivedRequestData rawPayload, String validationError) {
    Instant now = Instant.now();
    MessageIngressEventEntity event = new MessageIngressEventEntity();
    event.setId(UUID.randomUUID().toString());
    event.setIdempotencyKey(clean(rawPayload == null ? null : rawPayload.getGuid()));
    event.setStatus(STATUS_REJECTED);
    event.setPayloadJson(writeJson(rawPayload));
    event.setNormalizedMessageJson(null);
    event.setErrorCode(clean(validationError));
    event.setErrorMessage("Ingress payload validation failed");
    event.setAttemptCount(0);
    event.setCreatedAt(now);
    event.setUpdatedAt(now);
    eventRepository.save(event);
  }

  private void runWorkerLoop() {
    while (running.get()) {
      try {
        Optional<MessageIngressEventEntity> optionalPending =
            eventRepository.findFirstByStatusOrderByCreatedAtAsc(STATUS_PENDING);
        if (optionalPending.isEmpty()) {
          sleepQuietly(IDLE_SLEEP_MILLIS);
          continue;
        }

        MessageIngressEventEntity event = optionalPending.get();
        event.setStatus(STATUS_PROCESSING);
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setUpdatedAt(Instant.now());
        eventRepository.save(event);

        IncomingMessage message =
            objectMapper.readValue(event.getNormalizedMessageJson(), IncomingMessage.class);
        messageAgent.handleIncomingMessage(message);

        event.setStatus(STATUS_COMPLETED);
        event.setErrorCode(null);
        event.setErrorMessage(null);
        event.setProcessedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        eventRepository.save(event);
      } catch (InterruptedException interrupted) {
        if (!running.get()) {
          Thread.currentThread().interrupt();
          return;
        }
      } catch (Exception e) {
        log.warn("Message ingress worker failed while dispatching message", e);
      }
    }
  }

  private void sleepQuietly(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  private String resolveIdempotencyKey(
      IncomingMessage message, BlueBubblesMessageReceivedRequestData rawPayload) {
    if (message != null && !isBlank(message.messageGuid())) {
      return clean(message.messageGuid());
    }
    if (rawPayload != null && !isBlank(rawPayload.getGuid())) {
      return clean(rawPayload.getGuid());
    }
    return null;
  }

  private String resolveSender(BlueBubblesMessageReceivedRequestData data) {
    if (data == null || data.getHandle() == null) {
      return null;
    }
    String sender = clean(data.getHandle().getAddress());
    if (sender != null) {
      return sender;
    }
    return clean(data.getHandle().getUncanonicalizedId());
  }

  private String resolveService(BlueBubblesMessageReceivedRequestData data) {
    if (data == null || data.getHandle() == null) {
      return BBMessageAgent.IMESSAGE_SERVICE;
    }
    String service = clean(data.getHandle().getService());
    return service == null ? BBMessageAgent.IMESSAGE_SERVICE : service;
  }

  private String firstChatGuid(
      @NotNull @Valid List<@Valid BlueBubblesMessageReceivedRequestDataChatsInner> chats) {
    if (chats == null || chats.isEmpty()) {
      return null;
    }
    for (BlueBubblesMessageReceivedRequestDataChatsInner chat : chats) {
      if (chat == null) {
        continue;
      }
      String chatGuid = clean(chat.getGuid());
      if (chatGuid != null) {
        return chatGuid;
      }
    }
    return null;
  }

  private List<IncomingAttachment> parseAttachments(
      @NotNull @Valid List<@Valid BlueBubblesMessageReceivedRequestDataAttachmentsInner> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<IncomingAttachment> attachments = new ArrayList<>();
    for (BlueBubblesMessageReceivedRequestDataAttachmentsInner node : nodes) {
      if (node == null) {
        continue;
      }
      attachments.add(
          new IncomingAttachment(
              clean(node.getGuid()),
              clean(node.getMimeType()),
              clean(node.getTransferName()),
              null,
              null,
              null));
    }
    return attachments;
  }

  private Instant parseTimestamp(Long value) {
    if (value == null) {
      return Instant.now();
    }
    if (value > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(value);
    }
    return Instant.ofEpochSecond(value);
  }

  private String writeJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String cleanText(String value) {
    String cleaned = clean(value);
    return cleaned == null ? null : cleaned.stripTrailing();
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
