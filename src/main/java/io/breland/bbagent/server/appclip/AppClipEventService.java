package io.breland.bbagent.server.appclip;

import io.breland.bbagent.generated.model.AppClipEventRequest;
import io.breland.bbagent.generated.model.AppClipEventResponse;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppClipEventService {
  private static final int MAX_EVENT_NAME_LENGTH = 50;

  private final UmamiAnalyticsService umamiAnalyticsService;

  public AppClipEventService(@Nullable UmamiAnalyticsService umamiAnalyticsService) {
    this.umamiAnalyticsService = umamiAnalyticsService;
  }

  public AppClipEventResponse track(String accountId, AppClipEventRequest request) {
    return track(eventName(request), accountId, eventData(request));
  }

  public AppClipEventResponse trackBootstrap(AppClipEventRequest request) {
    return track(eventName(request), "appclip-bootstrap", eventData(request));
  }

  private AppClipEventResponse track(String eventName, String visitorId, Map<String, Object> data) {
    data.put("source", "app_clip");
    if (umamiAnalyticsService != null) {
      umamiAnalyticsService.track(eventName, "/appclip/" + pathSegment(eventName), visitorId, data);
    }
    return new AppClipEventResponse().accepted(true);
  }

  private String eventName(AppClipEventRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing event");
    }
    String eventName =
        StringUtils.truncate(StringUtils.trimToNull(request.getEventName()), MAX_EVENT_NAME_LENGTH);
    if (eventName == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing event name");
    }
    return eventName;
  }

  private Map<String, Object> eventData(AppClipEventRequest request) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (request.getProperties() != null) {
      data.putAll(request.getProperties());
    }
    return data;
  }

  private String pathSegment(String eventName) {
    String normalized =
        eventName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-+", "-");
    return StringUtils.defaultIfBlank(StringUtils.strip(normalized, "-"), "event");
  }
}
