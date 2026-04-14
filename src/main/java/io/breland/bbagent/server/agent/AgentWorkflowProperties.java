package io.breland.bbagent.server.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.workflow")
public class AgentWorkflowProperties {

  public enum Mode {
    INLINE,
    CADENCE
  }

  private Mode mode = Mode.INLINE;
  private String cadenceDomain = "default";
  private String cadenceTaskList = "bbagent";
  private String cadenceHost = "localhost";
  private int cadencePort = 7933;
  private String callbackBaseUrl = "http://localhost:8080";
  private int callbackDefaultTtlHours = 24;
  private long callbackTimestampToleranceSeconds = 300;

  public Mode getMode() {
    return mode;
  }

  public void setMode(Mode mode) {
    if (mode == null) {
      this.mode = Mode.INLINE;
      return;
    }
    this.mode = mode;
  }

  public boolean useCadenceWorkflow() {
    return mode == Mode.CADENCE;
  }

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

  public String getCallbackBaseUrl() {
    return callbackBaseUrl;
  }

  public void setCallbackBaseUrl(String callbackBaseUrl) {
    this.callbackBaseUrl = callbackBaseUrl;
  }

  public int getCallbackDefaultTtlHours() {
    return callbackDefaultTtlHours;
  }

  public void setCallbackDefaultTtlHours(int callbackDefaultTtlHours) {
    this.callbackDefaultTtlHours = callbackDefaultTtlHours;
  }

  public long getCallbackTimestampToleranceSeconds() {
    return callbackTimestampToleranceSeconds;
  }

  public void setCallbackTimestampToleranceSeconds(long callbackTimestampToleranceSeconds) {
    this.callbackTimestampToleranceSeconds = callbackTimestampToleranceSeconds;
  }
}
