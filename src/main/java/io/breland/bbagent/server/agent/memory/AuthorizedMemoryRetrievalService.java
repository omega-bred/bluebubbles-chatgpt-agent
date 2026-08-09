package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedMemory;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectedArtifact;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class AuthorizedMemoryRetrievalService {
  private final ConversationMemoryStore store;
  private final Mem0Client mem0Client;
  private final Clock clock;

  public AuthorizedMemoryRetrievalService(
      ConversationMemoryStore store, Mem0Client mem0Client, @Nullable Clock clock) {
    this.store = store;
    this.mem0Client = mem0Client;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public List<AuthorizedMemory> search(ToolContext context, String query) {
    if (context == null || StringUtils.isBlank(query)) {
      return List.of();
    }
    Optional<String> accountIdValue = context.canonicalAccountId();
    if (accountIdValue.isEmpty()) {
      return List.of();
    }
    String accountId = accountIdValue.get();
    String canonicalScope = "account:" + accountId;
    List<AuthorizedMemory> authorized = new ArrayList<>();
    for (Mem0Client.StoredMemory hit : mem0Client.searchMemories(canonicalScope, query)) {
      if (hit == null || StringUtils.isBlank(hit.memoryId())) {
        continue;
      }
      Optional<ProjectedArtifact> projected =
          store.findProjectedArtifact(hit.memoryId(), accountId);
      if (projected.isPresent()) {
        if (isAuthorized(projected.get(), accountId, clock.instant())) {
          ProjectedArtifact artifact = projected.get();
          authorized.add(
              new AuthorizedMemory(
                  artifact.artifactId(),
                  artifact.text(),
                  StringUtils.defaultIfBlank(artifact.groupDisplayName(), "Group conversation"),
                  artifact.occurredAt(),
                  true,
                  null));
        }
        continue;
      }
      if (store.ownsCanonicalMemory(canonicalScope, hit.memoryId())
          && StringUtils.isNotBlank(hit.memory())) {
        authorized.add(new AuthorizedMemory(null, hit.memory(), null, null, false, hit.memoryId()));
      }
    }
    return List.copyOf(authorized);
  }

  private boolean isAuthorized(ProjectedArtifact artifact, String accountId, Instant now) {
    return artifact.status() == ArtifactStatus.CONFIRMED
        && artifact.sensitivity() == ArtifactSensitivity.NORMAL
        && artifact.confidence() >= 0.85
        && store.isInArtifactAudience(artifact.artifactId(), accountId)
        && (artifact.expiresAt() == null || artifact.expiresAt().isAfter(now));
  }
}
