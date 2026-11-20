package io.breland.bbagent.server.agent.transport.lxmf;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(LxmfBridgeClient.class)
public class LxmfMessageTransport implements MessageTransport {
  private final LxmfBridgeClient lxmfBridgeClient;

  public LxmfMessageTransport(LxmfBridgeClient lxmfBridgeClient) {
    this.lxmfBridgeClient = lxmfBridgeClient;
  }

  @Override
  public String id() {
    return IncomingMessage.TRANSPORT_LXMF;
  }

  @Override
  public String displayName() {
    return "LXMF via Reticulum";
  }

  @Override
  public boolean sendText(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    String destinationHash = message.sender();
    if (destinationHash == null || destinationHash.isBlank()) {
      destinationHash = IncomingMessage.stripTransportPrefix(message.chatGuid());
    }
    return outgoingMessage.deliveryId() == null
        ? lxmfBridgeClient.sendText(destinationHash, outgoingMessage.text())
        : lxmfBridgeClient.sendText(
            destinationHash, outgoingMessage.text(), outgoingMessage.deliveryId());
  }

  @Override
  public boolean confirmText(
      IncomingMessage message, OutgoingTextMessage outgoingMessage, Instant startedAt) {
    return lxmfBridgeClient.confirmText(outgoingMessage.deliveryId());
  }
}
