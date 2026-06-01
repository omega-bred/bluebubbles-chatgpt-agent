package io.breland.bbagent.server.appclip;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

public final class AppAccountTokens {
  private static final String NAMESPACE = "bluechat-app-account-token:";

  private AppAccountTokens() {}

  public static UUID forAccountId(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      throw new IllegalArgumentException("Missing account id");
    }
    return UUID.nameUUIDFromBytes((NAMESPACE + accountId.trim()).getBytes(StandardCharsets.UTF_8));
  }
}
