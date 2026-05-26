package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.assistant.AssistantResponsivenessAgentTool;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.ReadPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SearchConvoHistoryAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SetGroupIconAgentTool;
import io.breland.bbagent.server.agent.tools.feedback.FeedbackAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.CreateEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.DeleteEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.gcal.GetCurrentTimeAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GetEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GetFreebusyAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListCalendarsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListColorsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListEventsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ManageAccountsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.RespondToEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.SearchEventsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.UpdateEventAgentTool;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesPodLogsAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesReadOnlyAgentTool;
import io.breland.bbagent.server.agent.tools.limits.GetUsageLimitsAgentTool;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.tools.memory.MemoryDeleteAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemoryGetAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemorySaveAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemoryUpdateAgentTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventDeleteTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventListTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.tools.website.GetWebsiteAccountLinkStatusAgentTool;
import io.breland.bbagent.server.agent.tools.website.LinkWebsiteAccountAgentTool;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

public final class AgentToolRegistry {
  private static final Set<String> GROUP_ONLY_TOOLS =
      Set.of(RenameConversationAgentTool.TOOL_NAME, SetGroupIconAgentTool.TOOL_NAME);
  private static final Set<String> BLUEBUBBLES_ONLY_TOOLS =
      Set.of(
          SearchConvoHistoryAgentTool.TOOL_NAME,
          CurrentConversationInfoAgentTool.TOOL_NAME,
          RenameConversationAgentTool.TOOL_NAME,
          SetGroupIconAgentTool.TOOL_NAME,
          SendGiphyAgentTool.TOOL_NAME,
          GetThreadContextAgentTool.TOOL_NAME,
          SendPollAgentTool.TOOL_NAME,
          ReadPollAgentTool.TOOL_NAME);
  private static final Set<String> BLUEBUBBLES_TOOL_NAMES =
      Set.of(
          SendTextAgentTool.TOOL_NAME,
          SendReactionAgentTool.TOOL_NAME,
          SearchConvoHistoryAgentTool.TOOL_NAME,
          CurrentConversationInfoAgentTool.TOOL_NAME,
          RenameConversationAgentTool.TOOL_NAME,
          SetGroupIconAgentTool.TOOL_NAME,
          SendGiphyAgentTool.TOOL_NAME,
          GetThreadContextAgentTool.TOOL_NAME,
          SendPollAgentTool.TOOL_NAME,
          ReadPollAgentTool.TOOL_NAME);
  private static final Set<String> GCAL_TOOL_NAMES =
      Set.of(
          ListCalendarsAgentTool.TOOL_NAME,
          ListEventsAgentTool.TOOL_NAME,
          SearchEventsAgentTool.TOOL_NAME,
          GetEventAgentTool.TOOL_NAME,
          CreateEventAgentTool.TOOL_NAME,
          UpdateEventAgentTool.TOOL_NAME,
          DeleteEventAgentTool.TOOL_NAME,
          RespondToEventAgentTool.TOOL_NAME,
          GetFreebusyAgentTool.TOOL_NAME,
          ManageAccountsAgentTool.TOOL_NAME,
          ListColorsAgentTool.TOOL_NAME,
          GetCurrentTimeAgentTool.TOOL_NAME);
  private static final Set<String> WEBSITE_TOOL_NAMES =
      Set.of(LinkWebsiteAccountAgentTool.TOOL_NAME, GetWebsiteAccountLinkStatusAgentTool.TOOL_NAME);
  private static final Set<String> ASSISTANT_TOOL_NAMES =
      Set.of(AssistantResponsivenessAgentTool.TOOL_NAME, AssistantNameAgentTool.TOOL_NAME);
  private static final Set<String> SCHEDULED_TOOL_NAMES =
      Set.of(
          ScheduledEventTool.TOOL_NAME,
          ScheduledEventListTool.TOOL_NAME,
          ScheduledEventDeleteTool.TOOL_NAME);
  private static final Set<String> KUBERNETES_TOOL_NAMES =
      Set.of(KubernetesReadOnlyAgentTool.TOOL_NAME, KubernetesPodLogsAgentTool.TOOL_NAME);
  private static final String KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID =
      "9f80c2a0-de6f-4c56-8027-29b1673bb0d5";

  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
  private final MessageTransportRegistry transportRegistry;
  private final Function<IncomingMessage, Optional<String>> accountIdResolver;

