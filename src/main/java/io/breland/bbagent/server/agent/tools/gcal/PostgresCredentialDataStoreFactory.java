package io.breland.bbagent.server.agent.tools.gcal;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.DataStoreFactory;
import io.breland.bbagent.server.agent.persistence.GcalCredentialEntity;
import io.breland.bbagent.server.agent.persistence.GcalCredentialRepository;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PostgresCredentialDataStoreFactory implements DataStoreFactory {
  private final GcalCredentialRepository repository;

  public PostgresCredentialDataStoreFactory(GcalCredentialRepository repository) {
    this.repository = repository;
  }

  @Override
  public DataStore<StoredCredential> getDataStore(String id) throws IOException {
    return new PostgresCredentialDataStore(repository, id);
  }

  private static final class PostgresCredentialDataStore implements DataStore<StoredCredential> {
    private final GcalCredentialRepository repository;
    private final String storeId;

    private PostgresCredentialDataStore(GcalCredentialRepository repository, String storeId) {
      this.repository = repository;
      this.storeId = storeId;
    }

    @Override
    public DataStoreFactory getDataStoreFactory() {
      return new PostgresCredentialDataStoreFactory(repository);
    }

    @Override
    public String getId() {
      return storeId;
    }

    @Override
    public int size() {
      long count = repository.countByStoreId(storeId);
      return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public boolean isEmpty() {
      return repository.countByStoreId(storeId) == 0;
    }

    @Override
    public boolean containsKey(String key) {
      return repository.existsById(idFor(key));
    }

    @Override
    public boolean containsValue(StoredCredential value) {
      if (value == null) {
        return false;
      }
      return repository.findAllByStoreId(storeId).stream()
          .map(PostgresCredentialDataStore::toStoredCredential)
          .anyMatch(
              stored ->
                  safeEquals(stored.getAccessToken(), value.getAccessToken())
                      && safeEquals(stored.getRefreshToken(), value.getRefreshToken())
                      && safeEquals(
                          stored.getExpirationTimeMilliseconds(),
                          value.getExpirationTimeMilliseconds()));
    }

    @Override
    public Set<String> keySet() {
      List<String> keys = repository.findAllAccountKeysByStoreId(storeId);
      return keys.stream().collect(Collectors.toSet());
    }

    @Override
    public Collection<StoredCredential> values() {
      return repository.findAllByStoreId(storeId).stream()
          .map(PostgresCredentialDataStore::toStoredCredential)
          .collect(Collectors.toList());
    }

    @Override
    public StoredCredential get(String key) {
      return repository
          .findById(idFor(key))
          .map(PostgresCredentialDataStore::toStoredCredential)
          .orElse(null);
    }

    @Override
    public DataStore<StoredCredential> set(String key, StoredCredential value) {
      if (key == null || key.isBlank()) {
        return this;
      }
      if (value == null) {
        delete(key);
        return this;
      }
      String id = idFor(key);
      GcalCredentialEntity entity =
          new GcalCredentialEntity(
              id,
              storeId,
              key,
              value.getAccessToken(),
              value.getRefreshToken(),
              value.getExpirationTimeMilliseconds());
      repository.save(entity);
      return this;
    }

    @Override
    public DataStore<StoredCredential> delete(String key) {
      if (key == null || key.isBlank()) {
        return this;
      }
      repository.deleteById(idFor(key));
      return this;
    }

    @Override
    public DataStore<StoredCredential> clear() {
      repository.deleteAllByStoreId(storeId);
      return this;
    }

    private String idFor(String key) {
      return storeId + ":" + key;
    }

    private static StoredCredential toStoredCredential(GcalCredentialEntity entity) {
      StoredCredential credential = new StoredCredential();
      credential.setAccessToken(entity.getAccessToken());
      credential.setRefreshToken(entity.getRefreshToken());
      credential.setExpirationTimeMilliseconds(entity.getExpirationTimeMs());
      return credential;
    }

    private static boolean safeEquals(Object left, Object right) {
      return left == null ? right == null : left.equals(right);
    }
  }
}
