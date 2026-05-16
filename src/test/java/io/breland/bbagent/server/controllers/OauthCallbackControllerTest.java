package io.breland.bbagent.server.controllers;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.config.SecurityConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CoderOauthController.class, GcalOauthController.class, RootController.class})
@Import(SecurityConfig.class)
class OauthCallbackControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockBean private CoderMcpClient coderMcpClient;

  @MockBean private GcalClient gcalClient;

  @MockBean private BBHttpClientWrapper bbHttpClientWrapper;

  @Test
  void oauthCallbackRouteServesFrontend() throws Exception {
    mockMvc
        .perform(get("/oauth/callback"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
  }

  @Test
  void gcalDeniedOauthRedirectsToFrontendResult() throws Exception {
    mockMvc
        .perform(get("/api/v1/gcal/completeOauth.gcal").param("error", "access_denied"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", containsString("/oauth/callback?")))
        .andExpect(header().string("Location", containsString("service=gcal")))
        .andExpect(header().string("Location", containsString("status=error")))
        .andExpect(
            header()
                .string(
                    "Location",
                    containsString(
                        "Google%20Calendar%20OAuth%20was%20not%20approved:%20access_denied")));

    verifyNoInteractions(bbHttpClientWrapper);
  }

  @Test
  void gcalSuccessSendsFollowupAndRedirectsToFrontendResult() throws Exception {
    GcalClient.OauthState oauthState =
        new GcalClient.OauthState("account-base", "pending-key", "chat-guid", "message-guid");
    when(gcalClient.isConfigured()).thenReturn(true);
    when(gcalClient.parseOauthState("state-token")).thenReturn(Optional.of(oauthState));
    when(gcalClient.exchangeCode("account-base", "pending-key", "oauth-code"))
        .thenReturn(Optional.of("primary"));

    mockMvc
        .perform(
            get("/api/v1/gcal/completeOauth.gcal")
                .param("code", "oauth-code")
                .param("state", "state-token"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", containsString("service=gcal")))
        .andExpect(header().string("Location", containsString("status=success")))
        .andExpect(
            header()
                .string(
                    "Location",
                    containsString(
                        "Google%20Calendar%20linked.%20You%20can%20close%20this%20tab.")));

    verify(bbHttpClientWrapper)
        .sendTextDirect(
            argThat(
                request ->
                    "chat-guid".equals(request.getChatGuid())
                        && "message-guid".equals(request.getSelectedMessageGuid())
                        && Integer.valueOf(0).equals(request.getPartIndex())
                        && "Calendar successfully linked.".equals(request.getMessage())));
  }

  @Test
  void coderMissingOauthParamsRedirectsToFrontendResult() throws Exception {
    mockMvc
        .perform(get("/api/v1/coder/completeOauth.coder"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", containsString("/oauth/callback?")))
        .andExpect(header().string("Location", containsString("service=coder")))
        .andExpect(header().string("Location", containsString("status=error")));

    verifyNoInteractions(bbHttpClientWrapper);
  }

  @Test
  void coderSuccessSendsFollowupAndRedirectsToFrontendResult() throws Exception {
    when(coderMcpClient.isConfigured()).thenReturn(true);
    when(coderMcpClient.completeOauth("oauth-code", "state-token"))
        .thenReturn(
            Optional.of(
                new CoderMcpClient.OauthCompletion("account-base", "chat-guid", "message-guid")));

    mockMvc
        .perform(
            get("/api/v1/coder/completeOauth.coder")
                .param("code", "oauth-code")
                .param("state", "state-token"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", containsString("service=coder")))
        .andExpect(header().string("Location", containsString("status=success")))
        .andExpect(
            header()
                .string(
                    "Location",
                    containsString("Coder%20linked.%20You%20can%20close%20this%20tab.")));

    verify(bbHttpClientWrapper)
        .sendTextDirect(
            argThat(
                request ->
                    "chat-guid".equals(request.getChatGuid())
                        && "message-guid".equals(request.getSelectedMessageGuid())
                        && Integer.valueOf(0).equals(request.getPartIndex())
                        && "Coder successfully linked.".equals(request.getMessage())));
  }
}
