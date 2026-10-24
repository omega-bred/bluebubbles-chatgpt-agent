package io.breland.bbagent.server.agent.tools.gayodex;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.gayodex")
@Getter
@Setter
public class GayodexProperties {
  private boolean enabled;
  private String baseUrl = "";
  private String token = "";

  public boolean isConfigured() {
    if (!enabled || token == null || !token.matches("[A-Za-z0-9_-]{43,}")) return false;
    try {
      URI uri = URI.create(baseUrl);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null
          && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return false;
    }
  }
}
