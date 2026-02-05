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

public class KubernetesReadOnlyAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "kubernetes_read_only_query";

  private static final String DEFAULT_API_SERVER = "https://kubernetes.default.svc";
  private static final Path SERVICE_ACCOUNT_TOKEN_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
  private static final Path SERVICE_ACCOUNT_CA_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

  private final ObjectMapper objectMapper;

  @Schema(description = "A read-only query for the Kubernetes API server.")
  public record KubernetesReadOnlyRequest(
      @Schema(
              description =
                  "Kubernetes API path. Must start with /api or /apis. Example: /api/v1/pods")
          String path,
      @Schema(
              description =
                  "Optional namespace filter for namespaced resources. If provided, the tool rewrites path to namespaced endpoint when possible.")
          String namespace,
      @Schema(description = "Optional label selector, e.g. app=my-app") String labelSelector,
      @Schema(description = "Optional field selector, e.g. metadata.name=my-pod")
          String fieldSelector,
      @Schema(description = "Limit result size (1-500).") Integer limit) {}

  public KubernetesReadOnlyAgentTool(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Read-only Kubernetes API query using the pod's service account. Supports get/list operations only.",
        jsonSchema(KubernetesReadOnlyRequest.class),
        false,
        (context, args) -> {
          try {
            KubernetesReadOnlyRequest request =
                objectMapper.treeToValue(args, KubernetesReadOnlyRequest.class);
            if (request == null || request.path() == null || request.path().isBlank()) {
              return "path is required";
            }

            String resolvedPath = normalizePath(request.path(), request.namespace());
            URI uri =
                buildUri(
                    resolvedPath,
                    request.labelSelector(),
                    request.fieldSelector(),
                    sanitizeLimit(request.limit()));

            HttpClient client = HttpClient.newBuilder().sslContext(loadClusterSslContext()).build();
            HttpRequest httpRequest =
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + readServiceAccountToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

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

  private String normalizePath(String path, String namespace) {
    String normalized = path.trim();
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.startsWith("/api") && !normalized.startsWith("/apis")) {
      throw new IllegalArgumentException("path must start with /api or /apis");
    }
    if (namespace == null || namespace.isBlank()) {
      return normalized;
    }
    if (normalized.contains("/namespaces/")) {
      return normalized;
    }

    if (normalized.startsWith("/api/v1/")) {
      String suffix = normalized.substring("/api/v1/".length());
      return "/api/v1/namespaces/" + namespace + "/" + suffix;
    }

    if (normalized.startsWith("/apis/")) {
      String withoutPrefix = normalized.substring("/apis/".length());
      int firstSlash = withoutPrefix.indexOf('/');
      if (firstSlash <= 0) {
        return normalized;
      }
      int secondSlash = withoutPrefix.indexOf('/', firstSlash + 1);
      if (secondSlash <= 0) {
        return normalized;
      }
      String groupVersion = withoutPrefix.substring(0, secondSlash);
      String suffix = withoutPrefix.substring(secondSlash + 1);
      return "/apis/" + groupVersion + "/namespaces/" + namespace + "/" + suffix;
    }

    return normalized;
  }

  private URI buildUri(String path, String labelSelector, String fieldSelector, Integer limit) {
    List<String> queryParts = new ArrayList<>();
    addQueryParam(queryParts, "labelSelector", labelSelector);
    addQueryParam(queryParts, "fieldSelector", fieldSelector);
    if (limit != null) {
      addQueryParam(queryParts, "limit", String.valueOf(limit));
    }

    String query = queryParts.isEmpty() ? "" : "?" + String.join("&", queryParts);
    return URI.create(DEFAULT_API_SERVER + path + query);
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

  private Integer sanitizeLimit(Integer limit) {
    if (limit == null) {
      return null;
    }
    return Math.clamp(limit, 1, 500);
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

  private Object parseBody(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (Exception ignored) {
      return body;
    }
  }
}
