package io.breland.bbagent.server.agent.transport;

public record OutgoingTextMessage(
    String text,
    String selectedMessageGuid,
    String effectId,
    Integer partIndex,
    String deliveryId) {
  public OutgoingTextMessage(
      String text, String selectedMessageGuid, String effectId, Integer partIndex) {
    this(text, selectedMessageGuid, effectId, partIndex, null);
  }

  public OutgoingTextMessage withDeliveryId(String id) {
    return new OutgoingTextMessage(text, selectedMessageGuid, effectId, partIndex, id);
  }

  public static OutgoingTextMessage plain(String text) {
    return new OutgoingTextMessage(text, null, null, null);
  }
}
