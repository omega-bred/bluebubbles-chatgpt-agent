package io.breland.bbagent.server.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.llm.OpenAiResponsesLlmProvider;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.model_picker.ModelPickerTestSupport;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import io.breland.bbagent.server.website.WebsiteAccountService;

final class BBMessageAgentFixture {
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private OpenAIClient openAiClient = mock(OpenAIClient.class);
  private AgentProfileService profileService = mock(AgentProfileService.class);
  private MessageTransportRegistry transportRegistry;
  private CadenceWorkflowLauncher cadenceWorkflowLauncher = mock(CadenceWorkflowLauncher.class);
  private AgentMetricsService agentMetricsService = mock(AgentMetricsService.class);
  private MessageResponseRateLimitService messageResponseRateLimitService =
      defaultRateLimitService();
  private OperationalMetricsService operationalMetricsService =
      mock(OperationalMetricsService.class);
  private ModelPicker modelPicker = ModelPickerTestSupport.standard();

  private BBMessageAgentFixture(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.transportRegistry = MessageTransportRegistry.blueBubblesOnly(bbHttpClientWrapper);
  }

  static BBMessageAgentFixture with(BBHttpClientWrapper bbHttpClientWrapper) {
    return new BBMessageAgentFixture(bbHttpClientWrapper);
  }

  BBMessageAgentFixture openAiClient(OpenAIClient openAiClient) {
    this.openAiClient = openAiClient;
    return this;
  }

  BBMessageAgentFixture profileService(AgentProfileService profileService) {
    this.profileService = profileService;
    return this;
  }

  BBMessageAgentFixture transportRegistry(MessageTransportRegistry transportRegistry) {
    this.transportRegistry = transportRegistry;
    return this;
  }

  BBMessageAgentFixture cadenceWorkflowLauncher(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    return this;
  }

  BBMessageAgentFixture agentMetricsService(AgentMetricsService agentMetricsService) {
    this.agentMetricsService = agentMetricsService;
    return this;
  }

  BBMessageAgentFixture messageResponseRateLimitService(
      MessageResponseRateLimitService messageResponseRateLimitService) {
    this.messageResponseRateLimitService = messageResponseRateLimitService;
    return this;
  }

  BBMessageAgentFixture operationalMetricsService(
      OperationalMetricsService operationalMetricsService) {
    this.operationalMetricsService = operationalMetricsService;
    return this;
  }

  BBMessageAgentFixture modelPicker(ModelPicker modelPicker) {
    this.modelPicker = modelPicker;
    return this;
  }

  BBMessageAgent build() {
    ObjectMapper objectMapper = new ObjectMapper();
    OpenAiClientProvider openAiClientProvider = mock(OpenAiClientProvider.class);
    when(openAiClientProvider.get()).thenReturn(openAiClient);
    WebsiteAccountService websiteAccountService = mock(WebsiteAccountService.class);
    ModelAccessService modelAccessService = mock(ModelAccessService.class);
    AgentToolRegistry toolRegistry =
        new AgentToolRegistry(
            bbHttpClientWrapper,
            mock(Mem0Client.class),
            mock(GcalClient.class),
            websiteAccountService,
            mock(GiphyClient.class),
            transportRegistry,
            objectMapper,
            openAiClientProvider,
            mock(FeedbackService.class),
            messageResponseRateLimitService,
            cadenceWorkflowLauncher,
            profileService,
            operationalMetricsService,
            modelAccessService,
            mock(ConversationMemorySettingsService.class),
            mock(MemoryScopeResolver.class));
    AgentResponseCreator responseCreator =
        new AgentResponseCreator(
            modelPicker,
            toolRegistry,
            new OpenAiResponsesLlmProvider(openAiClientProvider, modelPicker),
            operationalMetricsService,
            profileService);
    TermsAgreementValidator termsAgreementValidator =
        new TermsAgreementValidator(
            openAiClientProvider, objectMapper, TermsAgreementValidator.DEFAULT_RESPONSES_MODEL);
    NativeAppSessionService nativeAppSessionService = mock(NativeAppSessionService.class);
    when(nativeAppSessionService.claimStartToken(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return new BBMessageAgent(
        bbHttpClientWrapper,
        profileService,
        new AgentAttachmentInputBuilder(bbHttpClientWrapper),
        transportRegistry,
        objectMapper,
        cadenceWorkflowLauncher,
        agentMetricsService,
        messageResponseRateLimitService,
        nativeAppSessionService,
        mock(ConversationJournalService.class),
        toolRegistry,
        responseCreator,
        termsAgreementValidator,
        new MessageResponseLimitNoticeFactory(websiteAccountService),
        "http://localhost:8080");
  }

  private static MessageResponseRateLimitService defaultRateLimitService() {
    MessageResponseRateLimitService service = mock(MessageResponseRateLimitService.class);
    lenient().when(service.tryConsume(any())).thenReturn(new RateLimitDecision(null, true, 1L));
    return service;
  }
}
