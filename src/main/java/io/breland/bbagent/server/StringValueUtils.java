package io.breland.bbagent.server;

public final class StringValueUtils {
  private StringValueUtils() {}

  public static String clean(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
