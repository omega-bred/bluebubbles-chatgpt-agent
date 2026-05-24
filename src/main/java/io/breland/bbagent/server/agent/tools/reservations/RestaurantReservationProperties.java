package io.breland.bbagent.server.agent.tools.reservations;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.reservations")
@Getter
@Setter
public class RestaurantReservationProperties {
  private OpenTable opentable = new OpenTable();
  private Resy resy = new Resy();

  @Getter
  @Setter
  public static class OpenTable {
    private boolean enabled = true;
    private String baseUrl = "https://api.opentable.com";
    private String oauthUrl = "https://oauth.opentable.com";
    private String clientId = "";
    private String clientSecret = "";
    private String referralId = "";
    private String directorySearchUrlTemplate = "";
    private int timeoutSeconds = 10;
    private int defaultBackwardMinutes = 60;
    private int defaultForwardMinutes = 120;
  }

  @Getter
  @Setter
  public static class Resy {
    private boolean enabled = true;
    private String baseUrl = "https://resy.com";
    private String loginUrl = "https://resy.com/login";
  }
}
