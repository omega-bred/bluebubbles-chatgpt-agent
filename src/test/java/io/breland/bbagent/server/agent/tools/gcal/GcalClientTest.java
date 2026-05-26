package io.breland.bbagent.server.agent.tools.gcal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.StoredCredential;
import io.breland.bbagent.server.agent.persistence.GcalCredentialEntity;
import io.breland.bbagent.server.agent.persistence.GcalCredentialRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class GcalClientTest {
  private static final String CLIENT_SECRET_JSON =
      """
      {
        "web": {
          "client_id": "client-id.apps.googleusercontent.com",
          "client_secret": "client-secret",
          "auth_uri": "https://accounts.google.com/o/oauth2/auth",
          "token_uri": "https://oauth2.googleapis.com/token"
        }
      }
      """;

  @Test
  void authUrlRequestsConsentForRefreshToken() {
    GcalClient client = client(mock(GcalCredentialRepository.class));

    String authUrl = client.getAuthUrl("account-1", "chat-1", "message-1");

    assertThat(authUrl)
        .contains("access_type=offline")
        .contains("prompt=consent")
        .contains("include_granted_scopes=true");
  }

  @Test
  void migrateCredentialKeyPreservesExistingRefreshTokenWhenPendingCredentialOmitsIt() {
    GcalCredentialRepository repository = mock(GcalCredentialRepository.class);
    GcalClient client = client(repository);
    String fromKey = "account-1::pending::abc";
    String toKey = "account-1::person@example.com";
    String fromId = StoredCredential.DEFAULT_DATA_STORE_ID + ":" + fromKey;
    String toId = StoredCredential.DEFAULT_DATA_STORE_ID + ":" + toKey;
    GcalCredentialEntity pending = credential(fromId, fromKey, "new-access", null, 1_000L);
    GcalCredentialEntity existing = credential(toId, toKey, "old-access", "old-refresh", 500L);
    when(repository.findById(fromId)).thenReturn(Optional.of(pending));
    when(repository.findById(toId)).thenReturn(Optional.of(existing));

    ReflectionTestUtils.invokeMethod(client, "migrateCredentialKey", fromKey, toKey);

    ArgumentCaptor<GcalCredentialEntity> saved =
        ArgumentCaptor.forClass(GcalCredentialEntity.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(toId);
    assertThat(saved.getValue().getAccessToken()).isEqualTo("new-access");
    assertThat(saved.getValue().getRefreshToken()).isEqualTo("old-refresh");
    assertThat(saved.getValue().getExpirationTimeMs()).isEqualTo(1_000L);
    verify(repository).deleteById(fromId);
  }

  private static GcalClient client(GcalCredentialRepository repository) {
    return new GcalClient(
        "",
        CLIENT_SECRET_JSON,
        "http://localhost:8080/api/v1/gcal/completeOauth.gcal",
        "state-secret",
        "BlueChat",
        repository,
        new ObjectMapper());
  }

  private static GcalCredentialEntity credential(
      String id,
      String accountKey,
      String accessToken,
      String refreshToken,
      Long expirationTimeMs) {
    AccountKeyParts parts = AccountKeyParts.parse(accountKey);
    return new GcalCredentialEntity(
        id,
        StoredCredential.DEFAULT_DATA_STORE_ID,
        accountKey,
        parts.accountId(),
        parts.googleAccountId(),
        accessToken,
        refreshToken,
        expirationTimeMs);
  }
}
