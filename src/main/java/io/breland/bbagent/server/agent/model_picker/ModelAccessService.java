package io.breland.bbagent.server.agent.model_picker;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelOption;
import io.breland.bbagent.generated.model.WebsiteModelVerbosityOption;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelAccessService {
  public static final String STANDARD_MODEL_KEY = "local";
  public static final String STANDARD_MODEL_LABEL = "Free";
  public static final String STANDARD_RESPONSES_MODEL = "Qwen/Qwen3.6-27B";
  public static final String PREMIUM_MODEL_KEY = "chatgpt";
  public static final String PREMIUM_MODEL_LABEL = "ChatGPT";
  public static final String PREMIUM_RESPONSES_MODEL = "openai/gpt-5.4";
  public static final String CLAUDE_MODEL_KEY = "claude";
  public static final String CLAUDE_MODEL_LABEL = "Claude";
  public static final String CLAUDE_RESPONSES_MODEL = "anthropic/claude-sonnet-4-6";
  public static final String GEMINI_MODEL_KEY = "gemini";
  public static final String GEMINI_MODEL_LABEL = "Gemini";
  public static final String GEMINI_RESPONSES_MODEL = "gemini/gemini-3.5-flash";
  public static final String VERBOSITY_LOW = "low";
  public static final String VERBOSITY_MEDIUM = "medium";
  public static final String VERBOSITY_HIGH = "high";
  public static final Set<String> DEVELOPER_MESSAGE_SYSTEM_SQUASH_MODELS =
      Set.of(STANDARD_RESPONSES_MODEL);
  private static final List<ModelOption> STANDARD_OPTIONS =
      List.of(new ModelOption(STANDARD_MODEL_KEY, STANDARD_MODEL_LABEL, "local", true));
  private static final List<ModelOption> PREMIUM_OPTIONS =
      List.of(
          new ModelOption(PREMIUM_MODEL_KEY, PREMIUM_MODEL_LABEL, "openai", true),
          new ModelOption(CLAUDE_MODEL_KEY, CLAUDE_MODEL_LABEL, "anthropic", true),
          new ModelOption(GEMINI_MODEL_KEY, GEMINI_MODEL_LABEL, "google", true),
          new ModelOption(STANDARD_MODEL_KEY, STANDARD_MODEL_LABEL, "local", true));
  private static final Map<String, ModelOption> PREMIUM_OPTIONS_BY_KEY =
      PREMIUM_OPTIONS.stream()
          .collect(Collectors.toUnmodifiableMap(ModelOption::modelKey, Function.identity()));
  private static final List<VerbosityOption> VERBOSITY_OPTIONS =
      List.of(
          new VerbosityOption(VERBOSITY_LOW, "Concise", "Shorter, more direct replies.", true),
          new VerbosityOption(VERBOSITY_MEDIUM, "Balanced", "Default reply length.", true),
          new VerbosityOption(
              VERBOSITY_HIGH, "Detailed", "More complete replies with extra context.", true));
  private static final Map<String, VerbosityOption> VERBOSITY_OPTIONS_BY_KEY =
      VERBOSITY_OPTIONS.stream()
          .collect(
              Collectors.toUnmodifiableMap(VerbosityOption::verbosityKey, Function.identity()));

  private final AgentAccountRepository accountRepository;
  private final AgentAccountResolver accountResolver;

  @Value("${bbagent.models.local.responses-model:" + STANDARD_RESPONSES_MODEL + "}")
  private String standardResponsesModel = STANDARD_RESPONSES_MODEL;

  @Value("${bbagent.models.chatgpt.responses-model:" + PREMIUM_RESPONSES_MODEL + "}")
  private String chatGptResponsesModel = PREMIUM_RESPONSES_MODEL;

  @Value("${bbagent.models.claude.responses-model:" + CLAUDE_RESPONSES_MODEL + "}")
  private String claudeResponsesModel = CLAUDE_RESPONSES_MODEL;

  @Value("${bbagent.models.gemini.responses-model:" + GEMINI_RESPONSES_MODEL + "}")
  private String geminiResponsesModel = GEMINI_RESPONSES_MODEL;

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
        .isPremium(access.premium())
        .currentModel(access.currentModelKey())
        .currentModelLabel(access.currentModelLabel())
        .currentVerbosity(
            WebsiteModelAccessSummary.CurrentVerbosityEnum.fromValue(access.currentVerbosityKey()))
        .currentVerbosityLabel(access.currentVerbosityLabel())
        .modelSelectionAllowed(access.modelSelectionAllowed())
        .modelSelectionConfigurable(access.modelSelectionAllowed())
        .verbositySelectionAllowed(access.verbositySelectionAllowed())
        .verbositySelectionConfigurable(access.verbositySelectionAllowed())
        .readOnlyReason(access.premium() ? null : "Free accounts use the included model.")
        .availableModels(access.availableModels().stream().map(this::toWebsiteOption).toList())
        .availableVerbosityOptions(
            access.availableVerbosityOptions().stream()
                .map(this::toWebsiteVerbosityOption)
                .toList());
  }

  public ModelSelectionResult selectModel(IncomingMessage message, String modelKey) {
    return updatePreferences(message, modelKey, null);
  }

  public ModelSelectionResult updatePreferences(
      IncomingMessage message, @Nullable String modelKey, @Nullable String verbosityKey) {
    if (accountResolver == null) {
      return new ModelSelectionResult(false, standard(null), "Account resolution is unavailable.");
    }
    return accountResolver
        .resolveOrCreate(message)
        .map(
            resolved ->
                updatePreferences(resolved.account().getAccountId(), modelKey, verbosityKey))
        .orElseGet(
            () ->
                new ModelSelectionResult(
                    false, standard(null), "I could not resolve the current chat account."));
  }

  public ModelSelectionResult selectModel(String accountId, String modelKey) {
    return updatePreferences(accountId, modelKey, null);
  }

  public ModelSelectionResult updatePreferences(
      String accountId, @Nullable String modelKey, @Nullable String verbosityKey) {
    String cleanAccountId = StringUtils.defaultIfBlank(accountId, null);
    String cleanModelKey = normalizeOptionalModelKey(modelKey);
    String cleanVerbosityKey = normalizeOptionalVerbosityKey(verbosityKey);
    if (cleanAccountId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing account id");
    }
    if (cleanModelKey == null && cleanVerbosityKey == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose a model or verbosity setting");
    }
    if (cleanModelKey != null && !isSelectableModel(cleanModelKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown or unavailable model");
    }
    if (cleanVerbosityKey != null && !isSelectableVerbosity(cleanVerbosityKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown verbosity setting");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(cleanAccountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (cleanModelKey != null && !account.isPremium()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Model switching is only available to premium accounts");
    }
    boolean changed = false;
    if (cleanModelKey != null
        && !cleanModelKey.equals(
            normalizeOptionalModelKey(
                StringUtils.defaultIfBlank(account.getSelectedModel(), null)))) {
      account.setSelectedModel(cleanModelKey);
      changed = true;
    }
    if (cleanVerbosityKey != null
        && !cleanVerbosityKey.equals(normalizeVerbosityKey(account.getModelVerbosity()))) {
      account.setModelVerbosity(cleanVerbosityKey);
      changed = true;
    }
    account.setUpdatedAt(Instant.now());
    AgentAccountEntity saved = accountRepository.save(account);
    ModelAccess access = fromEntity(saved);
    return new ModelSelectionResult(
        changed, access, preferenceMessage(cleanModelKey, cleanVerbosityKey, access));
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
    String verbosityKey = normalizeVerbosityKey(entity.getModelVerbosity());
    if (entity.isPremium()) {
      String selected = StringUtils.defaultIfBlank(entity.getSelectedModel(), null);
      String modelKey = isSelectableModel(selected) ? selected : PREMIUM_MODEL_KEY;
      return new ModelAccess(
          entity.getAccountId(),
          true,
          modelKey,
          displayNameFor(modelKey),
          responsesModelFor(modelKey),
          verbosityKey,
          verbosityLabelFor(verbosityKey),
          true,
          PREMIUM_OPTIONS,
          VERBOSITY_OPTIONS);
    }
    return standard(entity.getAccountId(), verbosityKey);
  }

  private ModelAccess standard(String accountId) {
    return standard(accountId, VERBOSITY_MEDIUM);
  }

  private ModelAccess standard(String accountId, String verbosityKey) {
    String cleanVerbosityKey = normalizeVerbosityKey(verbosityKey);
    return new ModelAccess(
        accountId,
        false,
        STANDARD_MODEL_KEY,
        STANDARD_MODEL_LABEL,
        standardResponsesModel,
        cleanVerbosityKey,
        verbosityLabelFor(cleanVerbosityKey),
        false,
        STANDARD_OPTIONS,
        VERBOSITY_OPTIONS);
  }

  private WebsiteModelOption toWebsiteOption(ModelOption option) {
    return new WebsiteModelOption()
        .model(option.modelKey())
        .label(option.label())
        .provider(option.provider())
        .enabled(option.enabled());
  }

  private WebsiteModelVerbosityOption toWebsiteVerbosityOption(VerbosityOption option) {
    return new WebsiteModelVerbosityOption()
        .verbosity(WebsiteModelVerbosityOption.VerbosityEnum.fromValue(option.verbosityKey()))
        .label(option.label())
        .description(option.description())
        .enabled(option.enabled());
  }

  private static String displayNameFor(String modelKey) {
    return PREMIUM_OPTIONS_BY_KEY
        .getOrDefault(modelKey, PREMIUM_OPTIONS_BY_KEY.get(PREMIUM_MODEL_KEY))
        .label();
  }

  private String responsesModelFor(String modelKey) {
    if (STANDARD_MODEL_KEY.equals(modelKey)) {
      return standardResponsesModel;
    }
    if (CLAUDE_MODEL_KEY.equals(modelKey)) {
      return claudeResponsesModel;
    }
    if (GEMINI_MODEL_KEY.equals(modelKey)) {
      return geminiResponsesModel;
    }
    return chatGptResponsesModel;
  }

  private static String providerFor(String modelKey) {
    if (STANDARD_MODEL_KEY.equals(modelKey)) {
      return "local";
    }
    return PREMIUM_OPTIONS_BY_KEY
        .getOrDefault(modelKey, PREMIUM_OPTIONS_BY_KEY.get(PREMIUM_MODEL_KEY))
        .provider();
  }

  private static String verbosityLabelFor(String verbosityKey) {
    return VERBOSITY_OPTIONS_BY_KEY
        .getOrDefault(verbosityKey, VERBOSITY_OPTIONS_BY_KEY.get(VERBOSITY_MEDIUM))
        .label();
  }

  private static boolean isSelectableVerbosity(String verbosityKey) {
    if (StringUtils.isBlank(verbosityKey)) {
      return false;
    }
    VerbosityOption option = VERBOSITY_OPTIONS_BY_KEY.get(verbosityKey);
    return option != null && option.enabled();
  }

  private static String normalizeVerbosityKey(String verbosityKey) {
    String clean = normalizeOptionalVerbosityKey(verbosityKey);
    return clean == null || !isSelectableVerbosity(clean) ? VERBOSITY_MEDIUM : clean;
  }

  private static boolean isSelectableModel(String modelKey) {
    if (StringUtils.isBlank(modelKey)) {
      return false;
    }
    ModelOption option = PREMIUM_OPTIONS_BY_KEY.get(modelKey);
    return option != null && option.enabled();
  }

  private static String normalizeModelKey(String modelKey) {
    return StringUtils.defaultString(modelKey).trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static @Nullable String normalizeOptionalModelKey(@Nullable String modelKey) {
    return StringUtils.isBlank(modelKey) ? null : normalizeModelKey(modelKey);
  }

  private static @Nullable String normalizeOptionalVerbosityKey(@Nullable String verbosityKey) {
    return StringUtils.isBlank(verbosityKey)
        ? null
        : StringUtils.defaultString(verbosityKey).trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static String preferenceMessage(
      @Nullable String modelKey, @Nullable String verbosityKey, ModelAccess access) {
    if (modelKey != null && verbosityKey != null) {
      return "Model changed to "
          + access.currentModelLabel()
          + " with "
          + access.currentVerbosityLabel().toLowerCase(java.util.Locale.ROOT)
          + " replies.";
    }
    if (modelKey != null) {
      return "Model changed to " + access.currentModelLabel() + " for this account.";
    }
    return "Response style changed to "
        + access.currentVerbosityLabel().toLowerCase(java.util.Locale.ROOT)
        + " for this account.";
  }

  public record ModelAccess(
      String accountId,
      boolean premium,
      String currentModelKey,
      String currentModelLabel,
      String responsesModel,
      String currentVerbosityKey,
      String currentVerbosityLabel,
      boolean modelSelectionAllowed,
      List<ModelOption> availableModels,
      List<VerbosityOption> availableVerbosityOptions) {
    public String provider() {
      return providerFor(currentModelKey);
    }

    public boolean verbositySelectionAllowed() {
      return StringUtils.isNotBlank(accountId);
    }

    public boolean supportsImageGeneration() {
      return PREMIUM_MODEL_KEY.equals(currentModelKey);
    }

    public boolean supportsWebSearch() {
      return PREMIUM_MODEL_KEY.equals(currentModelKey);
    }
  }

  public record ModelOption(String modelKey, String label, String provider, boolean enabled) {}

  public record VerbosityOption(
      String verbosityKey, String label, String description, boolean enabled) {}

  public record ModelSelectionResult(boolean changed, ModelAccess modelAccess, String message) {}
}
