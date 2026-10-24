package io.breland.bbagent.server.agent.cadence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import io.breland.bbagent.server.agent.decisions.MessageDecisionService;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.terms.TermsGate;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CadenceIncomingMessageHandlerTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Loved a message", "Reacted 👍 to “Want me to make a second version?”",
        "Liked it, can you make another?", "Thanks, what time is it?"
      })
  void reactionsAndQuestionsReachTheModelForContextualResponseDecisions(String text) {
    Fixture fixture = fixture();
    IncomingMessage message = message("reaction", text, false, false);
    when(fixture.profileService().getAssistantResponsiveness(message.chatGuid()))
        .thenReturn(AssistantResponsiveness.DEFAULT);
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message))
        .thenReturn(new ConversationState());
    fixture.handler().handleIncomingMessage(message);
    fixture.handler().handleIncomingMessage(message);
    verify(fixture.workflowLauncher()).startWorkflow(any());
    verify(fixture.decisions()).assess(any(), any(), any());
  }

  @Test
  void silentModeStillDoesNotInvokeOnATapback() {
    Fixture fixture = fixture();
    IncomingMessage message = message("reaction", "Liked “Chat, proceed?”", false, false);
    when(fixture.profileService().getAssistantResponsiveness(message.chatGuid()))
        .thenReturn(AssistantResponsiveness.SILENT);
    fixture.handler().handleIncomingMessage(message);
    verifyNoInteractions(fixture.workflowLauncher());
    verifyNoInteractions(fixture.decisions());
  }

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void blockedAccountIsNotJournaledRegardlessOfAgreementRequirement(boolean termsRequired) {
    Fixture fixture = fixture(termsRequired);
    IncomingMessage message = message("blocked-message", "hello", false, false);
    when(fixture.profileService().isProcessingBlocked(message)).thenReturn(true);

    fixture.handler().handleIncomingMessage(message);

    verify(fixture.journalService(), never()).recordEligibleMessage(any());
    verifyNoInteractions(fixture.workflowLauncher());
    verifyNoInteractions(fixture.decisions());
  }

  @Test
  void disabledAgreementRequirementProcessesCurrentMessageWithoutRecordingConsent() {
    Fixture fixture = fixture(false);
    IncomingMessage message = message("without-agreement", "What can you do?", false, false);
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message))
        .thenReturn(new ConversationState());

    fixture.handler().handleIncomingMessage(message);

    verify(fixture.profileService()).recordMessageIdentities(message);
    verify(fixture.profileService()).isProcessingBlocked(message);
    verify(fixture.profileService(), never()).acceptTerms(any());
    verify(fixture.profileService()).resolveOrCreateAccount(message);
    verify(fixture.journalService()).recordEligibleMessage(message);
    verify(fixture.decisions()).assess(any(), any(), any());
    verify(fixture.workflowLauncher()).startWorkflow(any());
  }

  @Test
  void suppressedMessageIsJournaledAndRememberedWithoutReplacingActiveWorkflow() {
    Fixture fixture = fixture();
    IncomingMessage message = message("chatter", "thanks", false, false);
    ConversationState state = new ConversationState();
    state.setLatestWorkflowMessageGuid("active-request");
    state.setLatestWorkflowRunId("active-run");
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message)).thenReturn(state);
    when(fixture.decisions().assess(any(), any(), any()))
        .thenReturn(new MessageDecisionService.Assessment(true, DecisionRouting.EMPTY));

    fixture.handler().handleIncomingMessage(message);
    fixture.handler().handleIncomingMessage(message);

    verifyNoInteractions(fixture.workflowLauncher());
    verify(fixture.decisions()).assess(any(), any(), any());
    org.assertj.core.api.Assertions.assertThat(state.getLatestWorkflowMessageGuid())
        .isEqualTo("active-request");
    org.assertj.core.api.Assertions.assertThat(state.getLatestWorkflowRunId())
        .isEqualTo("active-run");
    org.assertj.core.api.Assertions.assertThat(state.history()).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(state.history().getFirst().content())
        .contains("thanks");
    verify(fixture.journalService(), Mockito.atLeastOnce()).recordEligibleMessage(message);
  }

  @Test
  void routingHintsTravelWithTheWorkflow() {
    Fixture fixture = fixture();
    IncomingMessage message = message("quota", "Chat, what is my quota?", false, false);
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message))
        .thenReturn(new ConversationState());
    DecisionRouting routing = new DecisionRouting(List.of("get_usage_limits"), false);
    when(fixture.decisions().assess(any(), any(), any()))
        .thenReturn(new MessageDecisionService.Assessment(false, routing));
    fixture.handler().handleIncomingMessage(message);
    var request =
        org.mockito.ArgumentCaptor.forClass(
            io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest.class);
    verify(fixture.workflowLauncher()).startWorkflow(request.capture());
    org.assertj.core.api.Assertions.assertThat(
            request.getValue().workflowContext().decisionRouting())
        .isEqualTo(routing);
  }

  @Test
  void emojiResponseUsesReactionWorkflowWithoutReplacingActiveTask() {
    Fixture fixture = fixture();
    IncomingMessage message = message("celebration", "I passed!", false, false);
    ConversationState state = new ConversationState();
    state.setLatestWorkflowMessageGuid("active-request");
    state.setLatestWorkflowRunId("active-run");
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message)).thenReturn(state);
    var routing =
        new DecisionRouting(List.of(), false, DecisionRouting.ResponseMode.REACTION, "love");
    when(fixture.decisions().assess(any(), any(), any()))
        .thenReturn(new MessageDecisionService.Assessment(false, routing));
    fixture.handler().handleIncomingMessage(message);
    var request =
        org.mockito.ArgumentCaptor.forClass(
            io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest.class);
    verify(fixture.workflowLauncher()).startWorkflow(request.capture());
    org.assertj.core.api.Assertions.assertThat(
            request.getValue().workflowContext().isReactionWorkflow())
        .isTrue();
    org.assertj.core.api.Assertions.assertThat(state.getLatestWorkflowMessageGuid())
        .isEqualTo("active-request");
    org.assertj.core.api.Assertions.assertThat(state.getLatestWorkflowRunId())
        .isEqualTo("active-run");
    org.assertj.core.api.Assertions.assertThat(state.getLatestReactionMessageGuid())
        .isEqualTo("celebration");
  }

  @Test
  void classificationFailureFallsBackToNormalAgent() {
    Fixture fixture = fixture();
    IncomingMessage message = message("request", "Help me", false, false);
    MessageTransport transport = Mockito.mock(MessageTransport.class);
    when(fixture.transportRegistry().resolve(message)).thenReturn(transport);
    when(transport.hydrateConversationState(message.chatGuid(), message))
        .thenReturn(new ConversationState());
    when(fixture.decisions().assess(any(), any(), any()))
        .thenThrow(new IllegalStateException("unavailable"));
    fixture.handler().handleIncomingMessage(message);
    verify(fixture.workflowLauncher()).startWorkflow(any());
  }

  @Test
  void transportEventsRejectedBeforeAssistantInvocationAreNotJournaled() {
    Fixture fixture = fixture();

    fixture.handler().handleIncomingMessage(message("self", "hello", true, false));
    fixture.handler().handleIncomingMessage(message("system", "joined", false, true));
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
    return fixture(true);
  }

  private Fixture fixture(boolean termsRequired) {
    AgentProfileService profileService = Mockito.mock(AgentProfileService.class);
    MessageTransportRegistry transportRegistry = Mockito.mock(MessageTransportRegistry.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    CadenceWorkflowLauncher workflowLauncher = Mockito.mock(CadenceWorkflowLauncher.class);
    ConversationJournalService journalService = Mockito.mock(ConversationJournalService.class);
    MessageDecisionService decisions = Mockito.mock(MessageDecisionService.class);
    OpenAiClientProvider openAiClientProvider = Mockito.mock(OpenAiClientProvider.class);
    TermsAgreementValidator termsAgreementValidator =
        new TermsAgreementValidator(
            openAiClientProvider,
            new ObjectMapper(),
            TermsAgreementValidator.DEFAULT_RESPONSES_MODEL,
            io.breland.bbagent.server.agent.decisions.DecisionFixtures.disabledBackground());
    TermsGate termsGate =
        new TermsGate(
            Mockito.mock(AgentOutboundService.class),
            profileService,
            termsAgreementValidator,
            "https://example.com",
            termsRequired);
    CadenceIncomingMessageHandler handler =
        new CadenceIncomingMessageHandler(
            new ConversationStateStore(),
            profileService,
            transportRegistry,
            workflowLauncher,
            Mockito.mock(io.breland.bbagent.server.metrics.AgentMetricsService.class),
            termsGate,
            new PollNotificationEnricher(bbHttpClientWrapper),
            journalService,
            decisions);
    return new Fixture(
        handler, profileService, transportRegistry, workflowLauncher, journalService, decisions);
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
      ConversationJournalService journalService,
      MessageDecisionService decisions) {}
}
