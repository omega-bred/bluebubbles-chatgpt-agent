package io.breland.bbagent.server.agent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PostgresAgentSettingsStoreTest {

  @Autowired private PostgresAgentSettingsStore store;
  @Autowired private AgentAccountResolver accountResolver;

  @Test
  void assistantResponsivenessCrud() {
    String chatGuid = "iMessage;+;chat123";
    assertTrue(store.findAssistantResponsiveness(chatGuid).isEmpty());

    store.saveAssistantResponsiveness(
        chatGuid, BBMessageAgent.AssistantResponsiveness.MORE_RESPONSIVE);
    Optional<BBMessageAgent.AssistantResponsiveness> loaded =
        store.findAssistantResponsiveness(chatGuid);
    assertTrue(loaded.isPresent());
    assertEquals(BBMessageAgent.AssistantResponsiveness.MORE_RESPONSIVE, loaded.get());

    store.saveAssistantResponsiveness(
        chatGuid, BBMessageAgent.AssistantResponsiveness.LESS_RESPONSIVE);
    assertEquals(
        BBMessageAgent.AssistantResponsiveness.LESS_RESPONSIVE,
        store.findAssistantResponsiveness(chatGuid).orElse(null));

    store.deleteAssistantResponsiveness(chatGuid);
    assertTrue(store.findAssistantResponsiveness(chatGuid).isEmpty());
  }

  @Test
  void globalNameCrud() {
    String accountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "user@example.com")
            .orElseThrow()
            .account()
            .getAccountId();
    assertTrue(store.findGlobalName(accountId).isEmpty());

    store.saveGlobalName(accountId, "Jordan");
    assertEquals("Jordan", store.findGlobalName(accountId).orElse(null));

    store.saveGlobalName(accountId, "JD");
    assertEquals("JD", store.findGlobalName(accountId).orElse(null));

    store.deleteGlobalName(accountId);
    assertFalse(store.findGlobalName(accountId).isPresent());
  }
}
