package io.breland.bbagent.server.agent.tools.gcal;

import org.apache.commons.lang3.StringUtils;

public record AccountKeyParts(String accountId, String googleAccountId) {
  public static final String ACCOUNT_DELIM = "::";
  public static final String DEFAULT_ACCOUNT_ID = "default";

  public static AccountKeyParts parse(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return new AccountKeyParts(null, null);
    }
    String accountId = StringUtils.substringBefore(accountKey, ACCOUNT_DELIM);
    String googleAccountId =
        StringUtils.defaultIfBlank(
            StringUtils.substringAfter(accountKey, ACCOUNT_DELIM), DEFAULT_ACCOUNT_ID);
    return new AccountKeyParts(accountId, googleAccountId);
  }
}
