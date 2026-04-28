package io.breland.bbagent.server.agent.transport;

public record OutgoingTextMessage(
    String text, String selectedMessageGuid, String effectId, Integer partIndex) {
  public static OutgoingTextMessage plain(String text) {
    return new OutgoingTextMessage(text, null, null, null);
  }
}
