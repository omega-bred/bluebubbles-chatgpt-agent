package io.breland.bbagent.server.agent.tools.gcal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GcalToolSupportTest {

  private final TestSupport support = new TestSupport(null);

  @Test
  void resolveCalendarIdDefaultsBlankValuesToPrimary() {
    assertEquals("primary", support.calendarId(null));
    assertEquals("primary", support.calendarId(" "));
    assertEquals("team-calendar", support.calendarId("team-calendar"));
  }

  @Test
  void resolveZoneFallsBackToSystemDefaultForBlankOrInvalidValues() {
    assertEquals(ZoneId.systemDefault(), support.zone(null));
    assertEquals(ZoneId.systemDefault(), support.zone(" "));
    assertEquals(ZoneId.systemDefault(), support.zone("not/a-zone"));
    assertEquals(ZoneId.of("America/Los_Angeles"), support.zone("America/Los_Angeles"));
  }

  @Test
  void attendeesFromEmailsDropsBlankAddresses() {
    assertEquals(
        List.of("a@example.com", "b@example.com"),
        support.attendeeEmails(Arrays.asList("a@example.com", "", null, "b@example.com")));
  }

  @Test
  void freeBusyItemsFromCalendarIdsDropsBlankCalendarIds() {
    assertEquals(
        List.of("primary", "team-calendar"),
        support.freeBusyCalendarIds(Arrays.asList("primary", " ", null, "team-calendar")));
  }

  private static final class TestSupport extends GcalToolSupport {
    private TestSupport(GcalClient gcalClient) {
      super(gcalClient);
    }

    private String calendarId(String calendarId) {
      return resolveCalendarId(calendarId);
    }

    private ZoneId zone(String timezone) {
      return resolveZone(timezone);
    }

    private List<String> attendeeEmails(List<String> emails) {
      return attendeesFromEmails(emails).stream().map(EventAttendee::getEmail).toList();
    }

    private List<String> freeBusyCalendarIds(List<String> calendarIds) {
      return freeBusyItemsFromCalendarIds(calendarIds).stream()
          .map(FreeBusyRequestItem::getId)
          .toList();
    }
  }
}
