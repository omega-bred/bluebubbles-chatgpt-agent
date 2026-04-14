package io.breland.bbagent.server.agent.tools.scheduled;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.cadence.client.WorkflowOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScheduledEventToolTest {

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
    String description = new ScheduledEventTool(null).getTool().description();

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
