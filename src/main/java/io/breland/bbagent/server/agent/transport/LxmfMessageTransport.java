package io.breland.bbagent.server.agent.transport;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.lxmf.LxmfBridgeClient;
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
    return lxmfBridgeClient.sendText(destinationHash, outgoingMessage.text());
  }
}
