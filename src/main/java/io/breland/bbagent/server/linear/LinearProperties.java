package io.breland.bbagent.server.linear;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.linear")
@Getter
@Setter
public class LinearProperties {
  private boolean enabled = true;
  private String apiUrl = "https://api.linear.app/graphql";
  private String apiKey = "";
  private String teamId = "";
  private String teamKey = "";
  private String teamName = "bluechat";
  private boolean createMissingLabels = false;
  private int timeoutSeconds = 10;
  private Labels labels = new Labels();

  @Getter
  @Setter
  public static class Labels {
    private String feedback = "Improvement";
    private String contact = "Contact/Help";
  }
}
