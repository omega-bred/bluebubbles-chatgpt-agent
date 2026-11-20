package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.client.DuplicateWorkflowException;
import io.breland.bbagent.server.agent.BBMessageAgent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Slf4j
public class InboundMessageWorker {
  private static final int PARALLELISM = 2;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore capacity = new Semaphore(PARALLELISM);
  private final InboundMessageInbox inbox;
  private final BBMessageAgent agent;
  private final CadenceWorkflowLauncher launcher;

  public InboundMessageWorker(
      InboundMessageInbox inbox, BBMessageAgent agent, CadenceWorkflowLauncher launcher) {
    this.inbox = inbox;
    this.agent = agent;
    this.launcher = launcher;
  }

  @Scheduled(fixedDelayString = "PT0.25S")
  public void poll() {
    for (int remaining = capacity.availablePermits();
        remaining > 0 && capacity.tryAcquire();
        remaining--) {
      executor.execute(
          () -> {
            try {
              processOne();
            } catch (RuntimeException error) {
              log.warn("Inbound inbox polling failed: {}", error.getClass().getSimpleName());
            } finally {
              capacity.release();
            }
          });
    }
  }

  void processOne() {
    var claim = inbox.claimNext(Instant.now());
    if (claim == null) return;
    try {
      inbox.prepare(claim, agent::prepareInboxMessage);
      inbox.dispatch(
          claim,
          request -> {
            try {
              launcher.startWorkflow(request);
            } catch (DuplicateWorkflowException alreadyDispatched) {
              log.debug("Cadence already accepted inbox item {}", claim.id());
            }
          });
    } catch (RuntimeException error) {
      log.warn(
          "Inbound inbox item {} attempt {} failed: {}",
          claim.id(),
          claim.attempt(),
          error.getClass().getSimpleName());
      inbox.retry(claim);
    }
  }

  @Scheduled(cron = "0 10 4 * * *", zone = "UTC")
  public void cleanUp() {
    inbox.deleteCompletedBefore(Instant.now().minus(Duration.ofDays(30)));
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
