package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.AdminFeedbackItem;
import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.generated.model.AdminFeedbackStatusUpdateRequest;
import io.breland.bbagent.generated.model.AdminRateLimitUsageResponse;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.admin.AdminStatsService;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class AdminController {
  private static final Duration DEFAULT_STATS_PERIOD = Duration.ofHours(24);

  private final AdminStatsService adminStatsService;
  private final FeedbackService feedbackService;
  private final MessageResponseRateLimitService messageResponseRateLimitService;

  public AdminController(
      AdminStatsService adminStatsService,
      FeedbackService feedbackService,
      MessageResponseRateLimitService messageResponseRateLimitService) {
    this.adminStatsService = adminStatsService;
    this.feedbackService = feedbackService;
    this.messageResponseRateLimitService = messageResponseRateLimitService;
  }

  @GetMapping(path = "/api/v1/admin/get.statistics", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminStatsResponse> adminGetStatistics(
      @RequestParam(value = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(value = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    Instant resolvedTo = to == null ? Instant.now() : to.toInstant();
    Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_STATS_PERIOD) : from.toInstant();
    if (!resolvedFrom.isBefore(resolvedTo)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(adminStatsService.getStatistics(resolvedFrom, resolvedTo));
  }

  @GetMapping(
      path = "/api/v1/admin/get.rate_limit_usage",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminRateLimitUsageResponse> adminGetRateLimitUsage(
      @RequestParam(value = "limit_key", required = false) String limitKey,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    return ResponseEntity.ok(messageResponseRateLimitService.adminUsage(limitKey, limit));
  }

  @GetMapping(path = "/api/v1/admin/list.feedback", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminFeedbackListResponse> adminListFeedback(
      @RequestParam(value = "status", required = false, defaultValue = "unread") String status,
      @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit) {
    return ResponseEntity.ok(feedbackService.listFeedback(status, limit));
  }

  @PostMapping(
      path = "/api/v1/admin/markRead.feedback",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminFeedbackItem> adminMarkFeedbackRead(
      @RequestBody(required = false) AdminFeedbackStatusUpdateRequest request) {
    return feedbackStatusResponse(request, true);
  }

  @PostMapping(
      path = "/api/v1/admin/markUnread.feedback",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminFeedbackItem> adminMarkFeedbackUnread(
      @RequestBody(required = false) AdminFeedbackStatusUpdateRequest request) {
    return feedbackStatusResponse(request, false);
  }

  private ResponseEntity<AdminFeedbackItem> feedbackStatusResponse(
      AdminFeedbackStatusUpdateRequest request, boolean read) {
    if (request == null || request.getFeedbackId() == null || request.getFeedbackId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    Optional<AdminFeedbackItem> item =
        read
            ? feedbackService.markRead(request.getFeedbackId())
            : feedbackService.markUnread(request.getFeedbackId());
    return item.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
