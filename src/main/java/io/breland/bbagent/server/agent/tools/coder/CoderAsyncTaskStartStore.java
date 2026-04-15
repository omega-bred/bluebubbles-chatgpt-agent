package io.breland.bbagent.server.agent.tools.coder;

import io.breland.bbagent.server.agent.persistence.coder.CoderAsyncTaskStartEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderAsyncTaskStartRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoderAsyncTaskStartStore {
  static final String STATUS_STARTING = "STARTING";
  static final String STATUS_STARTED = "STARTED";
  static final String STATUS_FAILED = "FAILED";

  private final CoderAsyncTaskStartRepository repository;

  public CoderAsyncTaskStartStore(CoderAsyncTaskStartRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Reservation reserve(
      String idempotencyKey,
      String accountBase,
      String chatGuid,
      String messageGuid,
      String taskHash,
      String task) {
    return repository
        .findLockedByIdempotencyKey(idempotencyKey)
        .map(Reservation::existing)
        .orElseGet(
            () ->
                Reservation.newStart(
                    insertReservation(
                        idempotencyKey, accountBase, chatGuid, messageGuid, taskHash, task)));
  }

  @Transactional
  public void markStarted(String idempotencyKey, String responseJson) {
    CoderAsyncTaskStartEntity entity =
        repository
            .findLockedByIdempotencyKey(idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("missing Coder task start reservation"));
    entity.setStatus(STATUS_STARTED);
    entity.setResponseJson(responseJson);
    entity.setErrorMessage(null);
    entity.setUpdatedAt(Instant.now());
    repository.save(entity);
  }

  @Transactional
  public void markFailed(String idempotencyKey, String errorMessage) {
    CoderAsyncTaskStartEntity entity =
        repository.findLockedByIdempotencyKey(idempotencyKey).orElse(null);
    if (entity == null) {
      return;
    }
    entity.setStatus(STATUS_FAILED);
    entity.setErrorMessage(errorMessage);
    entity.setUpdatedAt(Instant.now());
    repository.save(entity);
  }

  private CoderAsyncTaskStartEntity insertReservation(
      String idempotencyKey,
      String accountBase,
      String chatGuid,
      String messageGuid,
      String taskHash,
      String task) {
    Instant now = Instant.now();
    CoderAsyncTaskStartEntity entity =
        new CoderAsyncTaskStartEntity(
            idempotencyKey,
            accountBase,
            chatGuid,
            messageGuid,
            taskHash,
            task,
            STATUS_STARTING,
            now,
            now);
    repository.saveAndFlush(entity);
    return entity;
  }

  public record Reservation(boolean shouldStart, CoderAsyncTaskStartEntity entity) {
    static Reservation newStart(CoderAsyncTaskStartEntity entity) {
      return new Reservation(true, entity);
    }

    static Reservation existing(CoderAsyncTaskStartEntity entity) {
      return new Reservation(false, entity);
    }
  }
}
