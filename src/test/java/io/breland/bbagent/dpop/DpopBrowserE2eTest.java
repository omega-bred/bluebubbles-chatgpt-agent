package io.breland.bbagent.dpop;

import static org.assertj.core.api.Assertions.assertThat;

import io.breland.bbagent.server.config.SecurityConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Opt-in real browser + Keycloak + Spring test. See docs/dpop.md for the isolated fixture. */
@EnabledIfEnvironmentVariable(named = "BLUECHAT_DPOP_E2E_ISSUER", matches = ".+")
@SpringBootTest(
    classes = DpopBrowserE2eTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "management.influx.metrics.export.enabled=false",
      "bluechat.security.cors.allowed-origins=http://localhost:15174"
    })
class DpopBrowserE2eTest {
  @Value("${local.server.port}")
  int port;

  @Test
  void browserLoginRefreshReloadTabsAndProtectedRequests() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            "npm", "exec", "--", "playwright", "test", "--config", "playwright.dpop.config.ts");
    builder.directory(Path.of("frontend").toFile());
    builder.environment().put("VITE_API_URL", "http://localhost:" + port);
    builder.inheritIO();
    Process process = builder.start();
    try {
      assertThat(process.waitFor(180, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally {
      process.descendants().forEach(ProcessHandle::destroy);
      process.destroyForcibly();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({SecurityConfig.class, Resource.class})
  static class Application {
    @Bean
    JwtDecoder jwtDecoder() {
      String issuer = System.getenv("BLUECHAT_DPOP_E2E_ISSUER");
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      return decoder;
    }

    @Bean
    JdbcTemplate jdbcTemplate() {
      var source =
          new DriverManagerDataSource("jdbc:h2:mem:dpop-browser;DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V33__oauth_dpop_replay_protection.sql"))
          .execute(source);
      return new JdbcTemplate(source);
    }
  }

  @RestController
  static class Resource {
    @GetMapping("/api/v1/websiteAccount/get.websiteAccount")
    Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) {
      return Map.of("account", Map.of("subject", jwt.getSubject()), "links", java.util.List.of());
    }
  }
}
