package io.breland.bbagent.server.agent.cadence.models;

public record ImageSendResult(boolean sentImage, boolean captionSent, boolean rateLimited) {
  public ImageSendResult(boolean sentImage, boolean captionSent) {
    this(sentImage, captionSent, false);
  }
}
