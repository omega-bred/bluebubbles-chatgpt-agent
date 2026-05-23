package io.breland.bbagent.server.contact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.contact")
@Getter
@Setter
public class ContactProperties {
  private boolean enabled = true;
  private String capBaseUrl = "https://cap.bre.land";
  private String capSiteKey = "";
  private String capSecretKey = "";
  private boolean capRequired = true;
  private int capTimeoutSeconds = 5;
}
