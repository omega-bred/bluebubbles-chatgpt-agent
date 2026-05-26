package io.breland.bbagent.server.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.workflow")
public class AgentWorkflowProperties {

  private String cadenceDomain = "default";
  private String cadenceTaskList = "bbagent";
  private String cadenceHost = "localhost";
  private int cadencePort = 7933;

  public String getCadenceDomain() {
    return cadenceDomain;
  }

  public void setCadenceDomain(String cadenceDomain) {
    this.cadenceDomain = cadenceDomain;
  }

  public String getCadenceTaskList() {
    return cadenceTaskList;
  }

  public void setCadenceTaskList(String cadenceTaskList) {
    this.cadenceTaskList = cadenceTaskList;
  }

  public String getCadenceHost() {
    return cadenceHost;
  }

  public void setCadenceHost(String cadenceHost) {
    this.cadenceHost = cadenceHost;
  }

  public int getCadencePort() {
    return cadencePort;
  }

  public void setCadencePort(int cadencePort) {
    this.cadencePort = cadencePort;
  }
}
