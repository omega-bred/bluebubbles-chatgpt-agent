package io.breland.bbagent.server.config;

import com.nimbusds.jose.jwk.JWK;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Shared, atomic replay protection; never fall back to a per-pod cache on database failure. */
public final class DpopReplayStore {
  private final ObjectProvider<JdbcTemplate> jdbc;

  public DpopReplayStore(ObjectProvider<JdbcTemplate> jdbc) {
    this.jdbc = jdbc;
  }

  public void consume(Jwt proof) {
    // Scope jti to its proof key. Store hashes only, never access tokens or whole proofs.
    String hash;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> jwk = (Map<String, Object>) proof.getHeaders().get("jwk");
      String id = JWK.parse(jwk).computeThumbprint() + "\n" + proof.getId();
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception invalidKey) {
      throw new BadJwtException("Invalid DPoP key or proof identifier");
    }
    try {
      // Spring accepts iat within +/-30 seconds. Retain beyond that entire window.
      jdbc.getObject()
          .update(
              "INSERT INTO oauth_dpop_used_proofs (proof_hash, expires_at) VALUES (?, ?)",
              hash,
              Timestamp.from(proof.getIssuedAt().plusSeconds(120)));
    } catch (DuplicateKeyException replay) {
      throw new BadJwtException("DPoP proof has already been used");
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void cleanup() {
    jdbc.getObject()
        .update(
            "DELETE FROM oauth_dpop_used_proofs WHERE expires_at < ?",
            Timestamp.from(Instant.now()));
  }
}
