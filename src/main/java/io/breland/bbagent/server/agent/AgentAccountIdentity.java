package io.breland.bbagent.server.agent;

import org.apache.commons.lang3.StringUtils;

public record AgentAccountIdentity(
    String accountBase,
    String coderAccountBase,
    String gcalAccountBase,
    String sender,
    String chatGuid) {

  public static AgentAccountIdentity from(IncomingMessage message) {
    if (message == null) {
      return empty();
    }
    if (message.isLxmfTransport()) {
      return from(
          IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_LXMF, message.sender()),
          IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_LXMF, message.chatGuid()));
    }
    return from(message.sender(), message.chatGuid());
  }

  public static AgentAccountIdentity from(String rawSender, String rawChatGuid) {
    String sender = StringUtils.defaultIfBlank(rawSender, null);
    String chatGuid = StringUtils.defaultIfBlank(rawChatGuid, null);
    String accountBase = StringUtils.firstNonBlank(sender, chatGuid);
    String gcalAccountBase =
        sender != null && chatGuid != null ? chatGuid + "|" + sender : accountBase;
    return new AgentAccountIdentity(
        accountBase == null ? "" : accountBase,
        accountBase == null ? "" : accountBase,
        gcalAccountBase == null ? "" : gcalAccountBase,
        sender,
        chatGuid);
  }

  public static AgentAccountIdentity empty() {
    return new AgentAccountIdentity("", "", "", null, null);
  }

  public boolean hasAccountBase() {
    return accountBase != null && !accountBase.isBlank();
  }
}
