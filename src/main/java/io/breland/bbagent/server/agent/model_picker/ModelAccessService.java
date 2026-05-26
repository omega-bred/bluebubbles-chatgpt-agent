package io.breland.bbagent.server.agent.model_picker;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelOption;
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
        .modelSelectionAllowed(access.modelSelectionAllowed())
        .modelSelectionConfigurable(access.modelSelectionAllowed())
        .readOnlyReason(access.premium() ? null : "Free accounts use the included model.")
        .availableModels(access.availableModels().stream().map(this::toWebsiteOption).toList());
  }

  public ModelSelectionResult selectModel(IncomingMessage message, String modelKey) {
    if (accountResolver == null) {
      return new ModelSelectionResult(false, standard(null), "Account resolution is unavailable.");
    }
    return accountResolver
        .resolveOrCreate(message)
        .map(resolved -> selectModel(resolved.account().getAccountId(), modelKey))
        .orElseGet(
            () ->
                new ModelSelectionResult(
                    false, standard(null), "I could not resolve the current chat account."));
  }

  public ModelSelectionResult selectModel(String accountId, String modelKey) {
    String cleanAccountId = StringUtils.defaultIfBlank(accountId, null);
    String cleanModelKey = normalizeModelKey(modelKey);
    if (cleanAccountId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing account id");
    }
    if (!isSelectableModel(cleanModelKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown or unavailable model");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(cleanAccountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!account.isPremium()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Model switching is only available to premium accounts");
    }
    account.setSelectedModel(cleanModelKey);
    account.setUpdatedAt(Instant.now());
    AgentAccountEntity saved = accountRepository.save(account);
    ModelAccess access = fromEntity(saved);
    return new ModelSelectionResult(
        true, access, "Model changed to " + access.currentModelLabel() + " for this account.");
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
      String modelKey = isSelectableModel(selected) ? selected : PREMIUM_MODEL_KEY;
      return new ModelAccess(
          entity.getAccountId(),
          true,
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
        STANDARD_MODEL_KEY,
        STANDARD_MODEL_LABEL,
        standardResponsesModel,
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

  public record ModelAccess(
      String accountId,
      boolean premium,
      String currentModelKey,
      String currentModelLabel,
      String responsesModel,
      boolean modelSelectionAllowed,
      List<ModelOption> availableModels) {
    public String provider() {
      return providerFor(currentModelKey);
    }

    public boolean supportsImageGeneration() {
      return PREMIUM_MODEL_KEY.equals(currentModelKey);
    }

    public boolean supportsWebSearch() {
      return PREMIUM_MODEL_KEY.equals(currentModelKey);
    }
  }

  public record ModelOption(String modelKey, String label, String provider, boolean enabled) {}

  public record ModelSelectionResult(boolean changed, ModelAccess modelAccess, String message) {}
}
