package io.breland.bbagent.server.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelSelectionResponse;
import io.breland.bbagent.server.appclip.AppClipSessionAuthenticationFilter;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import io.breland.bbagent.server.config.SecurityConfig;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebsiteAccountController.class)
@Import({SecurityConfig.class, AppClipSessionAuthenticationFilter.class})
class WebsiteAccountControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockBean private WebsiteAccountService accountService;
  @MockBean private AppClipSessionService appClipSessionService;

  @Test
  void accountApiRequiresAuthentication() throws Exception {
    mockMvc
        .perform(get("/api/v1/websiteAccount/get.websiteAccount"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void accountApiReturnsCurrentAccountForJwt() throws Exception {
    when(accountService.getAccount(any(Jwt.class)))
        .thenReturn(
            new WebsiteAccountResponse()
                .account(new WebsiteAccountProfile().subject("sub-1").email("alice@example.com"))
                .links(List.of()));

    mockMvc
        .perform(
            get("/api/v1/websiteAccount/get.websiteAccount")
                .with(jwt().jwt(token -> token.subject("sub-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account.subject").value("sub-1"))
        .andExpect(jsonPath("$.account.email").value("alice@example.com"));
  }

  @Test
  void modelUpdateAcceptsAppClipSessionAuthentication() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1", Instant.now().plusSeconds(300))));
    when(accountService.updatePreferredModel(any(Jwt.class), eq("claude")))
        .thenReturn(
            new WebsiteModelSelectionResponse()
                .modelAccess(
                    new WebsiteModelAccessSummary()
                        .accountId("account-1")
                        .isPremium(true)
                        .currentModel("claude")
                        .currentModelLabel("Claude")
                        .modelSelectionAllowed(true)
                        .modelSelectionConfigurable(true)
                        .availableModels(List.of()))
                .message("Model changed"));

    mockMvc
        .perform(
            post("/api/v1/websiteAccount/updateModel.websiteAccountModels")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"claude\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model_access.current_model").value("claude"));

    ArgumentCaptor<Jwt> jwtCaptor = ArgumentCaptor.forClass(Jwt.class);
    org.mockito.Mockito.verify(accountService)
        .updatePreferredModel(jwtCaptor.capture(), eq("claude"));
    Assertions.assertEquals(
        "account-1",
        jwtCaptor.getValue().getClaimAsString(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM));
  }
}
