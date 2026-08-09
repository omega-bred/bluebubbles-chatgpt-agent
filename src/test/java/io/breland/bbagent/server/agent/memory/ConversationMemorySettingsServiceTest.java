package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService.GroupMemorySetting;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationMemorySettingsServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");

  @Autowired private ConversationMemorySettingsService settingsService;
  @Autowired private ConversationMemoryStore store;
  @Autowired private AgentAccountResolver accountResolver;
  @MockitoBean private BBHttpClientWrapper bbHttpClientWrapper;

  @Test
  void enablingGroupMemoryStartsProspectivelyAfterConfirmedGroupNotice() {
    String accountId = createAccount("enabler@example.com");
    store.upsertConversation(
        IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;settings-group", true, "Planning", NOW);
    when(bbHttpClientWrapper.sendTextDirect(any())).thenReturn(true);

    GroupMemorySetting setting =
        settingsService.updateGroupMemory(accountId, "iMessage;+;settings-group", true);

    assertThat(setting.available()).isTrue();
    assertThat(setting.enabled()).isTrue();
    assertThat(setting.collectionStartedAt()).isNotNull();
    assertThat(
            store.findEnabledConversationId(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;settings-group"))
        .isPresent();
    ArgumentCaptor<ApiV1MessageTextPostRequest> requestCaptor =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);
    verify(bbHttpClientWrapper).sendTextDirect(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getChatGuid()).isEqualTo("iMessage;+;settings-group");
    assertThat(requestCaptor.getValue().getMessage())
        .containsIgnoringCase("from this point forward")
        .containsIgnoringCase("earlier messages");
  }

  @Test
  void failedEnableNoticeRestoresDisabledState() {
    String accountId = createAccount("rollback@example.com");
    store.upsertConversation(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;settings-rollback",
        true,
        "Rollback",
        NOW);
    when(bbHttpClientWrapper.sendTextDirect(any())).thenReturn(false);

    assertThat(
            settingsService
                .tryUpdateGroupMemory(accountId, "iMessage;+;settings-rollback", true)
                .success())
        .isFalse();

    assertThat(
            store.findEnabledConversationId(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;settings-rollback"))
        .isEmpty();
  }

  @Test
  void directConversationReportsGroupMemoryUnavailable() {
    store.upsertConversation(
        IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;-;settings-direct", false, null, NOW);

    GroupMemorySetting setting = settingsService.getGroupMemory("iMessage;-;settings-direct");

    assertThat(setting.available()).isFalse();
    assertThat(setting.enabled()).isFalse();
  }

  private String createAccount(String identifier) {
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, identifier)
        .orElseThrow()
        .account()
        .getAccountId();
  }
}
