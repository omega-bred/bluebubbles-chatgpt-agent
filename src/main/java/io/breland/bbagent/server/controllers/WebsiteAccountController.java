package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.WebsiteAccountDeleteLinkResponse;
import io.breland.bbagent.generated.model.WebsiteAccountRedeemLinkRequest;
import io.breland.bbagent.generated.model.WebsiteAccountRedeemLinkResponse;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.server.website.WebsiteAccountService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class WebsiteAccountController {
  private final WebsiteAccountService accountService;

  public WebsiteAccountController(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @GetMapping(
      path = "/api/v1/websiteAccount/get.websiteAccount",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebsiteAccountResponse> websiteAccountGet(
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(accountService.getAccount(jwt));
  }

  @GetMapping(
      path = "/api/v1/websiteAccount/listLinkedAccounts.websiteAccountLinks",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebsiteLinkedAccountsResponse> websiteAccountListLinkedAccounts(
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(accountService.listLinkedAccounts(jwt));
  }

  @PostMapping(
      path = "/api/v1/websiteAccount/redeemLink.websiteAccountLinks",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebsiteAccountRedeemLinkResponse> websiteAccountRedeemLink(
      @RequestBody WebsiteAccountRedeemLinkRequest request, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(accountService.redeemLink(jwt, request.getToken()));
  }

  @DeleteMapping(
      path = "/api/v1/websiteAccount/deleteLink.websiteAccountLinks",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WebsiteAccountDeleteLinkResponse> websiteAccountDeleteLink(
      @RequestParam("link_id") String linkId, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(
        new WebsiteAccountDeleteLinkResponse().deleted(accountService.deleteLink(jwt, linkId)));
  }
}
