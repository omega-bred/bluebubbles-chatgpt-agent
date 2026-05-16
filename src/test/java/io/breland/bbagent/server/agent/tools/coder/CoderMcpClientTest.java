package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

class CoderMcpClientTest {

  @Test
  void requiresConfiguredStaticOauthClient() {
    assertFalse(client("", credentials()).isConfigured());
    assertTrue(client("coder-client-id", credentials()).isConfigured());
  }

  @Test
  void isLinkedUsesCanonicalAccountId() {
    CoderOauthCredentialRepository credentials = credentials();
    when(credentials.existsById("account-1")).thenReturn(true);

    assertTrue(client("coder-client-id", credentials).isLinked("account-1"));
  }

  private static CoderMcpClient client(
      String clientId, CoderOauthCredentialRepository credentials) {
    return new CoderMcpClient(
        "https://coder.breland.io/api/experimental/mcp/http",
        "http://localhost:8080/api/v1/coder/completeOauth.coder",
        "test-secret",
        clientId,
        "",
        "coder:all",
        5,
        60,
        RestClient.builder(),
        credentials,
        Mockito.mock(CoderOauthPendingAuthorizationRepository.class),
        new ObjectMapper());
  }

  private static CoderOauthCredentialRepository credentials() {
    return Mockito.mock(CoderOauthCredentialRepository.class);
  }
}
