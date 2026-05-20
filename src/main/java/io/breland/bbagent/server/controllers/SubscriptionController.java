package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.SubscriptionCheckoutRequest;
import io.breland.bbagent.generated.model.SubscriptionCheckoutResponse;
import io.breland.bbagent.generated.model.SubscriptionPlansResponse;
import io.breland.bbagent.generated.model.SubscriptionPortalResponse;
import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.generated.model.SubscriptionSummaryResponse;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class SubscriptionController {
  private final SubscriptionService subscriptionService;

  public SubscriptionController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @GetMapping(
      path = "/api/v1/subscription/list.subscriptionPlans",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionPlansResponse> subscriptionListPlans() {
    return ResponseEntity.ok(subscriptionService.listPlans());
  }

  @GetMapping(
      path = "/api/v1/subscription/get.subscription",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionSummaryResponse> subscriptionGet(
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(subscriptionService.getAccountSubscription(jwt));
  }

  @PostMapping(
      path = "/api/v1/subscription/createCheckout.subscriptionCheckouts",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionCheckoutResponse> subscriptionCreateCheckout(
      @RequestBody(required = false) SubscriptionCheckoutRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(subscriptionService.createCheckout(jwt, request));
  }

  @PostMapping(
      path = "/api/v1/subscription/createPortal.subscriptionPortals",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionPortalResponse> subscriptionCreatePortal(
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(subscriptionService.createPortal(jwt));
  }

  @PostMapping(
      path = "/api/v1/subscription/receiveWebhook.subscriptionProviderEvents",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionProviderWebhookResponse> subscriptionReceiveWebhook(
      @RequestParam("provider") String provider,
      @RequestHeader HttpHeaders headers,
      @RequestBody byte[] body) {
    SubscriptionProviderWebhookResponse response =
        subscriptionService.receiveWebhook(provider, headers, body);
    HttpStatus status =
        "unauthorized".equals(response.getStatus())
            ? HttpStatus.UNAUTHORIZED
            : "failed".equals(response.getStatus())
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.OK;
    return ResponseEntity.status(status).body(response);
  }
}
