package io.breland.bbagent.server.subscriptions;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionProviderRegistry {
  private final Map<String, SubscriptionProvider> providers;

  public SubscriptionProviderRegistry(List<SubscriptionProvider> providers) {
    this.providers =
        providers.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    provider -> provider.providerKey().toLowerCase(Locale.ROOT),
                    Function.identity()));
  }

  public SubscriptionProvider require(String providerKey) {
    String normalized = StringUtils.trimToNull(providerKey);
    if (normalized == null) {
      throw new IllegalArgumentException("Missing subscription provider");
    }
    SubscriptionProvider provider = providers.get(normalized.toLowerCase(Locale.ROOT));
    if (provider == null) {
      throw new IllegalArgumentException("Unknown subscription provider " + providerKey);
    }
    return provider;
  }

  public List<String> providerKeys() {
    return providers.keySet().stream().sorted().toList();
  }
}