  public AgentToolRegistry(
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      @Nullable WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      MessageTransportRegistry transportRegistry,
      ObjectMapper objectMapper,
      Supplier<OpenAIClient> openAiSupplier,
      @Nullable FeedbackService feedbackService,
      @Nullable MessageResponseRateLimitService messageResponseRateLimitService,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      Function<IncomingMessage, Optional<String>> accountIdResolver) {
    this(
        bbHttpClientWrapper,
        mem0Client,
        gcalClient,
        websiteAccountService,
        giphyClient,
        transportRegistry,
        objectMapper,
        openAiSupplier,
        feedbackService,
        messageResponseRateLimitService,
        cadenceWorkflowLauncher,
        accountIdResolver,
        null);
  }

  public AgentToolRegistry(
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      @Nullable WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      MessageTransportRegistry transportRegistry,
      ObjectMapper objectMapper,
      Supplier<OpenAIClient> openAiSupplier,
      @Nullable FeedbackService feedbackService,
      @Nullable MessageResponseRateLimitService messageResponseRateLimitService,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      Function<IncomingMessage, Optional<String>> accountIdResolver,
      @Nullable OperationalMetricsService operationalMetricsService) {
    this.transportRegistry = transportRegistry;
    this.accountIdResolver = accountIdResolver;
    registerBuiltInTools(
        bbHttpClientWrapper,
        mem0Client,
        gcalClient,
        websiteAccountService,
        giphyClient,
        objectMapper,
        openAiSupplier,
        feedbackService,
        messageResponseRateLimitService,
        cadenceWorkflowLauncher,
        operationalMetricsService);
  }

  public List<AgentTool> availableTools(IncomingMessage message) {
    String accountId = resolveAccountId(message);
    return tools.values().stream()
        .filter(tool -> shouldIncludeTool(tool, message, accountId))
        .toList();
  }

  public ResolvedTool resolveTool(String toolName, IncomingMessage message) {
    AgentTool tool = tools.get(toolName);
    if (tool != null) {
      if (KUBERNETES_TOOL_NAMES.contains(toolName)
          && !isKubernetesToolAllowed(message, resolveAccountId(message))) {
        return new ResolvedTool(null);
      }
      return new ResolvedTool(tool);
    }
    return new ResolvedTool(null);
  }

