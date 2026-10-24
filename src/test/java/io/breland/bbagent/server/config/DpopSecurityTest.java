package io.breland.bbagent.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    properties = {
      "bluechat.security.dpop.public-origins=https://bluechat.example,https://legacy.example",
      "bluechat.security.cors.allowed-origins=https://dashboard.example"
    })
@Import({
  SecurityConfig.class,
  DpopSecurityTest.Config.class,
  DpopSecurityTest.Resource.class,
  io.breland.bbagent.server.controllers.RootController.class
})
class DpopSecurityTest {
  private static final String PATH = "/api/v1/websiteAccount/test";
  private static final String TARGET = "https://bluechat.example" + PATH;
  private static final String ISSUER = "https://issuer.example/realms/bbagent";
  private static final RSAKey ISSUER_KEY = issuerKey();
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtDecoder decoder;
  @Autowired Converter<Jwt, AbstractAuthenticationToken> converter;
  @Autowired DpopReplayStore replays;
  ECKey key;
  String token;

  @BeforeEach
  void setup() throws Exception {
    jdbc.update("DELETE FROM oauth_dpop_used_proofs");
    key = new ECKeyGenerator(Curve.P_256).generate();
    token = token(key, Instant.now().plusSeconds(300));
  }

  @Test
  void validatesRealSignaturesAndExposesJwtPrincipal() throws Exception {
    mvc.perform(request(token, proof(key, token, "GET", TARGET, Instant.now())))
        .andExpect(status().isOk())
        .andExpect(content().string("test-subject"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_dpop_used_proofs", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void servesTheBrowserOAuthCallbackWithoutApiAuthentication() throws Exception {
    mvc.perform(
            get("/auth/callback").queryParam("code", "test-code").queryParam("state", "test-state"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"));
  }

  @Test
  void preservesAdminRoleAuthorizationForProvedTokens() throws Exception {
    String path = "/api/v1/admin/dpopTest";
    String uri = "https://bluechat.example" + path;
    mvc.perform(
            get(path)
                .header("Host", "bluechat.example")
                .header("Authorization", "DPoP " + token)
                .header("DPoP", proof(key, token, "GET", uri, Instant.now())))
        .andExpect(status().isForbidden());
    var claims =
        new JWTClaimsSet.Builder(SignedJWT.parse(token).getJWTClaimsSet())
            .claim("realm_access", Map.of("roles", List.of("bbagent-admin-role")))
            .build();
    SignedJWT admin = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    admin.sign(new RSASSASigner(ISSUER_KEY));
    mvc.perform(
            get(path)
                .header("Host", "bluechat.example")
                .header("Authorization", "DPoP " + admin.serialize())
                .header("DPoP", proof(key, admin.serialize(), "GET", uri, Instant.now())))
        .andExpect(status().isOk());
  }

  @Test
  void supportsUnboundLegacyBearerSessionsButNeverBoundBearer() throws Exception {
    mvc.perform(
            get(PATH)
                .header("Authorization", "Bearer " + token(null, Instant.now().plusSeconds(300))))
        .andExpect(status().isOk());
    mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get(PATH)
                .header("Authorization", "Bearer " + token)
                .header("DPoP", proof(key, token, "GET", TARGET, Instant.now())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsMissingDuplicateAndOrphanProofs() throws Exception {
    mvc.perform(get(PATH).header("Authorization", "DPoP " + token))
        .andExpect(status().isUnauthorized());
    String proof = proof(key, token, "GET", TARGET, Instant.now());
    mvc.perform(request(token, proof).header("DPoP", proof)).andExpect(status().isUnauthorized());
    mvc.perform(request(token, proof).header("Authorization", "Bearer another"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(PATH).header("DPoP", proof)).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsWrongKeyMethodUriAndTokenHash() throws Exception {
    ECKey other = new ECKeyGenerator(Curve.P_256).generate();
    for (String invalid :
        List.of(
            proof(other, token, "GET", TARGET, Instant.now()),
            proof(key, token, "POST", TARGET, Instant.now()),
            proof(key, token, "get", TARGET, Instant.now()),
            proof(key, token, "GET", TARGET + "/other", Instant.now()),
            proof(key, token, "GET", "https://attacker.example" + PATH, Instant.now()),
            proof(key, "other-access-token", "GET", TARGET, Instant.now()))) {
      mvc.perform(request(token, invalid))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("DPoP")));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_dpop_used_proofs", Integer.class))
        .isZero();
  }

  @Test
  void rejectsOldFutureReplayedAndExpiredAccessTokens() throws Exception {
    for (int delta : List.of(-120, 120)) {
      mvc.perform(
              request(token, proof(key, token, "GET", TARGET, Instant.now().plusSeconds(delta))))
          .andExpect(status().isUnauthorized());
    }
    String expired = token(key, Instant.now().minusSeconds(120));
    mvc.perform(request(expired, proof(key, expired, "GET", TARGET, Instant.now())))
        .andExpect(status().isUnauthorized());
    String proof = proof(key, token, "GET", TARGET, Instant.now());
    mvc.perform(request(token, proof)).andExpect(status().isOk());
    mvc.perform(request(token, proof)).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsUnboundDpopAndInvalidSignatureTypeOrJwk() throws Exception {
    String unbound = token(null, Instant.now().plusSeconds(300));
    mvc.perform(request(unbound, proof(key, unbound, "GET", TARGET, Instant.now())))
        .andExpect(status().isUnauthorized());
    JWTClaimsSet claims = claims(token, "GET", TARGET, Instant.now());
    for (JWSHeader header :
        List.of(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("JWT"))
                .jwk(key.toPublicJWK())
                .build(),
            new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("dpop+jwt")).build(),
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(new ECKeyGenerator(Curve.P_256).generate().toPublicJWK())
                .build())) {
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new ECDSASigner(key));
      mvc.perform(request(token, jwt.serialize())).andExpect(status().isUnauthorized());
    }
    SignedJWT symmetric =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("dpop+jwt")).build(),
            claims);
    symmetric.sign(new MACSigner(new byte[32]));
    mvc.perform(request(token, symmetric.serialize())).andExpect(status().isUnauthorized());
    // Nimbus refuses to construct a header with a private JWK, so build the hostile
    // wire representation directly and sign it with the correct EC key.
    String encodedHeader =
        com.nimbusds.jose.util.Base64URL.encode(
                com.nimbusds.jose.util.JSONObjectUtils.toJSONString(
                    Map.of("alg", "ES256", "typ", "dpop+jwt", "jwk", key.toJSONObject())))
            .toString();
    String encodedClaims = com.nimbusds.jose.util.Base64URL.encode(claims.toString()).toString();
    String input = encodedHeader + "." + encodedClaims;
    String signature =
        new ECDSASigner(key)
            .sign(new JWSHeader(JWSAlgorithm.ES256), input.getBytes(StandardCharsets.US_ASCII))
            .toString();
    mvc.perform(request(token, input + "." + signature)).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsMissingRequiredClaimsAndBadAccessTokenSignature() throws Exception {
    for (String missing : List.of("htm", "htu", "iat", "jti", "ath")) {
      Map<String, Object> values =
          new java.util.HashMap<>(claims(token, "GET", TARGET, Instant.now()).toJSONObject());
      values.remove(missing);
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256)
                  .type(new JOSEObjectType("dpop+jwt"))
                  .jwk(key.toPublicJWK())
                  .build(),
              JWTClaimsSet.parse(values));
      jwt.sign(new ECDSASigner(key));
      mvc.perform(request(token, jwt.serialize())).andExpect(status().isUnauthorized());
    }
    SignedJWT invalidToken =
        new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), SignedJWT.parse(token).getJWTClaimsSet());
    invalidToken.sign(new RSASSASigner(issuerKey()));
    mvc.perform(
            request(
                invalidToken.serialize(),
                proof(key, invalidToken.serialize(), "GET", TARGET, Instant.now())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void failsClosedIfSharedReplayStoreIsUnavailable() throws Exception {
    DpopReplayStore unavailable = org.mockito.Mockito.mock(DpopReplayStore.class);
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException("offline"))
        .when(unavailable)
        .consume(org.mockito.ArgumentMatchers.any());
    var replica = new DpopAuthenticationSupport(decoder, converter, unavailable, "");
    var request = new MockHttpServletRequest("GET", PATH);
    request.addHeader("Authorization", "DPoP " + token);
    request.addHeader("DPoP", proof(key, token, "GET", "http://localhost" + PATH, Instant.now()));
    assertThatThrownBy(() -> replica.authenticate(replica.convert(request)))
        .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
  }

  @Test
  void usesPinnedExternalSchemeAndHostAndIgnoresUntrustedProxyHeaders() throws Exception {
    mvc.perform(
            request(token, proof(key, token, "GET", TARGET, Instant.now()))
                .queryParam("ignored", "query")
                .header("Forwarded", "host=evil.example;proto=http")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http"))
        .andExpect(status().isOk());
    mvc.perform(
            request(token, proof(key, token, "GET", "https://legacy.example" + PATH, Instant.now()))
                .header(
                    "Host",
                    "legacy.example")) // two Host values is ambiguous; use a new builder below
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get(PATH)
                .header("Host", "legacy.example")
                .header("Authorization", "DPoP " + token)
                .header(
                    "DPoP",
                    proof(key, token, "GET", "https://legacy.example" + PATH, Instant.now())))
        .andExpect(status().isOk());
    mvc.perform(
            get(PATH)
                .header("Host", "evil.example")
                .header("Authorization", "DPoP " + token)
                .header("DPoP", proof(key, token, "GET", TARGET, Instant.now())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void replayIsAtomicAcrossIndependentReplicaVerifiers() throws Exception {
    DpopAuthenticationSupport first =
        new DpopAuthenticationSupport(decoder, converter, replays, "");
    DpopAuthenticationSupport second =
        new DpopAuthenticationSupport(decoder, converter, replays, "");
    String proof = proof(key, token, "GET", "http://localhost" + PATH, Instant.now());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
    request.addHeader("Authorization", "DPoP " + token);
    request.addHeader("DPoP", proof);
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> attempts =
          java.util.stream.IntStream.range(0, 16)
              .mapToObj(
                  i ->
                      (Callable<Boolean>)
                          () -> {
                            var replica = i % 2 == 0 ? first : second;
                            try {
                              replica.authenticate(replica.convert(request));
                              return true;
                            } catch (
                                org.springframework.security.core.AuthenticationException
                                    rejected) {
                              return false;
                            }
                          })
              .toList();
      long accepted = 0;
      for (var result : pool.invokeAll(attempts)) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_dpop_used_proofs", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void corsAllowsProofHeadersOnlyForConfiguredOrigins() throws Exception {
    mvc.perform(
            options(PATH)
                .header("Origin", "https://dashboard.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,dpop"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Access-Control-Allow-Headers"));
    mvc.perform(
            options(PATH)
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  static MockHttpServletRequestBuilder request(String token, String proof) {
    return get(PATH)
        .header("Host", "bluechat.example")
        .header("Authorization", "DPoP " + token)
        .header("DPoP", proof);
  }

  static String token(ECKey key, Instant expiresAt) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("test-subject")
            .issueTime(Date.from(Instant.now().minusSeconds(300)))
            .expirationTime(Date.from(expiresAt));
    if (key != null) claims.claim("cnf", Map.of("jkt", key.computeThumbprint().toString()));
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    jwt.sign(new RSASSASigner(ISSUER_KEY));
    return jwt.serialize();
  }

  static JWTClaimsSet claims(String token, String method, String uri, Instant time)
      throws Exception {
    return new JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(time))
        .claim("htm", method)
        .claim("htu", uri)
        .claim(
            "ath",
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.US_ASCII))))
        .build();
  }

  static String proof(ECKey key, String token, String method, String uri, Instant time)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(key.toPublicJWK())
                .build(),
            claims(token, method, uri, time));
    jwt.sign(new ECDSASigner(key));
    return jwt.serialize();
  }

  static RSAKey issuerKey() {
    try {
      return new RSAKeyGenerator(2048).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @RestController
  static class Resource {
    @GetMapping(PATH)
    String get(@AuthenticationPrincipal Jwt jwt) {
      return jwt.getSubject();
    }

    @GetMapping("/api/v1/admin/dpopTest")
    String admin() {
      return "admin";
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    JwtDecoder jwtDecoder() throws Exception {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withPublicKey(ISSUER_KEY.toRSAPublicKey()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
      return decoder;
    }

    @Bean
    JdbcTemplate jdbcTemplate() {
      var source = new DriverManagerDataSource("jdbc:h2:mem:dpop;DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V33__oauth_dpop_replay_protection.sql"))
          .execute(source);
      return new JdbcTemplate(source);
    }
  }
}
