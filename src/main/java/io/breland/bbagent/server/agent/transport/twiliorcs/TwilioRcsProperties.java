package io.breland.bbagent.server.agent.transport.twiliorcs;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "twilio.rcs")
@Getter
@Setter
public class TwilioRcsProperties {
  private String accountSid = "";
  private String authToken = "";
  private String messagingServiceSid = "";
  private String from = "";
  private String fallbackFrom = "";
  private boolean rcsOnly = false;
  private boolean validateWebhookSignatures = true;
  private String webhookUrl = "";
  private String statusCallbackUrl = "";

  boolean hasCredentials() {
    return StringUtils.isNoneBlank(accountSid, authToken);
  }

  boolean hasMessagingService() {
    return StringUtils.isNotBlank(messagingServiceSid);
  }

  boolean hasDirectSender() {
    return StringUtils.isNotBlank(from);
  }
}
