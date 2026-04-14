package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthClientRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

class CoderMcpClientTest {

  @Test
  void resolveAccountBasePrefersSenderOverChatScopedIdentity() {
    IncomingMessage message =
        new IncomingMessage(
            "any;-;+18035551212",
            "msg-1",
            null,
            "use coder",
            false,
            "iMessage",
            "+18035551212",
            false,
            Instant.now(),
            List.of(),
            false);

    assertEquals("+18035551212", CoderMcpClient.resolveAccountBase(message));
  }

  @Test
  void resolveAccountBaseFallsBackToChatWhenSenderIsMissing() {
    IncomingMessage message =
        new IncomingMessage(
            "any;-;+18035551212",
            "msg-1",
            null,
            "use coder",
            false,
            "iMessage",
            null,
            false,
            Instant.now(),
            List.of(),
            false);

    assertEquals("any;-;+18035551212", CoderMcpClient.resolveAccountBase(message));
  }

  @Test
  void isLinkedFallsBackToLegacyChatScopedCredential() {
    CoderOauthCredentialRepository credentials = Mockito.mock(CoderOauthCredentialRepository.class);
    when(credentials.existsById("+18035551212")).thenReturn(false);
    when(credentials.findFirstByAccountBaseEndingWithOrderByUpdatedAtDesc("|+18035551212"))
        .thenReturn(Optional.of(credential("any;-;+18035551212|+18035551212")));

    CoderMcpClient client = client(credentials);

    assertTrue(client.isLinked("+18035551212"));
  }

  private static CoderMcpClient client(CoderOauthCredentialRepository credentials) {
    return new CoderMcpClient(
        "https://coder.breland.io/api/experimental/mcp/http",
        "http://localhost:8080/api/v1/coder/completeOauth.coder",
        "test-secret",
        "",
        "",
        "coder:all",
        "BlueBubbles ChatGPT Agent Test",
        5,
        60,
        RestClient.builder(),
        Mockito.mock(CoderOauthClientRepository.class),
        credentials,
        Mockito.mock(CoderOauthPendingAuthorizationRepository.class),
        new ObjectMapper());
  }

  private static CoderOauthCredentialEntity credential(String accountBase) {
    Instant now = Instant.now();
    return new CoderOauthCredentialEntity(
        accountBase,
        "access-token",
        "refresh-token",
        "Bearer",
        "coder:all",
        now.plusSeconds(3600),
        now,
        now);
  }
}
