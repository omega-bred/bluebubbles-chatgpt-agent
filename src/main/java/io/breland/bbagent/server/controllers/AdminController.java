package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.AdminAccountBlockListResponse;
import io.breland.bbagent.generated.model.AdminAccountBlockRequest;
import io.breland.bbagent.generated.model.AdminAccountBlockResponse;
import io.breland.bbagent.generated.model.AdminFeedbackItem;
import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.generated.model.AdminFeedbackStatusUpdateRequest;
import io.breland.bbagent.generated.model.AdminPremiumGrantRequest;
import io.breland.bbagent.generated.model.AdminRateLimitUsageResponse;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.generated.model.AdminSubscriptionActionRequest;
import io.breland.bbagent.generated.model.AdminSubscriptionActionResponse;
import io.breland.bbagent.generated.model.AdminSubscriptionListResponse;
import io.breland.bbagent.server.admin.AccountModerationService;
import io.breland.bbagent.server.admin.AdminStatsService;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
  private final SubscriptionService subscriptionService;
  private final AccountModerationService accountModerationService;

  public AdminController(
      AdminStatsService adminStatsService,
      FeedbackService feedbackService,
      MessageResponseRateLimitService messageResponseRateLimitService,
      SubscriptionService subscriptionService,
      AccountModerationService accountModerationService) {
    this.adminStatsService = adminStatsService;
    this.feedbackService = feedbackService;
    this.messageResponseRateLimitService = messageResponseRateLimitService;
    this.subscriptionService = subscriptionService;
    this.accountModerationService = accountModerationService;
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

  @GetMapping(
      path = "/api/v1/admin/list.subscriptions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionListResponse> adminListSubscriptions(
      @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit) {
    return ResponseEntity.ok(
        subscriptionService.adminListSubscriptions(limit == null ? 100 : limit));
  }

  @PostMapping(
      path = "/api/v1/admin/sync.subscription",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionActionResponse> adminSyncSubscription(
      @RequestBody(required = false) AdminSubscriptionActionRequest request) {
    return ResponseEntity.ok(subscriptionService.adminSync(request));
  }

  @PostMapping(
      path = "/api/v1/admin/suspend.subscription",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionActionResponse> adminSuspendSubscription(
      @RequestBody(required = false) AdminSubscriptionActionRequest request) {
    return ResponseEntity.ok(subscriptionService.adminSuspend(request));
  }

  @PostMapping(
      path = "/api/v1/admin/unsuspend.subscription",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionActionResponse> adminUnsuspendSubscription(
      @RequestBody(required = false) AdminSubscriptionActionRequest request) {
    return ResponseEntity.ok(subscriptionService.adminUnsuspend(request));
  }

  @PostMapping(
      path = "/api/v1/admin/grantPremium.subscription",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionActionResponse> adminGrantPremium(
      @RequestBody(required = false) AdminPremiumGrantRequest request) {
    return ResponseEntity.ok(subscriptionService.adminGrantPremium(request));
  }

  @PostMapping(
      path = "/api/v1/admin/revokePremium.subscription",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminSubscriptionActionResponse> adminRevokePremium(
      @RequestBody(required = false) AdminPremiumGrantRequest request) {
    return ResponseEntity.ok(subscriptionService.adminRevokeManualPremium(request));
  }

  @GetMapping(
      path = "/api/v1/admin/list.accountBlocks",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminAccountBlockListResponse> adminListAccountBlocks(
      @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit) {
    return ResponseEntity.ok(accountModerationService.listBlocked(limit));
  }

  @PostMapping(
      path = "/api/v1/admin/block.account",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminAccountBlockResponse> adminBlockAccount(
      @RequestBody(required = false) AdminAccountBlockRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(accountModerationService.block(request, jwt));
  }

  @PostMapping(
      path = "/api/v1/admin/unblock.account",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminAccountBlockResponse> adminUnblockAccount(
      @RequestBody(required = false) AdminAccountBlockRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(accountModerationService.unblock(request, jwt));
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
