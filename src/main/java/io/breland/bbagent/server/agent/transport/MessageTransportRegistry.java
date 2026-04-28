package io.breland.bbagent.server.agent.transport;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesMessageTransport;
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
    MessageTransport blueBubblesTransport = null;
    for (MessageTransport transport : transports) {
      if (transport == null || transport.id() == null || transport.id().isBlank()) {
        continue;
      }
      this.transports.put(transport.id(), transport);
      if (IncomingMessage.TRANSPORT_BLUEBUBBLES.equals(transport.id())) {
        blueBubblesTransport = transport;
      }
    }
    this.fallbackTransport =
        blueBubblesTransport != null
            ? blueBubblesTransport
            : this.transports.values().stream().findFirst().orElse(null);
  }

  private MessageTransportRegistry(MessageTransport transport) {
    this.transports = new LinkedHashMap<>();
    this.transports.put(transport.id(), transport);
    this.fallbackTransport = transport;
  }

  public static MessageTransportRegistry blueBubblesOnly(BBHttpClientWrapper bbHttpClientWrapper) {
    return new MessageTransportRegistry(new BlueBubblesMessageTransport(bbHttpClientWrapper));
  }

  public MessageTransport resolve(IncomingMessage message) {
    if (message == null) {
      return fallbackTransport;
    }
    String id = message.transportOrDefault();
    MessageTransport transport = transports.get(id);
    return transport != null ? transport : fallbackTransport;
  }
}
