package io.breland.bbagent.server.agent.memory;

import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.memory.HindsightMemoryStore.*;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemoryProjectionWorkerTest {
  private final HindsightMemoryStore store = mock(HindsightMemoryStore.class);
  private final HindsightClient client = mock(HindsightClient.class);
  private final MemoryProjectionWorker worker =
      new MemoryProjectionWorker(store, client, null, true);

  private Document doc(String operation) {
    return new Document(
        "doc",
        "bank",
        null,
        "Likes tea",
        "hash",
        Instant.now(),
        "stable-op",
        operation,
        "PENDING",
        0,
        Instant.now());
  }

  @Test
  void disabledProviderDoesNotClaimWork() {
    worker.processDueProjections();
    verifyNoInteractions(store);
  }

  @Test
  void retryAfterLostAcknowledgementReusesOperationAndDocument() {
    Document doc = doc("UPSERT");
    when(client.operationStatus("bank", "stable-op")).thenReturn("not_found");
    when(store.bank("bank")).thenReturn(Optional.of(new Bank("bank", "account", null)));
    when(client.configureBank("bank", false)).thenReturn(true);
    when(client.retain("bank", "doc", "stable-op", "Likes tea", "hash", doc.occurredAt()))
        .thenReturn(false, true);
    worker.process(doc);
    worker.process(doc);
    verify(client, times(2))
        .retain("bank", "doc", "stable-op", "Likes tea", "hash", doc.occurredAt());
    verify(store).finish(eq(doc), anyString(), eq("PROCESSING"), isNull(), any());
  }

  @Test
  void completedZeroFactOperationIsSuccessfulWithoutAnotherWrite() {
    Document doc = doc("UPSERT");
    when(client.operationStatus("bank", "stable-op")).thenReturn("completed");
    worker.process(doc);
    verify(store).finish(eq(doc), anyString(), eq("SUCCEEDED"), isNull(), any());
    verify(client, never())
        .retain(anyString(), anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void deletionWaitsForInFlightRetainThenDeletesDocument() {
    Document doc = doc("DELETE");
    when(client.operationStatus("bank", "stable-op")).thenReturn("processing", "completed");
    worker.process(doc);
    verify(client, never()).deleteDocument(anyString(), anyString());
    when(client.deleteDocument("bank", "doc")).thenReturn(true);
    worker.process(doc);
    verify(client).deleteDocument("bank", "doc");
    verify(store).finish(eq(doc), anyString(), eq("SUCCEEDED"), isNull(), any());
  }

  @Test
  void terminalProviderFailureDoesNotGenerateNewOperation() {
    Document doc = doc("UPSERT");
    when(client.operationStatus("bank", "stable-op")).thenReturn("failed");
    worker.process(doc);
    verify(store)
        .finish(eq(doc), anyString(), eq("EXHAUSTED"), eq("hindsight_operation_failed"), any());
    verify(client, never())
        .retain(anyString(), anyString(), anyString(), anyString(), anyString(), any());
  }
}
