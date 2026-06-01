package io.breland.bbagent.server.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationSettingsServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void getSettingsIncludesConversationMetadataAndCurrentResponsiveness() throws Exception {
    AgentProfileService profileService = Mockito.mock(AgentProfileService.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    when(profileService.getAssistantResponsiveness("chat-guid"))
        .thenReturn(AssistantResponsiveness.LESS_RESPONSIVE);
    when(bbHttpClientWrapper.getConversationInfoJson("chat-guid"))
        .thenReturn(
            mapper.readTree(
                """
                {
                  "displayName": "Project Chat",
                  "chatIdentifier": "chat@example.com",
                  "isGroup": true,
                  "participants": [
                    {"address": "+15555550123"},
                    {"address": "alice@example.com"}
                  ],
                  "icon": "https://example.com/icon.png"
                }
                """));
    ConversationSettingsService service =
        new ConversationSettingsService(profileService, bbHttpClientWrapper, null);

    ConversationSettingsResponse response = service.getSettings("account-1", "chat-guid");

    assertThat(response.getConversation().getDisplayName()).isEqualTo("Project Chat");
    assertThat(response.getConversation().getChatIdentifier()).isEqualTo("chat@example.com");
    assertThat(response.getConversation().getParticipantCount()).isEqualTo(2);
    assertThat(response.getConversation().getParticipants()).hasSize(2);
    assertThat(response.getConversation().getIconUrl()).isEqualTo("https://example.com/icon.png");
    assertThat(response.getCurrentResponsiveness())
        .isEqualTo(ConversationSettingsResponse.CurrentResponsivenessEnum.LESS_RESPONSIVE);
    assertThat(response.getOptions()).hasSize(4);
  }

  @Test
  void updateResponsivenessUsesExistingConversationScopedSetting() {
    AgentProfileService profileService = Mockito.mock(AgentProfileService.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    when(profileService.getAssistantResponsiveness("chat-guid"))
        .thenReturn(AssistantResponsiveness.MORE_RESPONSIVE);
    ConversationSettingsService service =
        new ConversationSettingsService(profileService, bbHttpClientWrapper, null);

    var response = service.updateResponsiveness("account-1", "chat-guid", "more_responsive");

    verify(profileService)
        .setAssistantResponsiveness("chat-guid", AssistantResponsiveness.MORE_RESPONSIVE);
    assertThat(response.getSettings().getCurrentResponsiveness())
        .isEqualTo(ConversationSettingsResponse.CurrentResponsivenessEnum.MORE_RESPONSIVE);
    assertThat(response.getMessage()).contains("Active");
  }
}
