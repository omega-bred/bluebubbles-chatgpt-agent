package io.breland.bbagent.server.agent.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class KubernetesApiClientTest {
  private final KubernetesApiClient client = new KubernetesApiClient();

  @Test
  void namespacedPathRewritesCoreApiLists() {
    assertEquals(
        "/api/v1/namespaces/default/pods", client.namespacedPath("/api/v1/pods", "default"));
  }

  @Test
  void namespacedPathRewritesGroupedApiLists() {
    assertEquals(
        "/apis/apps/v1/namespaces/prod/deployments",
        client.namespacedPath("apis/apps/v1/deployments", "prod"));
  }

  @Test
  void namespacedPathLeavesExistingNamespaceAlone() {
    assertEquals(
        "/api/v1/namespaces/kube-system/pods",
        client.namespacedPath("/api/v1/namespaces/kube-system/pods", "default"));
  }

  @Test
  void namespacedPathRejectsNonApiPaths() {
    assertThrows(IllegalArgumentException.class, () -> client.namespacedPath("/metrics", null));
    assertThrows(IllegalArgumentException.class, () -> client.namespacedPath("/apiary", null));
  }

  @Test
  void uriEncodesQueryParamsAndDropsBlanks() {
    URI uri =
        client.uri(
            "/api/v1/pods",
            client.queryParam("labelSelector", "app=bb agent"),
            client.queryParam("fieldSelector", " "),
            client.queryParam("limit", 500));

    assertEquals(
        "https://kubernetes.default.svc/api/v1/pods?labelSelector=app%3Dbb+agent&limit=500",
        uri.toString());
  }

  @Test
  void podLogsPathEncodesPathSegmentsAndTrimsValues() {
    assertEquals(
        "/api/v1/namespaces/dev%20space/pods/app%2Fpod/log",
        client.podLogsPath(" dev space ", " app/pod "));
  }

  @Test
  void clampKeepsNullAndBoundsValues() {
    assertNull(client.clamp(null, 1, 500));
    assertEquals(1, client.clamp(-10, 1, 500));
    assertEquals(500, client.clamp(5000, 1, 500));
    assertEquals(25, client.clamp(25, 1, 500));
  }
}
