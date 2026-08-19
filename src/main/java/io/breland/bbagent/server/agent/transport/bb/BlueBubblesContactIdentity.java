package io.breland.bbagent.server.agent.transport.bb;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

public record BlueBubblesContactIdentity(String displayName, List<String> addresses) {
  public BlueBubblesContactIdentity {
    displayName = StringUtils.trimToEmpty(displayName);
    addresses =
        addresses == null
            ? List.of()
            : addresses.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
  }
}
