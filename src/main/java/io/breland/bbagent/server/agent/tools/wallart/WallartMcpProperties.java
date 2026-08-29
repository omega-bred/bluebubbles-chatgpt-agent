package io.breland.bbagent.server.agent.tools.wallart;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.wallart-mcp")
@Getter
@Setter
public class WallartMcpProperties {
  private String baseUrl = "http://localhost:8081";
  private String endpoint = "/mcp";
  private String toolName = "showNewArt";
  private String allowedParticipant = "+18033861737";
  private Duration requestTimeout = Duration.ofSeconds(30);
}
