package io.breland.bbagent.server.agent.decisions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import org.mockito.Mockito;

public final class DecisionFixtures {
  private DecisionFixtures() {}

  public static BackgroundDecisionService disabledBackground() {
    DecisionsProperties properties = new DecisionsProperties();
    properties.setEnabled(false);
    return new BackgroundDecisionService(
        Mockito.mock(DecisionsClient.class),
        properties,
        new ObjectMapper(),
        new OperationalMetricsService(null));
  }

  public static AgentToolRegistry toolRegistry(String transportId) {
    var transports = Mockito.mock(MessageTransportRegistry.class);
    var transport = Mockito.mock(MessageTransport.class);
    Mockito.when(transports.resolve(Mockito.any())).thenReturn(transport);
    Mockito.when(transport.id()).thenReturn(transportId);
    Mockito.when(transport.supportsReactions()).thenReturn("bluebubbles".equals(transportId));
    return new AgentToolRegistry(
        Mockito.mock(BBHttpClientWrapper.class),
        Mockito.mock(HindsightClient.class),
        Mockito.mock(GcalClient.class),
        Mockito.mock(WebsiteAccountService.class),
        Mockito.mock(GiphyClient.class),
        transports,
        new ObjectMapper(),
        Mockito.mock(OpenAiClientProvider.class),
        Mockito.mock(FeedbackService.class),
        Mockito.mock(MessageResponseRateLimitService.class),
        Mockito.mock(CadenceWorkflowLauncher.class),
        Mockito.mock(AgentProfileService.class),
        new OperationalMetricsService(null),
        Mockito.mock(ModelAccessService.class),
        Mockito.mock(ConversationMemorySettingsService.class),
        Mockito.mock(MemoryScopeResolver.class),
        null,
        null);
  }
}
