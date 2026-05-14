package io.breland.bbagent.server.agent.tools.gcal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.StoredCredential;
import io.breland.bbagent.server.agent.persistence.GcalCredentialRepository;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GcalClientTest {

  @Test
  void scopeAccountKeyKeepsDefaultAccountAtBaseAndScopesNamedAccounts() {
    GcalClient client = client(unusedRepository());

    assertEquals("acct", client.scopeAccountKey("acct", null));
    assertEquals("acct", client.scopeAccountKey("acct", " "));
    assertEquals("acct", client.scopeAccountKey("acct", AccountKeyParts.DEFAULT_ACCOUNT_ID));
    assertEquals("acct::work", client.scopeAccountKey("acct", "work"));
    assertEquals("other::work", client.scopeAccountKey("acct", "other::work"));
    assertEquals("work", client.scopeAccountKey(" ", "work"));
  }

  @Test
  void listAccountsForFiltersBlankPendingAndDuplicateAccountIds() {
    assertEquals(
        List.of("primary", "work"),
        client(
                repositoryReturning(
                    Arrays.asList("primary", "", null, "pending::abc", "work", "work")))
            .listAccountsFor("acct"));
  }

  @Test
  void listAccountsForSkipsRepositoryForBlankAccountBase() {
    assertEquals(List.of(), client(unusedRepository()).listAccountsFor(" "));
  }

  private static GcalClient client(GcalCredentialRepository repository) {
    return new GcalClient(
        "", "", "http://localhost/oauth", "state-secret", "test", repository, new ObjectMapper());
  }

  private static GcalCredentialRepository repositoryReturning(List<String> accountIds) {
    return repositoryProxy(
        (method, args) -> {
          if ("findAccountIdsByStoreIdAndAccountBase".equals(method)) {
            assertEquals(StoredCredential.DEFAULT_DATA_STORE_ID, args[0]);
            assertEquals("acct", args[1]);
            return accountIds;
          }
          throw new UnsupportedOperationException(method);
        });
  }

  private static GcalCredentialRepository unusedRepository() {
    return repositoryProxy(
        (method, args) -> {
          throw new UnsupportedOperationException(method);
        });
  }

  private static GcalCredentialRepository repositoryProxy(RepositoryCall call) {
    return (GcalCredentialRepository)
        Proxy.newProxyInstance(
            GcalCredentialRepository.class.getClassLoader(),
            new Class<?>[] {GcalCredentialRepository.class},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "toString" -> "GcalCredentialRepository test proxy";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              }
              return call.invoke(method.getName(), args == null ? new Object[0] : args);
            });
  }

  @FunctionalInterface
  private interface RepositoryCall {
    Object invoke(String method, Object[] args);
  }
}
