package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AgentAccountResolverTest {
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private AgentAccountRepository accountRepository;

  @Test
  void linkingWebsiteAccountMergesExistingWebsiteAndPhoneAccounts() {
    accountRepository.deleteAll();
    Jwt jwt = jwt("keycloak-sub", "mindstorms6+apple@gmail.com");
    String websiteAccountId = accountResolver.upsertWebsiteAccount(jwt).getAccountId();
    String phoneAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+1 (803) 386-1737")
            .orElseThrow()
            .account()
            .getAccountId();

    accountResolver.linkWebsiteAccount(phoneAccountId, jwt);

    assertEquals(1, accountRepository.count());
    var merged = accountRepository.findById(phoneAccountId).orElseThrow();
    assertEquals("keycloak-sub", merged.getWebsiteSubject());
    assertEquals("mindstorms6+apple@gmail.com", merged.getWebsiteEmail());
    assertTrue(accountRepository.findById(websiteAccountId).isEmpty());
    assertEquals(
        2,
        accountResolver.identitiesForAccount(phoneAccountId).stream()
            .map(identity -> identity.getIdentityType() + ":" + identity.getNormalizedIdentifier())
            .filter(
                value ->
                    value.equals("imessage_phone:+18033861737")
                        || value.equals("imessage_email:mindstorms6+apple@gmail.com"))
            .count());
  }

  private Jwt jwt(String subject, String email) {
    return new Jwt(
        "token",
        null,
        null,
        Map.of("alg", "none"),
        Map.of("sub", subject, "email", email, "preferred_username", email));
  }
}
