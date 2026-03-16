package io.breland.bbagent.server.agent.tools.kubernetes;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class KubernetesPodLogsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "kubernetes_get_pod_logs";

  private static final String DEFAULT_API_SERVER = "https://kubernetes.default.svc";
  private static final Path SERVICE_ACCOUNT_TOKEN_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
  private static final Path SERVICE_ACCOUNT_CA_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

  private final ObjectMapper objectMapper;

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
    this.objectMapper = objectMapper;
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

            URI uri = buildUri(request);
            HttpClient client = HttpClient.newBuilder().sslContext(loadClusterSslContext()).build();
            HttpRequest httpRequest =
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + readServiceAccountToken())
                    .header("Accept", "text/plain")
                    .GET()
                    .build();

            HttpResponse<String> response =
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

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

  private URI buildUri(KubernetesPodLogsRequest request) {
    String path =
        "/api/v1/namespaces/"
            + encodePathSegment(request.namespace().trim())
            + "/pods/"
            + encodePathSegment(request.pod().trim())
            + "/log";

    List<String> queryParts = new ArrayList<>();
    addQueryParam(queryParts, "container", request.container());
    if (request.follow() != null) {
      addQueryParam(queryParts, "follow", String.valueOf(request.follow()));
    }
    if (request.previous() != null) {
      addQueryParam(queryParts, "previous", String.valueOf(request.previous()));
    }
    if (request.tailLines() != null) {
      addQueryParam(
          queryParts, "tailLines", String.valueOf(Math.clamp(request.tailLines(), 1, 5000)));
    }

    String query = queryParts.isEmpty() ? "" : "?" + String.join("&", queryParts);
    return URI.create(DEFAULT_API_SERVER + path + query);
  }

  private String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private void addQueryParam(List<String> queryParts, String name, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    queryParts.add(
        URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  private String readServiceAccountToken() throws IOException {
    return Files.readString(SERVICE_ACCOUNT_TOKEN_PATH, StandardCharsets.UTF_8).trim();
  }

  private SSLContext loadClusterSslContext() throws Exception {
    CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
    java.security.cert.Certificate cert;
    try (var input = Files.newInputStream(SERVICE_ACCOUNT_CA_PATH)) {
      cert = certFactory.generateCertificate(input);
    }

    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setCertificateEntry("k8s-ca", cert);

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
    return sslContext;
  }
}
