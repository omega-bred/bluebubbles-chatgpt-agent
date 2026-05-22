package io.breland.bbagent.server.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class UmamiAnalyticsServiceTest {

  @Test
  void buildRequestReturnsNullWhenDisabled() {
    UmamiAnalyticsProperties properties = new UmamiAnalyticsProperties();
    UmamiAnalyticsService service = new UmamiAnalyticsService(properties, WebClient.builder());

    assertThat(service.buildRequest("event", "/server/test", "account-1", Map.of())).isNull();
  }

  @Test
  void buildRequestSanitizesPayloadAndHashesDistinctId() {
    UmamiAnalyticsProperties properties = new UmamiAnalyticsProperties();
    properties.setEnabled(true);
    properties.setWebsiteId("website-1");
    properties.setHostname("example.test");
    UmamiAnalyticsService service = new UmamiAnalyticsService(properties, WebClient.builder());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kind", "checkout");
    data.put("null_value", null);
    data.put("long_value", "x".repeat(600));

    var request =
        service.buildRequest(
            "subscription_checkout_created_extra_long_event_name_that_will_be_trimmed",
            "/server/subscription/checkout?token=secret",
            "account-1",
            data);

    assertThat(request).isNotNull();
    assertThat(request.type()).isEqualTo("event");
    assertThat(request.payload().hostname()).isEqualTo("example.test");
    assertThat(request.payload().website()).isEqualTo("website-1");
    assertThat(request.payload().name()).hasSize(50);
    assertThat(request.payload().url()).isEqualTo("/server/subscription/checkout");
    assertThat(request.payload().id()).hasSize(50).doesNotContain("account-1");
    assertThat(request.payload().data()).containsEntry("kind", "checkout");
    assertThat(request.payload().data()).doesNotContainKey("null_value");
    assertThat((String) request.payload().data().get("long_value")).hasSize(500);
  }
}
