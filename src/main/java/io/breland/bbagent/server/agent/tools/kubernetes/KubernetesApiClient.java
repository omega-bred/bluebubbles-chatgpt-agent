package io.breland.bbagent.server.agent.tools.kubernetes;

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
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class KubernetesApiClient {
  private static final String DEFAULT_API_SERVER = "https://kubernetes.default.svc";
  private static final Path SERVICE_ACCOUNT_TOKEN_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
  private static final Path SERVICE_ACCOUNT_CA_PATH =
      Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

  HttpResponse<String> get(URI uri, String accept) throws Exception {
    HttpClient client = HttpClient.newBuilder().sslContext(loadClusterSslContext()).build();
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + readServiceAccountToken())
            .header("Accept", accept)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  URI uri(String path, QueryParam... queryParams) {
    String queryString =
        Arrays.stream(queryParams)
            .filter(Objects::nonNull)
            .filter(param -> param.value() != null && !param.value().isBlank())
            .map(param -> encodeQuery(param.name()) + "=" + encodeQuery(param.value()))
            .collect(Collectors.joining("&"));
    String query = queryString.isBlank() ? "" : "?" + queryString;
    return URI.create(DEFAULT_API_SERVER + path + query);
  }

  String namespacedPath(String path, String namespace) {
    String normalized = normalizeApiPath(path);
    if (namespace == null || namespace.isBlank() || normalized.contains("/namespaces/")) {
      return normalized;
    }

    String encodedNamespace = encodePathSegment(namespace.trim());
    if (normalized.startsWith("/api/v1/")) {
      return "/api/v1/namespaces/"
          + encodedNamespace
          + "/"
          + normalized.substring("/api/v1/".length());
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
      return "/apis/" + groupVersion + "/namespaces/" + encodedNamespace + "/" + suffix;
    }

    return normalized;
  }

  String podLogsPath(String namespace, String pod) {
    return "/api/v1/namespaces/"
        + encodePathSegment(namespace.trim())
        + "/pods/"
        + encodePathSegment(pod.trim())
        + "/log";
  }

  Integer clamp(Integer value, int min, int max) {
    return value == null ? null : Math.clamp(value, min, max);
  }

  QueryParam queryParam(String name, Object value) {
    return new QueryParam(name, value == null ? null : String.valueOf(value));
  }

  private String normalizeApiPath(String path) {
    String normalized = path.trim();
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    boolean apiPath =
        normalized.equals("/api")
            || normalized.startsWith("/api/")
            || normalized.equals("/apis")
            || normalized.startsWith("/apis/");
    if (!apiPath) {
      throw new IllegalArgumentException("path must start with /api or /apis");
    }
    return normalized;
  }

  private static String encodePathSegment(String value) {
    return encodeQuery(value).replace("+", "%20");
  }

  private static String encodeQuery(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

  record QueryParam(String name, String value) {}
}
