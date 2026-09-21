package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.HindsightMemoryStore.Document;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MemoryProjectionWorker {
  private final HindsightMemoryStore store;
  private final HindsightClient client;
  private final @Nullable OperationalMetricsService metrics;
  private final boolean groupsEnabled;
  private final String workerId = UUID.randomUUID().toString();

  public MemoryProjectionWorker(
      HindsightMemoryStore store,
      HindsightClient client,
      @Nullable OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.enabled:false}") boolean groupsEnabled) {
    this.store = store;
    this.client = client;
    this.metrics = metrics;
    this.groupsEnabled = groupsEnabled;
  }

  @Scheduled(
      fixedDelayString = "${bbagent.memory.projection.worker-poll-interval:PT5S}",
      initialDelayString = "${bbagent.memory.projection.worker-initial-delay:PT15S}")
  public void processDueProjections() {
    if (!client.isConfigured()) return;
    // Claim immediately before each operation, rather than leasing a batch that can expire in
    // flight.
    for (int i = 0; i < 25; i++) {
      var work = store.claim(workerId, Instant.now(), 1, groupsEnabled);
      if (work.isEmpty()) break;
      process(work.getFirst());
    }
  }

  void process(Document doc) {
    Instant start = Instant.now();
    String state = "FAILED";
    String error = null;
    try {
      String status = client.operationStatus(doc.bankId(), doc.operationId());
      if (status.equals("unavailable")) error = "hindsight_status_failed";
      else if (status.equals("pending") || status.equals("processing")) {
        state = "PROCESSING";
        if (doc.createdAt().isBefore(start.minus(Duration.ofDays(1)))) {
          state = "EXHAUSTED";
          error = "hindsight_operation_stalled";
        }
      } else if (doc.operation().equals("DELETE")) {
        if (client.deleteDocument(doc.bankId(), doc.documentId())) state = "SUCCEEDED";
        else error = "hindsight_delete_failed";
      } else if (status.equals("completed")) state = "SUCCEEDED";
      else if (status.equals("failed") || status.equals("cancelled")) {
        state = "EXHAUSTED";
        error = "hindsight_operation_failed";
      } else if (status.equals("not_found")) {
        var bank = store.bank(doc.bankId()).orElseThrow();
        if (client.configureBank(doc.bankId(), bank.group())
            && client.retain(
                doc.bankId(),
                doc.documentId(),
                doc.operationId(),
                doc.text(),
                doc.hash(),
                doc.occurredAt())) state = "PROCESSING";
        else error = "hindsight_write_failed";
      } else error = "hindsight_unknown_status";
    } catch (RuntimeException e) {
      error = "hindsight_worker_failed";
    }
    store.finish(doc, workerId, state, error, Instant.now());
    if (metrics != null && !state.equals("PROCESSING"))
      metrics.recordMemoryProjection(
          doc.operation(),
          state.equals("SUCCEEDED"),
          error,
          Duration.between(start, Instant.now()));
  }
}