  public String toolCategory(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return "other";
    }
    if (BLUEBUBBLES_TOOL_NAMES.contains(toolName)) {
      return "bluebubbles";
    }
    if (GCAL_TOOL_NAMES.contains(toolName)) {
      return "google_calendar";
    }
    if (WEBSITE_TOOL_NAMES.contains(toolName)) {
      return "website";
    }
    if (ASSISTANT_TOOL_NAMES.contains(toolName)) {
      return "assistant";
    }
    if (SCHEDULED_TOOL_NAMES.contains(toolName)) {
      return "scheduled";
    }
    if (KUBERNETES_TOOL_NAMES.contains(toolName)) {
      return "kubernetes";
    }
    if (toolName.startsWith("memory_")) {
      return "memory";
    }
    if (FeedbackAgentTool.TOOL_NAME.equals(toolName)) {
      return "feedback";
    }
    if (GetUsageLimitsAgentTool.TOOL_NAME.equals(toolName)) {
      return "limits";
    }
    return "other";
  }

  private boolean shouldIncludeTool(
      AgentTool tool, IncomingMessage message, @Nullable String accountId) {
    MessageTransport transport = transportRegistry.resolve(message);
    if (SendReactionAgentTool.TOOL_NAME.equals(tool.name())) {
      return transport.supportsReactions();
    }
    if (BLUEBUBBLES_ONLY_TOOLS.contains(tool.name())
        && !IncomingMessage.TRANSPORT_BLUEBUBBLES.equals(transport.id())) {
      return false;
    }
    if (GROUP_ONLY_TOOLS.contains(tool.name())) {
      return message != null && message.isGroup();
    }
    if (KUBERNETES_TOOL_NAMES.contains(tool.name())) {
      return isKubernetesToolAllowed(message, accountId);
    }
    return true;
  }

  private boolean isKubernetesToolAllowed(IncomingMessage message, @Nullable String accountId) {
    return message != null
        && !message.isGroup()
        && KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID.equals(accountId);
  }

  private String resolveAccountId(IncomingMessage message) {
    Optional<String> accountId = accountIdResolver.apply(message);
    return accountId == null ? null : accountId.orElse(null);
  }

  private void registerBuiltInTools(
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      @Nullable WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      ObjectMapper objectMapper,
      Supplier<OpenAIClient> openAiSupplier,
      @Nullable FeedbackService feedbackService,
      @Nullable MessageResponseRateLimitService messageResponseRateLimitService,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      @Nullable OperationalMetricsService operationalMetricsService) {
    registerTool(new SendTextAgentTool().getTool());
    registerTool(new SendReactionAgentTool().getTool());
    registerTool(new SendPollAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new ReadPollAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SearchConvoHistoryAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new CurrentConversationInfoAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new RenameConversationAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SetGroupIconAgentTool(bbHttpClientWrapper, openAiSupplier).getTool());
    registerTool(
        new SendGiphyAgentTool(
                bbHttpClientWrapper, giphyClient, openAiSupplier, operationalMetricsService)
            .getTool());
    registerTool(new AssistantResponsivenessAgentTool().getTool());
    registerTool(new AssistantNameAgentTool().getTool());
    registerTool(new MemorySaveAgentTool(mem0Client).getTool());
    registerTool(new MemoryGetAgentTool(mem0Client).getTool());
    registerTool(new MemoryUpdateAgentTool(mem0Client).getTool());
    registerTool(new MemoryDeleteAgentTool(mem0Client).getTool());
    if (feedbackService != null) {
      registerTool(new FeedbackAgentTool(feedbackService).getTool());
    }
    registerTool(new ListCalendarsAgentTool(gcalClient).getTool());
    registerTool(new ListEventsAgentTool(gcalClient).getTool());
    registerTool(new SearchEventsAgentTool(gcalClient).getTool());
    registerTool(new GetEventAgentTool(gcalClient).getTool());
    registerTool(new CreateEventAgentTool(gcalClient).getTool());
    registerTool(new UpdateEventAgentTool(gcalClient).getTool());
    registerTool(new DeleteEventAgentTool(gcalClient).getTool());
    registerTool(new RespondToEventAgentTool(gcalClient).getTool());
    registerTool(new GetFreebusyAgentTool(gcalClient).getTool());
    registerTool(new ManageAccountsAgentTool(gcalClient).getTool());
    registerTool(new ListColorsAgentTool(gcalClient).getTool());
    registerTool(new GetCurrentTimeAgentTool(gcalClient).getTool());
    if (websiteAccountService != null) {
      registerTool(new LinkWebsiteAccountAgentTool(websiteAccountService).getTool());
      registerTool(new GetWebsiteAccountLinkStatusAgentTool(websiteAccountService).getTool());
    }
    if (messageResponseRateLimitService != null) {
      registerTool(new GetUsageLimitsAgentTool(messageResponseRateLimitService).getTool());
    }
    registerTool(new KubernetesReadOnlyAgentTool(objectMapper).getTool());
    registerTool(new KubernetesPodLogsAgentTool(objectMapper).getTool());
    registerTool(new GetThreadContextAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new ScheduledEventTool(cadenceWorkflowLauncher).getTool());
    registerTool(new ScheduledEventListTool(cadenceWorkflowLauncher).getTool());
    registerTool(new ScheduledEventDeleteTool(cadenceWorkflowLauncher).getTool());
  }

  private void registerTool(AgentTool tool) {
    if (tool == null || StringUtils.isBlank(tool.name())) {
      return;
    }
    tools.put(tool.name(), tool);
  }

  public record ResolvedTool(@Nullable AgentTool tool) {}
}
