package io.breland.bbagent.server.agent.tools.gcal;

public record AccountKeyParts(String accountId, String googleAccountId) {
  public static final String ACCOUNT_DELIM = "::";
  public static final String DEFAULT_ACCOUNT_ID = "default";

  public static AccountKeyParts parse(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return new AccountKeyParts(null, null);
    }
    int delimIndex = accountKey.indexOf(ACCOUNT_DELIM);
    if (delimIndex < 0) {
      return new AccountKeyParts(accountKey, DEFAULT_ACCOUNT_ID);
    }
    String accountId = accountKey.substring(0, delimIndex);
    String googleAccountId = accountKey.substring(delimIndex + ACCOUNT_DELIM.length());
    if (googleAccountId.isBlank()) {
      googleAccountId = DEFAULT_ACCOUNT_ID;
    }
    return new AccountKeyParts(accountId, googleAccountId);
  }
}
