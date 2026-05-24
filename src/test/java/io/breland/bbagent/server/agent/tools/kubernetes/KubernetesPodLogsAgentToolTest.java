package io.breland.bbagent.server.agent.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class KubernetesPodLogsAgentToolTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fetchesPodLogsWithNegotiationFriendlyAcceptHeader() throws Exception {
    KubernetesApiClient kubernetesApi = spy(new KubernetesApiClient());
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("line 1\nline 2\n");
    doReturn(response).when(kubernetesApi).get(any(URI.class), anyString());

    KubernetesPodLogsAgentTool toolProvider =
        new KubernetesPodLogsAgentTool(objectMapper, kubernetesApi);
    ObjectNode args = objectMapper.createObjectNode();
    args.put("namespace", "default");
    args.put("pod", "app");
    args.put("tailLines", 100);

    String result = toolProvider.getTool().handler().apply(null, args);

    URI expectedUri =
        URI.create(
            "https://kubernetes.default.svc/api/v1/namespaces/default/pods/app/log?tailLines=100");
    verify(kubernetesApi).get(eq(expectedUri), eq(KubernetesApiClient.POD_LOGS_ACCEPT_HEADER));

    var payload = objectMapper.readTree(result);
    assertEquals(200, payload.get("status").asInt());
    assertEquals(expectedUri.toString(), payload.get("request_uri").asText());
    assertEquals("line 1\nline 2\n", payload.get("response").asText());
  }
}
