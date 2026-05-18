package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CoderAsyncTaskStartStoreTest {
  @Autowired private CoderAsyncTaskStartStore store;
  @Autowired private AgentAccountResolver accountResolver;

  @Test
  void reservesTaskStartOncePerIdempotencyKey() {
    String key = "coder-task-test-" + UUID.randomUUID();
    String accountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "+18035551212")
            .orElseThrow()
            .account()
            .getAccountId();

    CoderAsyncTaskStartStore.Reservation first =
        store.reserve(
            key, accountId, "any;-;+18035551212", "msg-1", "task-hash", "summarize commits");
    assertTrue(first.shouldStart());
    assertEquals(CoderAsyncTaskStartStore.STATUS_STARTING, first.entity().getStatus());

    store.markStarted(key, "{\"started\":true}");

    CoderAsyncTaskStartStore.Reservation second =
        store.reserve(
            key, accountId, "any;-;+18035551212", "msg-1", "task-hash", "summarize commits");
    assertFalse(second.shouldStart());
    assertEquals(CoderAsyncTaskStartStore.STATUS_STARTED, second.entity().getStatus());
    assertEquals("{\"started\":true}", second.entity().getResponseJson());
  }
}
