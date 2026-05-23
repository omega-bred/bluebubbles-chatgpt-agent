package io.breland.bbagent.server.agent.tools.coder;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

final class CoderToolNameMapper {

  private static final int MAX_TOOL_NAME_LENGTH = 64;

  String toAgentToolName(String mcpToolName) {
    String normalized =
        mcpToolName == null
            ? "tool"
            : mcpToolName.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
    normalized = normalized.replaceAll("^_+|_+$", "");
    normalized = StringUtils.defaultIfBlank(normalized, "tool");
    String hash = shortHash(mcpToolName);
    int maxBaseLength =
        MAX_TOOL_NAME_LENGTH - CoderMcpClient.TOOL_PREFIX.length() - hash.length() - 1;
    String truncated = StringUtils.truncate(normalized, maxBaseLength);
    return CoderMcpClient.TOOL_PREFIX + truncated + "_" + hash;
  }

  String disambiguateAgentToolName(String mcpToolName, int count) {
    String suffix = "_" + count;
    String base = toAgentToolName(mcpToolName);
    if (base.length() + suffix.length() <= MAX_TOOL_NAME_LENGTH) {
      return base + suffix;
    }
    return StringUtils.truncate(base, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
  }

  private String shortHash(String value) {
    return DigestUtils.sha256Hex(String.valueOf(value)).substring(0, 12);
  }
}
