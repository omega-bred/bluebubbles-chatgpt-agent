package io.breland.bbagent.server.agent.tools.kubernetes;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

public class KubernetesPodLogsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "kubernetes_get_pod_logs";

  private final ObjectMapper objectMapper;
  private final KubernetesApiClient kubernetesApi;

  @Schema(description = "Fetch logs from a Kubernetes pod.")
  public record KubernetesPodLogsRequest(
      @Schema(description = "Namespace that contains the pod.") String namespace,
      @Schema(description = "Pod name.") String pod,
      @Schema(description = "Optional container name when pod has multiple containers.")
          String container,
      @Schema(description = "Whether to stream logs continuously. Defaults to false.")
          Boolean follow,
      @Schema(description = "Return logs from previous container instance if true.")
          Boolean previous,
      @Schema(description = "Only return the last N lines of logs (1-5000).") Integer tailLines) {}

  public KubernetesPodLogsAgentTool(ObjectMapper objectMapper) {
    this(objectMapper, new KubernetesApiClient());
  }

  KubernetesPodLogsAgentTool(ObjectMapper objectMapper, KubernetesApiClient kubernetesApi) {
    this.objectMapper = objectMapper;
    this.kubernetesApi = kubernetesApi;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get logs for a Kubernetes pod using the pod's service account.",
        jsonSchema(KubernetesPodLogsRequest.class),
        false,
        (context, args) -> {
          try {
            KubernetesPodLogsRequest request =
                objectMapper.treeToValue(args, KubernetesPodLogsRequest.class);
            if (request == null || request.namespace() == null || request.namespace().isBlank()) {
              return "namespace is required";
            }
            if (request.pod() == null || request.pod().isBlank()) {
              return "pod is required";
            }

            URI uri =
                kubernetesApi.uri(
                    kubernetesApi.podLogsPath(request.namespace(), request.pod()),
                    kubernetesApi.queryParam("container", request.container()),
                    kubernetesApi.queryParam("follow", request.follow()),
                    kubernetesApi.queryParam("previous", request.previous()),
                    kubernetesApi.queryParam(
                        "tailLines", kubernetesApi.clamp(request.tailLines(), 1, 5000)));
            HttpResponse<String> response = kubernetesApi.get(uri, "text/plain");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", response.statusCode());
            payload.put("request_uri", uri.toString());
            payload.put("response", response.body());
            return objectMapper.writeValueAsString(payload);
          } catch (Exception e) {
            return "kubernetes pod logs failed: " + e.getMessage();
          }
        });
  }
}
