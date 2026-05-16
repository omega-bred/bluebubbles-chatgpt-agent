package io.breland.bbagent.server.agent.tools.coder;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class CoderOauthStateCodec {

  private final Algorithm algorithm;
  private final Duration ttl;
  private final SecureRandom secureRandom = new SecureRandom();

  CoderOauthStateCodec(String stateSecret, Duration ttl) {
    this.algorithm =
        stateSecret == null || stateSecret.isBlank() ? null : Algorithm.HMAC256(stateSecret);
    this.ttl = ttl;
  }

  boolean isConfigured() {
    return algorithm != null;
  }

  Optional<String> createState(
      String accountId, String pendingId, String chatGuid, String messageGuid) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    try {
      Instant now = Instant.now();
      return Optional.of(
          JWT.create()
              .withIssuedAt(Date.from(now))
              .withExpiresAt(Date.from(now.plus(ttl)))
              .withClaim("account_id", accountId)
              .withClaim("pending_id", pendingId)
              .withClaim("chat_guid", chatGuid)
              .withClaim("message_guid", messageGuid)
              .sign(algorithm));
    } catch (Exception e) {
      log.warn("Failed to build Coder OAuth state", e);
      return Optional.empty();
    }
  }

  Optional<OauthState> parseState(String state) {
    if (state == null || state.isBlank() || !isConfigured()) {
      return Optional.empty();
    }
    try {
      JWTVerifier verifier = JWT.require(algorithm).build();
      DecodedJWT jwt = verifier.verify(state);
      String accountId = jwt.getClaim("account_id").asString();
      String pendingId = jwt.getClaim("pending_id").asString();
      String chatGuid = jwt.getClaim("chat_guid").asString();
      String messageGuid = jwt.getClaim("message_guid").asString();
      if (accountId == null || accountId.isBlank() || pendingId == null || pendingId.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new OauthState(accountId, pendingId, chatGuid, messageGuid));
    } catch (JWTVerificationException e) {
      log.warn("Failed to parse Coder OAuth state", e);
      return Optional.empty();
    }
  }

  String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  String codeChallenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create PKCE code challenge", e);
    }
  }

  record OauthState(String accountId, String pendingId, String chatGuid, String messageGuid) {}
}
