package io.breland.bbagent.server.agent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.BBMessageAgent;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PostgresAgentSettingsStoreTest {

  @Autowired private PostgresAgentSettingsStore store;

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
    String sender = "user@example.com";
    assertTrue(store.findGlobalName(sender).isEmpty());

    store.saveGlobalName(sender, "Jordan");
    assertEquals("Jordan", store.findGlobalName(sender).orElse(null));

    store.saveGlobalName(sender, "JD");
    assertEquals("JD", store.findGlobalName(sender).orElse(null));

    store.deleteGlobalName(sender);
    assertFalse(store.findGlobalName(sender).isPresent());
  }
}
