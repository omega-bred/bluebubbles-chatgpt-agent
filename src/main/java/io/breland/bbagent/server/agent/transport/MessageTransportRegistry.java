package io.breland.bbagent.server.agent.transport;

import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MessageTransportRegistry {
  private final Map<String, MessageTransport> transports;
  private final MessageTransport fallbackTransport;

  @Autowired
  public MessageTransportRegistry(List<MessageTransport> transports) {
    this.transports = new LinkedHashMap<>();
    for (MessageTransport transport : transports) {
      if (transport == null || transport.id() == null || transport.id().isBlank()) {
        continue;
      }
      this.transports.put(transport.id(), transport);
    }
    this.fallbackTransport =
        this.transports.getOrDefault(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            this.transports.values().stream().findFirst().orElse(null));
  }

  public MessageTransport resolve(IncomingMessage message) {
    if (message == null) {
      return fallbackTransport;
    }
    return transports.getOrDefault(message.transportOrDefault(), fallbackTransport);
  }
}
