package io.breland.bbagent.server;

import org.apache.commons.lang3.StringUtils;

public final class StringSupport {
  private StringSupport() {}

  public static String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }
}
