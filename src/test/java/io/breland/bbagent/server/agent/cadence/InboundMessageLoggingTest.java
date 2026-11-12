package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest.TypeEnum;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataHandle;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.decisions.MessageDecisionService;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.terms.TermsGate;
import io.breland.bbagent.server.agent.tools.gayodex.GayodexInboundActor;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.controllers.BluebubblesWebhookController;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class InboundMessageLoggingTest {
  private static final String CHAT = "synthetic-chat";
  private static final String GUID = "synthetic-message";
  private static final String PRIVATE_BODY = "synthetic-private-body";
  private static final String PRIVATE_SENDER = "synthetic.private.sender@example.invalid";
  private static final String PRIVATE_FILENAME = "synthetic-private-attachment.png";
  private static final String START_CODE = "BC-SYNTHETIC123";

  @ParameterizedTest
  @EnumSource(
      value = TypeEnum.class,
      names = {"NEW_MESSAGE", "UPDATED_MESSAGE"})
  void webhookForwardsMessagesWithoutLoggingTheirPayload(TypeEnum type, CapturedOutput output)
      throws Exception {
    var agent = mock(BBMessageAgent.class);
    var bb = mock(BBHttpClientWrapper.class);
    var actor = mock(GayodexInboundActor.class);
    when(actor.authenticate(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(bb.getConversationInfo(CHAT))
        .thenReturn(new Chat().participants(List.of(new ChatParticipant())));
    var controller = new BluebubblesWebhookController(null, agent, bb, actor);
    var data =
        new BlueBubblesMessageReceivedRequestData()
            .originalROWID(1)
            .guid(GUID)
            .text(PRIVATE_BODY + " " + START_CODE)
            .isFromMe(false)
            .dateCreated(1_700_000_000_000L)
            .handle(
                new BlueBubblesMessageReceivedRequestDataHandle(PRIVATE_SENDER)
                    .service(BBMessageAgent.IMESSAGE_SERVICE))
            .chats(List.of(new BlueBubblesMessageReceivedRequestDataChatsInner().guid(CHAT)))
            .attachments(
                List.of(
                    new BlueBubblesMessageReceivedRequestDataAttachmentsInner()
                        .guid("synthetic-attachment")
                        .mimeType("image/png")
                        .transferName(PRIVATE_FILENAME)));

    int start = output.getAll().length();
    try (var capture = new LogCapture(BluebubblesWebhookController.class.getName())) {
      capture.checkControlEvent(output);
      var response =
          controller.bluebubblesMessageReceived(new BlueBubblesMessageReceivedRequest(type, data));

      assertThat(response.getStatusCode().value()).isEqualTo(200);
      var forwarded = ArgumentCaptor.forClass(IncomingMessage.class);
      verify(agent).handleIncomingMessage(forwarded.capture());
      assertThat(forwarded.getValue().text()).isEqualTo(PRIVATE_BODY + " " + START_CODE);
      assertThat(forwarded.getValue().sender()).isEqualTo(PRIVATE_SENDER);
      assertThat(forwarded.getValue().attachments())
          .singleElement()
          .satisfies(attachment -> assertThat(attachment.filename()).isEqualTo(PRIVATE_FILENAME));
      assertNoPayload(output.getAll().substring(start));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void handlerKeepsProcessedAndDroppedPayloadsOutOfLogs(boolean fromMe, CapturedOutput output)
      throws Exception {
    var message = message(fromMe);
    var fixture = fixture(message);
    int start = output.getAll().length();
    try (var capture = new LogCapture(CadenceIncomingMessageHandler.class.getName())) {
      capture.checkControlEvent(output);
      fixture.handler().handleIncomingMessage(message);

      if (fromMe) {
        verifyNoInteractions(
            fixture.profile(), fixture.journal(), fixture.metrics(), fixture.launcher());
      } else {
        verify(fixture.profile()).recordMessageIdentities(message);
        verify(fixture.journal()).recordEligibleMessage(message);
        verify(fixture.metrics()).recordAcceptedMessage(message);
        verify(fixture.launcher()).startWorkflow(any());
      }
      assertNoPayload(output.getAll().substring(start));
    }
  }

  @Test
  void metricFailureRetainsItsCategoryAndWorkflowWithoutLoggingPrivateData(CapturedOutput output)
      throws Exception {
    var message = message(false);
    var fixture = fixture(message);
    var failure =
        new IllegalStateException(
            PRIVATE_BODY + " " + START_CODE, new IllegalArgumentException(PRIVATE_SENDER));
    failure.addSuppressed(new RuntimeException(PRIVATE_FILENAME));
    doThrow(failure).when(fixture.metrics()).recordAcceptedMessage(message);

    int start = output.getAll().length();
    try (var capture = new LogCapture(CadenceIncomingMessageHandler.class.getName())) {
      capture.checkControlEvent(output);
      fixture.handler().handleIncomingMessage(message);

      verify(fixture.launcher()).startWorkflow(any());
      String logged = output.getAll().substring(start);
      assertNoPayload(logged);
      assertThat(logged)
          .contains("WARN Failed to record message metric failure_type=IllegalStateException")
          .doesNotContain("Caused by:", "Suppressed:", "at io.breland.");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void captureRestoresInheritedAndNamedLoggerConfigurations(boolean named, CapturedOutput output)
      throws Exception {
    Object context =
        ReflectionTestUtils.invokeMethod(
            Class.forName("org.apache.logging.log4j.LogManager"), "getContext", false);
    Object configuration = ReflectionTestUtils.invokeMethod(context, "getConfiguration");
    String name = InboundMessageLoggingTest.class.getName() + ".restoration." + named;
    Map<String, ?> original = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
    Object originalNamed = original.get(name);
    ReflectionTestUtils.invokeMethod(configuration, "removeLogger", name);
    if (named) {
      ReflectionTestUtils.invokeMethod(
          configuration, "addLogger", name, LogCapture.loggerConfig(name, "ERROR"));
    }
    ReflectionTestUtils.invokeMethod(context, "updateLoggers");
    try {
      Map<String, ?> beforeLoggers = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
      beforeLoggers = Map.copyOf(beforeLoggers);
      Map<String, ?> beforeAppenders =
          ReflectionTestUtils.invokeMethod(configuration, "getAppenders");
      beforeAppenders = Map.copyOf(beforeAppenders);
      Object beforeEffective =
          ReflectionTestUtils.invokeMethod(configuration, "getLoggerConfig", name);
      Object logger = ReflectionTestUtils.invokeMethod(context, "getLogger", name);
      Object beforeLevel = ReflectionTestUtils.invokeMethod(logger, "getLevel");
      var capture = new LogCapture(name);
      try (capture) {
        capture.checkControlEvent(output);
      }
      Map<String, ?> afterLoggers = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
      Map<String, ?> afterAppenders =
          ReflectionTestUtils.invokeMethod(configuration, "getAppenders");
      Object afterEffective =
          ReflectionTestUtils.invokeMethod(configuration, "getLoggerConfig", name);
      Object afterLevel = ReflectionTestUtils.invokeMethod(logger, "getLevel");
      assertThat(afterLoggers).isEqualTo(beforeLoggers);
      assertThat(afterAppenders).isEqualTo(beforeAppenders);
      assertThat(afterEffective).isSameAs(beforeEffective);
      assertThat(afterLevel).isEqualTo(beforeLevel);
      assertThat((Boolean) ReflectionTestUtils.invokeMethod(capture.appender, "isStopped"))
          .isTrue();
    } finally {
      ReflectionTestUtils.invokeMethod(configuration, "removeLogger", name);
      if (originalNamed != null) {
        ReflectionTestUtils.invokeMethod(configuration, "addLogger", name, originalNamed);
      }
      ReflectionTestUtils.invokeMethod(context, "updateLoggers");
    }
  }

  private static IncomingMessage message(boolean fromMe) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        CHAT,
        GUID,
        null,
        PRIVATE_BODY + " " + START_CODE,
        fromMe,
        BBMessageAgent.IMESSAGE_SERVICE,
        PRIVATE_SENDER,
        true,
        Instant.ofEpochMilli(1_700_000_000_000L),
        List.of(
            new IncomingAttachment(
                "synthetic-attachment", "image/png", PRIVATE_FILENAME, null, null, null)),
        false);
  }

  private static Fixture fixture(IncomingMessage message) {
    var states = mock(ConversationStateStore.class);
    when(states.computeIfAbsent(eq(CHAT), any())).thenReturn(new ConversationState());
    var profile = mock(AgentProfileService.class);
    when(profile.getAssistantResponsiveness(CHAT)).thenReturn(AssistantResponsiveness.DEFAULT);
    var enricher = mock(PollNotificationEnricher.class);
    when(enricher.enrich(message)).thenReturn(message);
    var launcher = mock(CadenceWorkflowLauncher.class);
    var metrics = mock(AgentMetricsService.class);
    var journal = mock(ConversationJournalService.class);
    var handler =
        new CadenceIncomingMessageHandler(
            states,
            profile,
            mock(MessageTransportRegistry.class),
            launcher,
            metrics,
            mock(TermsGate.class),
            enricher,
            journal,
            mock(MessageDecisionService.class));
    return new Fixture(handler, profile, journal, metrics, launcher);
  }

  private static void assertNoPayload(String logged) {
    assertThat(logged).doesNotContain(PRIVATE_BODY, PRIVATE_SENDER, PRIVATE_FILENAME, START_CODE);
  }

  private record Fixture(
      CadenceIncomingMessageHandler handler,
      AgentProfileService profile,
      ConversationJournalService journal,
      AgentMetricsService metrics,
      CadenceWorkflowLauncher launcher) {}

  private static final class LogCapture implements AutoCloseable {
    private final String name;
    private final Object context;
    private final Object configuration;
    private final Object logger;
    private final Object previousLevel;
    private final Object previousConfig;
    private final Object appender;

    private LogCapture(String name) throws Exception {
      this.name = name;
      // Log4j Core is runtime-only; keep the project's existing dependency scopes.
      context =
          ReflectionTestUtils.invokeMethod(
              Class.forName("org.apache.logging.log4j.LogManager"), "getContext", false);
      configuration = ReflectionTestUtils.invokeMethod(context, "getConfiguration");
      Map<String, ?> loggers = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
      previousConfig = loggers.get(name);
      logger = ReflectionTestUtils.invokeMethod(context, "getLogger", name);
      previousLevel = ReflectionTestUtils.invokeMethod(logger, "getLevel");
      Object layoutBuilder =
          ReflectionTestUtils.invokeMethod(
              Class.forName("org.apache.logging.log4j.core.layout.PatternLayout"), "newBuilder");
      ReflectionTestUtils.invokeMethod(layoutBuilder, "withPattern", "%p %m%n%ex{full}");
      Object layout = ReflectionTestUtils.invokeMethod(layoutBuilder, "build");
      Object appenderBuilder =
          ReflectionTestUtils.invokeMethod(
              Class.forName("org.apache.logging.log4j.core.appender.ConsoleAppender"),
              "newBuilder");
      ReflectionTestUtils.invokeMethod(appenderBuilder, "setName", "inbound-payload-capture");
      ReflectionTestUtils.invokeMethod(appenderBuilder, "setFollow", true);
      ReflectionTestUtils.invokeMethod(appenderBuilder, "setLayout", layout);
      appender = ReflectionTestUtils.invokeMethod(appenderBuilder, "build");
      ReflectionTestUtils.invokeMethod(appender, "start");
      Object replacement = loggerConfig(name, "ALL");
      ReflectionTestUtils.invokeMethod(replacement, "addAppender", appender, null, null);
      ReflectionTestUtils.invokeMethod(configuration, "removeLogger", name);
      ReflectionTestUtils.invokeMethod(configuration, "addLogger", name, replacement);
      ReflectionTestUtils.invokeMethod(context, "updateLoggers");
    }

    private static Object loggerConfig(String name, String level) throws Exception {
      Class<?> levelType = Class.forName("org.apache.logging.log4j.Level");
      return Class.forName("org.apache.logging.log4j.core.config.LoggerConfig")
          .getConstructor(String.class, levelType, boolean.class)
          .newInstance(name, levelType.getField(level).get(null), false);
    }

    private void checkControlEvent(CapturedOutput output) {
      int start = output.getAll().length();
      LoggerFactory.getLogger(name).trace("inbound log capture control");
      assertThat(output.getAll().substring(start)).contains("TRACE inbound log capture control");
    }

    @Override
    public void close() {
      try {
        ReflectionTestUtils.invokeMethod(configuration, "removeLogger", name);
        if (previousConfig != null) {
          ReflectionTestUtils.invokeMethod(configuration, "addLogger", name, previousConfig);
        }
        ReflectionTestUtils.invokeMethod(context, "updateLoggers");
        ReflectionTestUtils.invokeMethod(logger, "setLevel", previousLevel);
      } finally {
        ReflectionTestUtils.invokeMethod(appender, "stop");
      }
    }
  }
}
