package io.breland.bbagent.server.agent.cadence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CadenceIncomingMessageHandlerTest {
  @Test
  void silentGroupMessageIsJournaledWithoutLaunchingAssistantWorkflow() {
    Fixture fixture = fixture();
    IncomingMessage message = message("silent-message", "group update", false, false);
    when(fixture.profileService().getAssistantResponsiveness(message.chatGuid()))
        .thenReturn(AssistantResponsiveness.SILENT);

    fixture.handler().handleIncomingMessage(message);

    verify(fixture.journalService()).recordEligibleMessage(message);
    verifyNoInteractions(fixture.workflowLauncher());
  }

  @Test
  void blockedAccountIsNotJournaled() {
    Fixture fixture = fixture();
    IncomingMessage message = message("blocked-message", "hello", false, false);
    when(fixture.profileService().isProcessingBlocked(message)).thenReturn(true);

    fixture.handler().handleIncomingMessage(message);

    verify(fixture.journalService(), never()).recordEligibleMessage(any());
    verifyNoInteractions(fixture.workflowLauncher());
  }

  @Test
  void transportEventsRejectedBeforeAssistantInvocationAreNotJournaled() {
    Fixture fixture = fixture();

    fixture.handler().handleIncomingMessage(message("self", "hello", true, false));
    fixture.handler().handleIncomingMessage(message("system", "joined", false, true));
    fixture.handler().handleIncomingMessage(message("reaction", "Loved a message", false, false));
    fixture.handler().handleIncomingMessage(message("blank", " ", false, false));

    verify(fixture.journalService(), never()).recordEligibleMessage(any());
    verifyNoInteractions(fixture.workflowLauncher());
  }

  @Test
  void journalFailureDoesNotPreventInteractiveWorkflow() {
    Fixture fixture = fixture();
    IncomingMessage message = message("workflow-message", "Chat please help", false, false);
    when(fixture.profileService().getAssistantResponsiveness(message.chatGuid()))
        .thenReturn(AssistantResponsiveness.DEFAULT);
    Mockito.doThrow(new IllegalStateException("database unavailable"))
        .when(fixture.journalService())
        .recordEligibleMessage(message);
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.displayName()).thenReturn("test");
    when(transport.hydrateConversationState(message.chatGuid(), message))
        .thenReturn(new ConversationState());

    fixture.handler().handleIncomingMessage(message);

    verify(fixture.workflowLauncher()).startWorkflow(any());
  }

  private Fixture fixture() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    AgentProfileService profileService = Mockito.mock(AgentProfileService.class);
    MessageTransportRegistry transportRegistry = Mockito.mock(MessageTransportRegistry.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    CadenceWorkflowLauncher workflowLauncher = Mockito.mock(CadenceWorkflowLauncher.class);
    ConversationJournalService journalService = Mockito.mock(ConversationJournalService.class);
    OpenAiClientProvider openAiClientProvider = Mockito.mock(OpenAiClientProvider.class);
    TermsAgreementValidator termsAgreementValidator =
        new TermsAgreementValidator(
            openAiClientProvider,
            new ObjectMapper(),
            TermsAgreementValidator.DEFAULT_RESPONSES_MODEL);
    CadenceIncomingMessageHandler handler =
        new CadenceIncomingMessageHandler(
            messageAgent,
            new ConcurrentHashMap<>(),
            profileService,
            transportRegistry,
            bbHttpClientWrapper,
            workflowLauncher,
            Mockito.mock(io.breland.bbagent.server.metrics.AgentMetricsService.class),
            () -> "https://example.com/terms",
            termsAgreementValidator,
            journalService);
    return new Fixture(
        handler, profileService, transportRegistry, workflowLauncher, journalService);
  }

  private IncomingMessage message(
      String messageGuid, String text, boolean fromMe, boolean systemMessage) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;cadence-memory",
        messageGuid,
        null,
        text,
        fromMe,
        BBMessageAgent.IMESSAGE_SERVICE,
        "member@example.com",
        true,
        Instant.parse("2026-08-08T18:00:00Z"),
        List.of(),
        systemMessage);
  }

  private record Fixture(
      CadenceIncomingMessageHandler handler,
      AgentProfileService profileService,
      MessageTransportRegistry transportRegistry,
      CadenceWorkflowLauncher workflowLauncher,
      ConversationJournalService journalService) {}
}
