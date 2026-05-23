package io.breland.bbagent.server.subscriptions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bbagent.subscriptions")
@Getter
@Setter
public class SubscriptionProperties {
  private boolean enabled = true;
  private String defaultProvider = "btcpay";
  private int checkoutDurationMinutes = 30;
  private String returnUrl = "http://localhost:8080/account";
  private List<Plan> plans = new ArrayList<>();
  private Map<String, ProviderSettings> providers = new LinkedHashMap<>();

  public Plan requirePlan(String planKey) {
    String requested = planKey == null || planKey.isBlank() ? defaultPlanKey() : planKey.trim();
    return plans.stream()
        .filter(plan -> requested.equals(plan.getKey()) && plan.isActive())
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown subscription plan"));
  }

  public String defaultPlanKey() {
    return plans.stream()
        .filter(Plan::isActive)
        .map(Plan::getKey)
        .findFirst()
        .orElse("premium_monthly");
  }

  public ProviderPlan requireProviderPlan(Plan plan, String providerKey) {
    Map<String, ProviderPlan> providerPlans = plan.getProviders();
    ProviderPlan providerPlan =
        providerPlans == null
            ? null
            : providerPlans.get(providerKey == null ? "" : providerKey.trim());
    if (providerPlan == null
        || providerPlan.getPlanId() == null
        || providerPlan.getPlanId().isBlank()) {
      throw new IllegalStateException(
          "Subscription plan is not configured for provider " + providerKey);
    }
    return providerPlan;
  }

  public ProviderSettings providerSettings(String providerKey) {
    return providers == null
        ? new ProviderSettings()
        : providers.getOrDefault(providerKey, new ProviderSettings());
  }

  @Getter
  @Setter
  public static class Plan {
    private String key = "premium_monthly";
    private String displayName = "Premium";
    private String description = "Premium model access";
    private BigDecimal priceAmount = BigDecimal.ZERO;
    private String currency = "USD";
    private String billingInterval = "monthly";
    private String entitlement = "premium";
    private boolean active = true;
    private int sortOrder = 0;
    private Map<String, ProviderPlan> providers = new LinkedHashMap<>();
  }

  @Getter
  @Setter
  public static class ProviderPlan {
    private String offeringId;
    private String planId;
  }

  @Getter
  @Setter
  public static class ProviderSettings {
    private boolean enabled = true;
    private String baseUrl = "";
    private String apiKey = "";
    private String storeId = "";
    private String webhookSecret = "";
    private String portalConfigurationId = "";
    private boolean automaticTaxEnabled = false;
    private boolean testModeOnly = false;
  }
}
