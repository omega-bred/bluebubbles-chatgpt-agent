package io.breland.bbagent.server.agent.model_picker;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelOption;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ModelAccessService {
  public static final String STANDARD_MODEL_KEY = "local";
  public static final String STANDARD_MODEL_LABEL = "Free";
  public static final String STANDARD_RESPONSES_MODEL = "Qwen/Qwen3.6-27B";
  public static final String PREMIUM_MODEL_KEY = "chatgpt";
  public static final String PREMIUM_MODEL_LABEL = "ChatGPT";
  public static final String PREMIUM_RESPONSES_MODEL = "openai/gpt-5.4";
  public static final Set<String> DEVELOPER_MESSAGE_SYSTEM_SQUASH_MODELS =
      Set.of(STANDARD_RESPONSES_MODEL);
  private static final List<ModelOption> STANDARD_OPTIONS =
      List.of(new ModelOption(STANDARD_MODEL_KEY, STANDARD_MODEL_LABEL, "local", true));
  private static final List<ModelOption> PREMIUM_OPTIONS =
      List.of(
          new ModelOption(PREMIUM_MODEL_KEY, PREMIUM_MODEL_LABEL, "openai", true),
          new ModelOption("claude", "Claude", "anthropic", false),
          new ModelOption("gemini", "Gemini", "google", false),
          new ModelOption(STANDARD_MODEL_KEY, STANDARD_MODEL_LABEL, "local", true));
  private static final Map<String, ModelOption> PREMIUM_OPTIONS_BY_KEY =
      PREMIUM_OPTIONS.stream()
          .collect(Collectors.toUnmodifiableMap(ModelOption::modelKey, Function.identity()));

  private final AgentAccountRepository accountRepository;
  private final AgentAccountResolver accountResolver;

  public ModelAccessService(
      AgentAccountRepository accountRepository, @Nullable AgentAccountResolver accountResolver) {
    this.accountRepository = accountRepository;
    this.accountResolver = accountResolver;
  }

  public ModelAccess resolve(IncomingMessage message) {
    if (accountResolver == null) {
      return standard(null);
    }
    return accountResolver
        .resolveOrCreate(message)
        .map(resolved -> fromEntity(resolved.account()))
        .orElseGet(() -> standard(null));
  }

  public ModelAccess resolve(@Nullable String accountId) {
    String cleanAccountId = StringUtils.defaultIfBlank(accountId, null);
    if (cleanAccountId == null) {
      return standard(null);
    }
    return accountRepository
        .findById(cleanAccountId)
        .map(this::fromEntity)
        .orElseGet(() -> standard(cleanAccountId));
  }

  public boolean isPremium(IncomingMessage message) {
    return resolve(message).premium();
  }

  public WebsiteModelAccessSummary toWebsiteSummary(@Nullable String accountId) {
    return toWebsiteSummary(resolve(accountId));
  }

  public WebsiteModelAccessSummary toWebsiteSummary(ModelAccess access) {
    return new WebsiteModelAccessSummary()
        .accountId(access.accountId())
        .plan(WebsiteModelAccessSummary.PlanEnum.fromValue(access.plan()))
        .isPremium(access.premium())
        .currentModel(access.currentModelKey())
        .currentModelLabel(access.currentModelLabel())
        .modelSelectionAllowed(access.modelSelectionAllowed())
        .modelSelectionConfigurable(false)
        .readOnlyReason(
            access.premium()
                ? "Model choices for premium accounts are coming soon."
                : "Free accounts use the included model.")
        .availableModels(access.availableModels().stream().map(this::toWebsiteOption).toList());
  }

  public String resolveAccountId(IncomingMessage message) {
    if (accountResolver == null) {
      return null;
    }
    return accountResolver
        .resolveOrCreate(message)
        .map(resolved -> resolved.account().getAccountId())
        .orElse(null);
  }

  private ModelAccess fromEntity(AgentAccountEntity entity) {
    if (entity.isPremium()) {
      String selected = StringUtils.defaultIfBlank(entity.getSelectedModel(), null);
      String modelKey = selected == null ? PREMIUM_MODEL_KEY : selected;
      return new ModelAccess(
          entity.getAccountId(),
          true,
          "premium",
          modelKey,
          displayNameFor(modelKey),
          responsesModelFor(modelKey),
          true,
          PREMIUM_OPTIONS);
    }
    return standard(entity.getAccountId());
  }

  private ModelAccess standard(String accountId) {
    return new ModelAccess(
        accountId,
        false,
        "standard",
        STANDARD_MODEL_KEY,
        STANDARD_MODEL_LABEL,
        STANDARD_RESPONSES_MODEL,
        false,
        STANDARD_OPTIONS);
  }

  private WebsiteModelOption toWebsiteOption(ModelOption option) {
    return new WebsiteModelOption()
        .model(option.modelKey())
        .label(option.label())
        .provider(option.provider())
        .enabled(option.enabled());
  }

  private String displayNameFor(String modelKey) {
    return PREMIUM_OPTIONS_BY_KEY
        .getOrDefault(modelKey, PREMIUM_OPTIONS_BY_KEY.get(PREMIUM_MODEL_KEY))
        .label();
  }

  private String responsesModelFor(String modelKey) {
    if (STANDARD_MODEL_KEY.equals(modelKey)) {
      return STANDARD_RESPONSES_MODEL;
    }
    return PREMIUM_RESPONSES_MODEL;
  }

  public record ModelAccess(
      String accountId,
      boolean premium,
      String plan,
      String currentModelKey,
      String currentModelLabel,
      String responsesModel,
      boolean modelSelectionAllowed,
      List<ModelOption> availableModels) {}

  public record ModelOption(String modelKey, String label, String provider, boolean enabled) {}
}
