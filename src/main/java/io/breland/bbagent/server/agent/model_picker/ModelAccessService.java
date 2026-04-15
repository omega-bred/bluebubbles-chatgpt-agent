package io.breland.bbagent.server.agent.model_picker;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelOption;
import io.breland.bbagent.server.StringValueUtils;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.model.ModelAccountSettingsEntity;
import io.breland.bbagent.server.agent.persistence.model.ModelAccountSettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ModelAccessService {
  public static final String STANDARD_MODEL_KEY = "local";
  public static final String STANDARD_MODEL_LABEL = "Free";
  public static final String STANDARD_RESPONSES_MODEL = "google/gemma-4-31B-it";
  public static final String PREMIUM_MODEL_KEY = "chatgpt";
  public static final String PREMIUM_MODEL_LABEL = "ChatGPT";
  public static final String PREMIUM_RESPONSES_MODEL = "openai/gpt-5.4";
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

  private final ModelAccountSettingsRepository repository;

  public ModelAccessService(ModelAccountSettingsRepository repository) {
    this.repository = repository;
  }

  public ModelAccess resolve(IncomingMessage message) {
    return resolve(resolveAccountBase(message));
  }

  public ModelAccess resolve(@Nullable String accountBase) {
    String cleanAccountBase = StringValueUtils.clean(accountBase);
    if (cleanAccountBase == null) {
      return standard(null);
    }
    return repository
        .findById(cleanAccountBase)
        .map(this::fromEntity)
        .orElseGet(() -> standard(cleanAccountBase));
  }

  public boolean isPremium(IncomingMessage message) {
    return resolve(message).premium();
  }

  public WebsiteModelAccessSummary toWebsiteSummary(@Nullable String accountBase) {
    return toWebsiteSummary(resolve(accountBase));
  }

  public WebsiteModelAccessSummary toWebsiteSummary(ModelAccess access) {
    return new WebsiteModelAccessSummary()
        .accountBase(access.accountBase())
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

  public String resolveAccountBase(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    String sender = StringValueUtils.clean(message.sender());
    if (sender != null) {
      return sender;
    }
    return StringValueUtils.clean(message.chatGuid());
  }

  private ModelAccess fromEntity(ModelAccountSettingsEntity entity) {
    if (entity.isPremium()) {
      String selected = StringValueUtils.clean(entity.getSelectedModel());
      String modelKey = selected == null ? PREMIUM_MODEL_KEY : selected;
      return new ModelAccess(
          entity.getAccountBase(),
          true,
          "premium",
          modelKey,
          displayNameFor(modelKey),
          responsesModelFor(modelKey),
          true,
          PREMIUM_OPTIONS);
    }
    return standard(entity.getAccountBase());
  }

  private ModelAccess standard(String accountBase) {
    return new ModelAccess(
        accountBase,
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
      String accountBase,
      boolean premium,
      String plan,
      String currentModelKey,
      String currentModelLabel,
      String responsesModel,
      boolean modelSelectionAllowed,
      List<ModelOption> availableModels) {}

  public record ModelOption(String modelKey, String label, String provider, boolean enabled) {}
}
