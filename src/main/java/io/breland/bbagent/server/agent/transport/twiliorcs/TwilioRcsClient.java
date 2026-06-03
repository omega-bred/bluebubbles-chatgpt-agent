package io.breland.bbagent.server.agent.transport.twiliorcs;

import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.security.RequestValidator;
import com.twilio.twiml.MessagingResponse;
import com.twilio.type.PhoneNumber;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TwilioRcsClient {
  private final TwilioRcsProperties properties;

  public TwilioRcsClient(TwilioRcsProperties properties) {
    this.properties = properties;
  }

  public boolean sendText(String recipient, String content) {
    String to = outboundRecipient(recipient);
    String body = StringUtils.trimToNull(content);
    if (StringUtils.isBlank(to)) {
      log.warn("Cannot send Twilio RCS message without recipient");
      return false;
    }
    if (StringUtils.isBlank(body)) {
      log.warn("Cannot send blank Twilio RCS message");
      return false;
    }
    if (!properties.hasCredentials()) {
      log.warn("Cannot send Twilio RCS message without account SID and auth token");
      return false;
    }
    if (!properties.hasMessagingService() && !properties.hasDirectSender()) {
      log.warn("Cannot send Twilio RCS message without messaging service SID or RCS sender");
      return false;
    }

    try {
      MessageCreator creator = messageCreator(to, body);
      if (properties.hasMessagingService()
          && StringUtils.isNotBlank(properties.getFallbackFrom())) {
        creator.setFallbackFrom(new PhoneNumber(properties.getFallbackFrom().trim()));
      }
      if (StringUtils.isNotBlank(properties.getStatusCallbackUrl())) {
        creator.setStatusCallback(URI.create(properties.getStatusCallbackUrl().trim()));
      }
      Message message = creator.create(restClient());
      log.info("Sent Twilio RCS message sid={} to={}", message.getSid(), to);
      return StringUtils.isNotBlank(message.getSid());
    } catch (Exception e) {
      log.warn("Failed to send Twilio RCS message to {}", to, e);
      return false;
    }
  }

  public boolean isValidWebhook(
      String requestUrl, Map<String, String> formParams, String signature) {
    if (!properties.isValidateWebhookSignatures()) {
      return true;
    }
    if (StringUtils.isAnyBlank(properties.getAuthToken(), requestUrl, signature)) {
      log.warn("Rejecting Twilio RCS webhook because signature validation is not configured");
      return false;
    }
    return new RequestValidator(properties.getAuthToken())
        .validate(requestUrl, formParams, signature);
  }

  public String emptyMessagingResponse() {
    return new MessagingResponse.Builder().build().toXml();
  }

  public String configuredWebhookUrl() {
    return StringUtils.trimToNull(properties.getWebhookUrl());
  }

  private MessageCreator messageCreator(String to, String body) {
    PhoneNumber recipient = new PhoneNumber(to);
    if (properties.hasMessagingService()) {
      return Message.creator(recipient, properties.getMessagingServiceSid().trim(), body);
    }
    return Message.creator(
        recipient, new PhoneNumber(TwilioRcsAddress.toRcsSender(properties.getFrom())), body);
  }

  private String outboundRecipient(String recipient) {
    return properties.isRcsOnly()
        ? TwilioRcsAddress.toRcsRecipient(recipient)
        : TwilioRcsAddress.normalizeEndpoint(recipient);
  }

  private TwilioRestClient restClient() {
    return new TwilioRestClient.Builder(properties.getAccountSid(), properties.getAuthToken())
        .build();
  }
}
