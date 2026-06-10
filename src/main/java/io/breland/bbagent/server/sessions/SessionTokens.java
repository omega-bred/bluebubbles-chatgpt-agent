package io.breland.bbagent.server.sessions;

import java.security.SecureRandom;
import java.util.Base64;
import org.apache.commons.codec.digest.DigestUtils;

public final class SessionTokens {
  private SessionTokens() {}

  public static String randomUrlToken(SecureRandom secureRandom, int bytesLength) {
    byte[] bytes = new byte[bytesLength];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256Hash(String token) {
    return DigestUtils.sha256Hex(token);
  }
}
