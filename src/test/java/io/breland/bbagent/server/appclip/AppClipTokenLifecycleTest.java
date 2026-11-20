package io.breland.bbagent.server.appclip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.sessions.SessionTokens;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class AppClipTokenLifecycleTest {
  @Autowired private AppClipSessionService sessions;
  @Autowired private WebsiteAccountService website;
  @Autowired private AgentAccountRepository accounts;
  @Autowired private WebsiteAccountLinkTokenRepository links;

  @Test
  void onlyOneConcurrentClaimAndOneConcurrentRotationSucceed() throws Exception {
    String link = createLink();
    var redemptions = twice(() -> redeemInClip(link));
    assertThat(redemptions).filteredOn(Optional::isPresent).hasSize(1);
    AppClipSessionResponse session =
        redemptions.stream().flatMap(Optional::stream).findFirst().orElseThrow();

    var rotations = twice(() -> sessions.authenticate(session.getSessionToken()));
    assertThat(rotations).filteredOn(Optional::isPresent).hasSize(1);
    var replacement = rotations.stream().flatMap(Optional::stream).findFirst().orElseThrow();
    assertThat(replacement.nextToken()).isNotEqualTo(session.getSessionToken());
    assertThat(Duration.between(replacement.expiresAt(), session.getExpiresAt().toInstant()).abs())
        .isLessThanOrEqualTo(Duration.ofNanos(1_000));
    assertThat(sessions.authenticate(session.getSessionToken())).isEmpty();
    assertThat(sessions.authenticate(replacement.nextToken()).orElseThrow().expiresAt())
        .isEqualTo(replacement.expiresAt());
  }

  @Test
  void webAndClipShareOneRedemptionAndWebsiteRetryHasNoSecondEffect() {
    String webLink = createLink();
    Jwt owner = websiteLogin();
    assertThat(website.redeemLink(owner, webLink).getStatus()).isEqualTo("linked");
    assertThat(website.redeemLink(owner, webLink).getStatus()).isEqualTo("already_linked");
    assertThat(redeemInClip(webLink)).isEmpty();
    assertThatThrownBy(() -> website.redeemLink(websiteLogin(), webLink))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    String clipLink = createLink();
    assertThat(redeemInClip(clipLink)).isPresent();
    assertThatThrownBy(() -> website.redeemLink(owner, clipLink))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  private String createLink() {
    Instant now = Instant.now();
    String accountId = UUID.randomUUID().toString();
    accounts.saveAndFlush(new AgentAccountEntity(accountId, now, now));
    String token = UUID.randomUUID().toString();
    links.saveAndFlush(
        new WebsiteAccountLinkTokenEntity(
            SessionTokens.sha256Hash(token),
            accountId,
            WebsiteAccountService.LINK_PURPOSE_ACCOUNT_LINK,
            null,
            null,
            null,
            false,
            null,
            now.plusSeconds(60),
            now,
            now));
    return token;
  }

  private Optional<AppClipSessionResponse> redeemInClip(String link) {
    try {
      return Optional.of(sessions.createSession(link));
    } catch (ResponseStatusException error) {
      assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE);
      return Optional.empty();
    }
  }

  private static Jwt websiteLogin() {
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .subject(UUID.randomUUID().toString())
        .build();
  }

  private static <T> List<T> twice(Callable<T> action) throws Exception {
    CyclicBarrier started = new CyclicBarrier(2);
    Callable<T> concurrent =
        () -> {
          started.await(10, TimeUnit.SECONDS);
          return action.call();
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(concurrent);
      var second = executor.submit(concurrent);
      return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
  }
}
