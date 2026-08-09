package io.breland.bbagent.server.controllers;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.generated.model.ConversationGroupMemorySetting;
import io.breland.bbagent.generated.model.ConversationPersonalCatchupSetting;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.ConversationSettingsUpdateResponse;
import io.breland.bbagent.generated.model.ConversationSummary;
import io.breland.bbagent.server.appclip.AppClipSessionAuthenticationFilter;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import io.breland.bbagent.server.config.SecurityConfig;
import io.breland.bbagent.server.conversation.ConversationSettingsService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConversationSettingsController.class)
@Import(SecurityConfig.class)
class ConversationSettingsControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private ConversationSettingsService settingsService;
  @MockitoBean private AppClipSessionService appClipSessionService;

  @Test
  void getsConversationSettingsForConversationPurposeSession() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1",
                    WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS,
                    "chat-guid",
                    Instant.now().plusSeconds(300))));
    when(settingsService.getSettings("account-1", "chat-guid"))
        .thenReturn(settingsResponse("default", "Balanced"));

    mockMvc
        .perform(
            get("/api/v1/conversationSettings/get.conversationSettings")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversation.display_name").value("Project Chat"))
        .andExpect(jsonPath("$.current_responsiveness").value("default"));
  }

  @Test
  void rejectsAccountPurposeSession() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1",
                    WebsiteAccountService.LINK_PURPOSE_ACCOUNT_LINK,
                    null,
                    Instant.now().plusSeconds(300))));

    mockMvc
        .perform(
            get("/api/v1/conversationSettings/get.conversationSettings")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session"))
        .andExpect(status().isForbidden());
  }

  @Test
  void updatesResponsiveness() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1",
                    WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS,
                    "chat-guid",
                    Instant.now().plusSeconds(300))));
    when(settingsService.updateResponsiveness(
            eq("account-1"), eq("chat-guid"), eq("more_responsive")))
        .thenReturn(
            new ConversationSettingsUpdateResponse()
                .settings(settingsResponse("more_responsive", "Active"))
                .message("Conversation response style changed to Active."));

    mockMvc
        .perform(
            post("/api/v1/conversationSettings/updateResponsiveness.conversationSettings")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"responsiveness\":\"more_responsive\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.current_responsiveness").value("more_responsive"));
  }

  @Test
  void updatesGroupMemoryWithinAuthenticatedConversationScope() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1",
                    WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS,
                    "chat-guid",
                    Instant.now().plusSeconds(300))));
    when(settingsService.updateGroupMemory("account-1", "chat-guid", true))
        .thenReturn(
            new ConversationSettingsUpdateResponse()
                .settings(settingsResponse("default", "Balanced"))
                .message("Group memory enabled."));

    mockMvc
        .perform(
            post("/api/v1/conversationSettings/updateGroupMemory.conversationSettings")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.group_memory.enabled").value(true));
  }

  @Test
  void updatesPersonalCatchupsWithinAuthenticatedAccountAndConversationScope() throws Exception {
    when(appClipSessionService.authenticate("clip-session"))
        .thenReturn(
            Optional.of(
                new AppClipSessionService.AuthenticatedAppClipSession(
                    "account-1",
                    WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS,
                    "chat-guid",
                    Instant.now().plusSeconds(300))));
    when(settingsService.updateCatchups(
            "account-1", "chat-guid", true, "America/Los_Angeles", "22:00", "08:00"))
        .thenReturn(
            new ConversationSettingsUpdateResponse()
                .settings(settingsResponse("default", "Balanced"))
                .message("Personal catch-ups enabled."));

    mockMvc
        .perform(
            post("/api/v1/conversationSettings/updateCatchups.conversationSettings")
                .header(AppClipSessionAuthenticationFilter.SESSION_HEADER, "clip-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"enabled":true,"timezone":"America/Los_Angeles",
                     "quiet_start":"22:00","quiet_end":"08:00"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.personal_catchups.enabled").value(false));
  }

  private ConversationSettingsResponse settingsResponse(String responsiveness, String label) {
    return new ConversationSettingsResponse()
        .conversation(
            new ConversationSummary()
                .chatGuid("chat-guid")
                .displayName("Project Chat")
                .participants(List.of()))
        .currentResponsiveness(
            ConversationSettingsResponse.CurrentResponsivenessEnum.fromValue(responsiveness))
        .currentResponsivenessLabel(label)
        .options(List.of())
        .groupMemory(
            new ConversationGroupMemorySetting()
                .available(true)
                .enabled(true)
                .label("Memory")
                .description("Prospective collective memory"))
        .personalCatchups(
            new ConversationPersonalCatchupSetting()
                .available(false)
                .enabled(false)
                .timezone("UTC")
                .quietStart("22:00")
                .quietEnd("08:00"));
  }
}
