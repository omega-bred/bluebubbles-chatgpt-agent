package io.breland.bbagent.server.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.server.config.SecurityConfig;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebsiteAccountController.class)
@Import(SecurityConfig.class)
class WebsiteAccountControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockBean private WebsiteAccountService accountService;

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
                .account(
                    new WebsiteAccountProfile()
                        .subject("sub-1")
                        .email("alice@example.com")
                        .preferredUsername("alice"))
                .links(List.of()));

    mockMvc
        .perform(
            get("/api/v1/websiteAccount/get.websiteAccount")
                .with(jwt().jwt(token -> token.subject("sub-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.account.subject").value("sub-1"))
        .andExpect(jsonPath("$.account.email").value("alice@example.com"));
  }
}
