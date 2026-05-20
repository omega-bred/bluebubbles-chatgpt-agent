package io.breland.bbagent.server.subscriptions;

public class WebhookVerificationException extends RuntimeException {
  public WebhookVerificationException(String message) {
    super(message);
  }

  public WebhookVerificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
