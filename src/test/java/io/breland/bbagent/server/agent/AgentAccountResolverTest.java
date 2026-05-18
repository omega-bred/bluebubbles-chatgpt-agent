package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
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

  @Test
  void acceptsTermsForResolvedMessageAccount() {
    accountRepository.deleteAll();
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;chat-terms",
            "msg-terms",
            null,
            "YES",
            false,
            "iMessage",
            "+1 (803) 386-1737",
            false,
            Instant.now(),
            java.util.List.of(),
            false);

    String accountId =
        accountResolver.resolveOrCreate(message).orElseThrow().account().getAccountId();

    accountResolver.acceptTerms(message);

    assertNotNull(accountRepository.findById(accountId).orElseThrow().getTermsAcceptedAt());
  }

  @Test
  void mergingAccountsPreservesTermsAcceptance() {
    accountRepository.deleteAll();
    String acceptedEmailAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "mindstorms6+apple@gmail.com")
            .orElseThrow()
            .account()
            .getAccountId();
    var acceptedEmailAccount = accountRepository.findById(acceptedEmailAccountId).orElseThrow();
    acceptedEmailAccount.setTermsAcceptedAt(Instant.parse("2026-05-18T00:00:00Z"));
    acceptedEmailAccount.setUpdatedAt(Instant.now());
    accountRepository.save(acceptedEmailAccount);
    String phoneAccountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+1 (803) 386-1737")
            .orElseThrow()
            .account()
            .getAccountId();

    accountResolver.linkWebsiteAccount(
        phoneAccountId, jwt("keycloak-sub", "mindstorms6+apple@gmail.com"));

    assertNotNull(accountRepository.findById(phoneAccountId).orElseThrow().getTermsAcceptedAt());
    assertTrue(accountRepository.findById(acceptedEmailAccountId).isEmpty());
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
