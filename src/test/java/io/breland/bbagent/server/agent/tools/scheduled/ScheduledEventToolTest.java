package io.breland.bbagent.server.agent.tools.scheduled;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.WorkflowOptions;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScheduledEventToolTest {

  @ParameterizedTest
  @ValueSource(strings = {IncomingMessage.TRANSPORT_BLUEBUBBLES, IncomingMessage.TRANSPORT_LXMF})
  void scheduledMessagePreservesSourceRoutingWithoutInboundProvenance(String transport) {
    CadenceWorkflowLauncher launcher = Mockito.mock(CadenceWorkflowLauncher.class);
    when(launcher.startScheduledWorkflow(any(), any(), any(), any()))
        .thenReturn(new WorkflowExecution());
    IncomingMessage source =
        new IncomingMessage(
            transport,
            "chat-1",
            "msg-1",
            "thread-1",
            "remind me",
            false,
            "service",
            "sender",
            false,
            Instant.now(),
            List.of(),
            false);
    if (source.isBlueBubblesTransport()) {
      source = source.withVerifiedBlueBubblesInbound(List.of());
    }

    String result =
        new ScheduledEventTool(launcher)
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(source).build(),
                new ObjectMapper()
                    .createObjectNode()
                    .put("task", "say moon")
                    .put("delaySeconds", 30));

    assertEquals("scheduled", result);
    ArgumentCaptor<CadenceMessageWorkflowRequest> request =
        ArgumentCaptor.forClass(CadenceMessageWorkflowRequest.class);
    verify(launcher).startScheduledWorkflow(request.capture(), any(), any(), any());
    IncomingMessage scheduled = request.getValue().message();
    assertEquals(source.transportOrDefault(), scheduled.transportOrDefault());
    assertEquals(source.chatGuid(), scheduled.chatGuid());
    assertEquals(source.sender(), scheduled.sender());
    assertEquals(source.service(), scheduled.service());
    assertEquals(source.threadOriginatorGuid(), scheduled.threadOriginatorGuid());
    assertTrue(scheduled.isSystemMessage());
    assertFalse(scheduled.verifiedBlueBubblesInbound());
  }

  @Test
  void createsCadenceValidUnixCronForSupportedRepeatIntervals() {
    assertCadenceAccepts("*/1 * * * *", Duration.ofMinutes(1));
    assertCadenceAccepts("*/5 * * * *", Duration.ofMinutes(5));
    assertCadenceAccepts("0 */1 * * *", Duration.ofHours(1));
    assertCadenceAccepts("0 0 * * *", Duration.ofDays(1));
    assertCadenceAccepts("0 0 * * 0", Duration.ofDays(7));
  }

  @Test
  void rejectsIntervalsThatUnixCronCannotRepresentAccurately() {
    assertNull(ScheduledEventTool.resolveCronSchedule(Duration.ofSeconds(30)));
    assertNull(ScheduledEventTool.resolveCronSchedule(Duration.ofMinutes(45)));
    assertNull(ScheduledEventTool.resolveCronSchedule(Duration.ofHours(5)));
  }

  @Test
  void toolDescriptionTellsLongRunningChecksToRescheduleThemselves() {
    String description =
        new ScheduledEventTool(Mockito.mock(CadenceWorkflowLauncher.class)).getTool().description();

    assertTrue(description.contains("pending or running"));
    assertTrue(description.contains("schedule_event again"));
    assertTrue(description.contains("max attempts/deadline"));
  }

  private static void assertCadenceAccepts(String expectedCron, Duration interval) {
    String cron = ScheduledEventTool.resolveCronSchedule(interval);
    assertEquals(expectedCron, cron);
    assertDoesNotThrow(
        () ->
            new WorkflowOptions.Builder()
                .setTaskList("bbagent-test")
                .setExecutionStartToCloseTimeout(Duration.ofMinutes(5))
                .setCronSchedule(cron)
                .validateBuildWithDefaults());
  }
}
