package io.breland.bbagent.server.analytics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.analytics.umami")
@Getter
@Setter
public class UmamiAnalyticsProperties {
  private boolean enabled = false;
  private String hostUrl = "https://unami.bre.land";
  private String websiteId = "944a19b7-0e55-4a9b-b67f-48503cbead0d";
  private String hostname = "chatagent.bre.land";
  private String language = "en-US";
  private String userAgent = "BlueBubblesChatGptAgent/0.0.1 (Server)";
}
