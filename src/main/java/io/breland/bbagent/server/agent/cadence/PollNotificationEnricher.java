package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class PollNotificationEnricher {
  private final BBHttpClientWrapper bbHttpClientWrapper;

  IncomingMessage enrich(IncomingMessage message) {
    if (message == null
        || !message.isBlueBubblesTransport()
        || !BlueBubblesPollSupport.isPollBundle(message.balloonBundleId())) {
      return message;
    }
    String pollMessageGuid = BlueBubblesPollSupport.pollMessageGuid(message);
    if (pollMessageGuid == null || pollMessageGuid.isBlank()) {
      return message.withText(BlueBubblesPollSupport.fallbackPollNotification(message, null));
    }
    try {
      JsonNode poll = bbHttpClientWrapper.readPollJson(pollMessageGuid);
      return message.withText(
          BlueBubblesPollSupport.formatPollNotification(message, pollMessageGuid, poll));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to read poll update for triggerGuid={} pollGuid={}",
          message.messageGuid(),
          pollMessageGuid,
          e);
      return message.withText(
          BlueBubblesPollSupport.fallbackPollNotification(message, pollMessageGuid));
    }
  }
}
