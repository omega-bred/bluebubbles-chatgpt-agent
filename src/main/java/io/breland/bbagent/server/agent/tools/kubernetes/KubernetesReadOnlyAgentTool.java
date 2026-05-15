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

public class KubernetesReadOnlyAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "kubernetes_read_only_query";

  private final ObjectMapper objectMapper;
  private final KubernetesApiClient kubernetesApi;

  @Schema(description = "A read-only query for the Kubernetes API server.")
  public record KubernetesReadOnlyRequest(
      @Schema(
              description =
                  "Kubernetes API path. Must start with /api or /apis. Example: /api/v1/pods")
          String path,
      @Schema(
              description =
                  "Optional namespace filter for namespaced resources. If provided, the tool"
                      + " rewrites path to namespaced endpoint when possible.")
          String namespace,
      @Schema(description = "Optional label selector, e.g. app=my-app") String labelSelector,
      @Schema(description = "Optional field selector, e.g. metadata.name=my-pod")
          String fieldSelector,
      @Schema(description = "Limit result size (1-500).") Integer limit) {}

  public KubernetesReadOnlyAgentTool(ObjectMapper objectMapper) {
    this(objectMapper, new KubernetesApiClient());
  }

  KubernetesReadOnlyAgentTool(ObjectMapper objectMapper, KubernetesApiClient kubernetesApi) {
    this.objectMapper = objectMapper;
    this.kubernetesApi = kubernetesApi;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Read-only Kubernetes API query using the pod's service account. Supports get/list"
            + " operations only.",
        jsonSchema(KubernetesReadOnlyRequest.class),
        false,
        (context, args) -> {
          try {
            KubernetesReadOnlyRequest request =
                objectMapper.treeToValue(args, KubernetesReadOnlyRequest.class);
            if (request == null || request.path() == null || request.path().isBlank()) {
              return "path is required";
            }

            String resolvedPath = kubernetesApi.namespacedPath(request.path(), request.namespace());
            URI uri =
                kubernetesApi.uri(
                    resolvedPath,
                    kubernetesApi.queryParam("labelSelector", request.labelSelector()),
                    kubernetesApi.queryParam("fieldSelector", request.fieldSelector()),
                    kubernetesApi.queryParam(
                        "limit", kubernetesApi.clamp(request.limit(), 1, 500)));

            HttpResponse<String> response = kubernetesApi.get(uri, "application/json");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", response.statusCode());
            payload.put("path", resolvedPath);
            payload.put("request_uri", uri.toString());
            payload.put("response", parseBody(response.body()));
            return objectMapper.writeValueAsString(payload);
          } catch (Exception e) {
            return "kubernetes query failed: " + e.getMessage();
          }
        });
  }

  private Object parseBody(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (Exception ignored) {
      return body;
    }
  }
}
