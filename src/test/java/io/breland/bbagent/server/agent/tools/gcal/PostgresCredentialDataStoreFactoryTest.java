package io.breland.bbagent.server.agent.tools.gcal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.google.api.client.auth.oauth2.StoredCredential;
import io.breland.bbagent.server.agent.persistence.GcalCredentialEntity;
import io.breland.bbagent.server.agent.persistence.GcalCredentialRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostgresCredentialDataStoreFactoryTest {
  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "access, refresh, 100, access, refresh, 100, true",
        "access, refresh, 100, other, refresh, 100, false",
        "access, refresh, 100, access, other, 100, false",
        "access, refresh, 100, access, refresh, 101, false",
        "NULL, refresh, 100, NULL, refresh, 100, true",
        "access, NULL, 100, access, NULL, 100, true",
        "access, refresh, NULL, access, refresh, NULL, true",
        "NULL, NULL, NULL, NULL, NULL, NULL, true",
        "NULL, refresh, 100, access, refresh, 100, false",
        "access, refresh, 100, NULL, refresh, 100, false",
        "access, NULL, 100, access, refresh, 100, false",
        "access, refresh, 100, access, NULL, 100, false",
        "access, refresh, NULL, access, refresh, 100, false",
        "access, refresh, 100, access, refresh, NULL, false"
      })
  void containsValueComparesAllCredentialFields(
      String storedAccess,
      String storedRefresh,
      Long storedExpiration,
      String access,
      String refresh,
      Long expiration,
      boolean expected)
      throws Exception {
    var repository = mock(GcalCredentialRepository.class);
    var entity = new GcalCredentialEntity();
    entity.setAccessToken(storedAccess);
    entity.setRefreshToken(storedRefresh);
    entity.setExpirationTimeMs(storedExpiration);
    when(repository.findAllByStoreId("test-store")).thenReturn(List.of(entity));
    var store = new PostgresCredentialDataStoreFactory(repository).getDataStore("test-store");
    var value =
        new StoredCredential()
            .setAccessToken(access)
            .setRefreshToken(refresh)
            .setExpirationTimeMilliseconds(expiration);

    assertThat(store.containsValue(value)).isEqualTo(expected);
    verify(repository).findAllByStoreId("test-store");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void containsValueRejectsNullWithoutQueryingRepository() throws Exception {
    var repository = mock(GcalCredentialRepository.class);
    var store = new PostgresCredentialDataStoreFactory(repository).getDataStore("test-store");

    assertThat(store.containsValue(null)).isFalse();
    verifyNoInteractions(repository);
  }

  @Test
  void containsValueReturnsFalseForEmptyStore() throws Exception {
    var repository = mock(GcalCredentialRepository.class);
    when(repository.findAllByStoreId("test-store")).thenReturn(List.of());
    var store = new PostgresCredentialDataStoreFactory(repository).getDataStore("test-store");

    assertThat(store.containsValue(new StoredCredential())).isFalse();
    verify(repository).findAllByStoreId("test-store");
  }
}
