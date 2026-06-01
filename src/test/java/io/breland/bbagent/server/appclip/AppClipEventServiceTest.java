package io.breland.bbagent.server.appclip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.breland.bbagent.generated.model.AppClipEventRequest;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class AppClipEventServiceTest {

  @Test
  void trackSendsAppClipEventToUmami() {
    UmamiAnalyticsService analyticsService = mock(UmamiAnalyticsService.class);
    AppClipEventService service = new AppClipEventService(analyticsService);
    AppClipEventRequest request =
        new AppClipEventRequest()
            .eventName("appclip model selection updated")
            .properties(Map.of("source", "client", "model", "chatgpt"));

    assertThat(service.track("account-1", request).getAccepted()).isTrue();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> dataCaptor =
        ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    verify(analyticsService)
        .track(
            eq("appclip model selection updated"),
            eq("/appclip/appclip-model-selection-updated"),
            eq("account-1"),
            dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("model", "chatgpt")
        .containsEntry("source", "app_clip");
  }

  @Test
  void trackRejectsBlankEventName() {
    AppClipEventService service = new AppClipEventService(null);
    AppClipEventRequest request = new AppClipEventRequest().eventName(" ");

    assertThatThrownBy(() -> service.track("account-1", request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Missing event name");
  }
}
