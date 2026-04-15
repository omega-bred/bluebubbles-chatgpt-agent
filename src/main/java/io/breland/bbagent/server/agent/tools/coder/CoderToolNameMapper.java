package io.breland.bbagent.server.agent.tools.coder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class CoderToolNameMapper {

  private static final int MAX_TOOL_NAME_LENGTH = 64;

  String toAgentToolName(String mcpToolName) {
    String normalized =
        mcpToolName == null
            ? "tool"
            : mcpToolName.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
    if (normalized.isBlank()) {
      normalized = "tool";
    }
    String hash = shortHash(mcpToolName);
    int maxBaseLength =
        MAX_TOOL_NAME_LENGTH - CoderMcpClient.TOOL_PREFIX.length() - hash.length() - 1;
    String truncated =
        normalized.length() > maxBaseLength ? normalized.substring(0, maxBaseLength) : normalized;
    return CoderMcpClient.TOOL_PREFIX + truncated + "_" + hash;
  }

  String disambiguateAgentToolName(String mcpToolName, int count) {
    String suffix = "_" + count;
    String base = toAgentToolName(mcpToolName);
    if (base.length() + suffix.length() <= MAX_TOOL_NAME_LENGTH) {
      return base + suffix;
    }
    return base.substring(0, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
  }

  private String shortHash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        builder.append(String.format(Locale.ROOT, "%02x", hashed[i]));
      }
      return builder.toString();
    } catch (Exception e) {
      return Integer.toHexString(String.valueOf(value).hashCode());
    }
  }
}
