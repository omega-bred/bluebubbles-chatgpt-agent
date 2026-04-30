package io.breland.bbagent.server.agent;

import java.util.stream.Stream;
import org.springframework.util.StringUtils;

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
    String sender = clean(rawSender);
    String chatGuid = clean(rawChatGuid);
    String accountBase = firstWithText(sender, chatGuid);
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
    return StringUtils.hasText(accountBase);
  }

  private static String clean(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

  private static String firstWithText(String... values) {
    return Stream.of(values).filter(StringUtils::hasText).findFirst().orElse(null);
  }
}
