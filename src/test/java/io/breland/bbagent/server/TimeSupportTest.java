package io.breland.bbagent.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeSupportTest {

  @Test
  void epochSecondsOrMillisReturnsNullForNullInput() {
    assertNull(TimeSupport.epochSecondsOrMillis(null));
  }

  @Test
  void epochSecondsOrMillisOrNowUsesCurrentInstantForNullInput() {
    Instant before = Instant.now();
    Instant actual = TimeSupport.epochSecondsOrMillisOrNow(null);
    Instant after = Instant.now();

    assertFalse(actual.isBefore(before));
    assertFalse(actual.isAfter(after));
  }

  @Test
  void epochSecondsOrMillisParsesEpochSeconds() {
    assertEquals(
        Instant.ofEpochSecond(1_700_000_000L), TimeSupport.epochSecondsOrMillis(1_700_000_000L));
  }

  @Test
  void epochSecondsOrMillisParsesEpochMillis() {
    assertEquals(
        Instant.ofEpochMilli(1_700_000_000_000L),
        TimeSupport.epochSecondsOrMillis(1_700_000_000_000L));
  }
}
