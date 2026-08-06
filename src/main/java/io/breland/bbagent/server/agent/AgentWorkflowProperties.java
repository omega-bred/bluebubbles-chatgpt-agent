package io.breland.bbagent.server.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.workflow")
@Getter
@Setter
public class AgentWorkflowProperties {

  private String cadenceDomain = "default";
  private String cadenceTaskList = "bbagent";
  private String cadenceHost = "localhost";
  private int cadencePort = 7933;
}
