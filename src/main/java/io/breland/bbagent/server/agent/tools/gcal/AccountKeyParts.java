package io.breland.bbagent.server.agent.tools.gcal;

import org.springframework.util.StringUtils;

public record AccountKeyParts(String accountBase, String accountId) {
  public static final String ACCOUNT_DELIM = "::";
  public static final String DEFAULT_ACCOUNT_ID = "default";

  public static AccountKeyParts parse(String accountKey) {
    if (!StringUtils.hasText(accountKey)) {
      return new AccountKeyParts(null, null);
    }
    int delimIndex = accountKey.indexOf(ACCOUNT_DELIM);
    if (delimIndex < 0) {
      return new AccountKeyParts(accountKey, DEFAULT_ACCOUNT_ID);
    }
    String base = accountKey.substring(0, delimIndex);
    String accountId = accountKey.substring(delimIndex + ACCOUNT_DELIM.length());
    if (!StringUtils.hasText(accountId)) {
      accountId = DEFAULT_ACCOUNT_ID;
    }
    return new AccountKeyParts(base, accountId);
  }
}
