package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.*;
import io.breland.bbagent.server.agent.memory.HindsightMemoryStore.Bank;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthorizedMemoryRetrievalService {
  private final ConversationMemoryStore store;
  private final HindsightMemoryStore memories;
  private final ConversationMembershipService memberships;
  private final HindsightClient client;
  private final double minimumConfidence;
  private final boolean groupsEnabled;
  private final int maxBanks;

  public AuthorizedMemoryRetrievalService(
      ConversationMemoryStore store,
      HindsightMemoryStore memories,
      ConversationMembershipService memberships,
      HindsightClient client,
      @Value("${bbagent.memory.group.minimum-confidence:0.85}") double minimumConfidence,
      @Value("${bbagent.memory.group.enabled:false}") boolean groupsEnabled,
      @Value("${hindsight.recall-max-banks:12}") int maxBanks) {
    this.store = store;
    this.memories = memories;
    this.memberships = memberships;
    this.client = client;
    this.minimumConfidence = minimumConfidence;
    this.groupsEnabled = groupsEnabled;
    this.maxBanks = Math.clamp(maxBanks, 1, 32);
  }

  public List<AuthorizedMemory> search(ToolContext context, String query) {
    if (!client.isConfigured()
        || context == null
        || context.message() == null
        || StringUtils.isBlank(query)) return List.of();
    var account = context.canonicalAccountId();
    if (account.isEmpty()) return List.of();
    String conversation = null;
    Set<String> recipients = Set.of();
    if (context.message().isGroup()) {
      if (!groupsEnabled) return List.of();
      conversation =
          store
              .findEnabledConversationId(
                  context.message().transportOrDefault(), context.message().chatGuid())
              .orElse(null);
      if (conversation == null) return List.of();
      try {
        recipients = memberships.refreshGroupMembership(conversation);
      } catch (ConversationMembershipService.MembershipRefreshException e) {
        return List.of();
      }
      if (!recipients.contains(account.get())) return List.of();
    }
    List<Bank> banks =
        memories.readableBanks(account.get(), conversation, recipients, groupsEnabled, maxBanks);
    List<AuthorizedMemory> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    // Bound fan-out and wall-clock time, while keeping each recall in a separately authorized bank.
    ExecutorService executor =
        Executors.newFixedThreadPool(
            Math.min(4, Math.max(1, banks.size())), Thread.ofVirtual().factory());
    List<Future<List<HindsightClient.RecalledMemory>>> futures = new ArrayList<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    try {
      for (Bank bank : banks)
        futures.add(executor.submit(() -> client.recall(bank.bankId(), query)));
      int characters = 0;
      for (int i = 0; i < banks.size(); i++) {
        List<HindsightClient.RecalledMemory> hits;
        try {
          hits =
              futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException | TimeoutException e) {
          continue;
        }
        Bank bank = banks.get(i);
        for (var hit : hits) {
          var doc = memories.document(hit.documentId()).orElse(null);
          if (doc == null
              || !doc.bankId().equals(bank.bankId())
              || !doc.state().equals("SUCCEEDED")
              || !doc.operation().equals("UPSERT")
              || !doc.hash().equals(hit.contentHash())
              || seen.contains(doc.documentId())) continue;
          AuthorizedMemory memory;
          if (doc.artifactId() != null) {
            var artifact = store.findProjectionArtifact(doc.artifactId()).orElse(null);
            if (artifact == null
                || artifact.status() != ArtifactStatus.CONFIRMED
                || artifact.sensitivity() != ArtifactSensitivity.NORMAL
                || artifact.confidence() < minimumConfidence
                || (artifact.expiresAt() != null && !artifact.expiresAt().isAfter(Instant.now()))
                || !store.isInArtifactAudience(artifact.artifactId(), account.get())) continue;
            if (recipients.stream()
                .anyMatch(id -> !store.isInArtifactAudience(artifact.artifactId(), id))) continue;
            memory =
                new AuthorizedMemory(
                    artifact.artifactId(),
                    artifact.text(),
                    StringUtils.defaultIfBlank(artifact.groupDisplayName(), "Group conversation"),
                    artifact.occurredAt(),
                    true,
                    null);
          } else {
            String groupName =
                bank.group()
                    ? store
                        .findConversation(bank.conversationId())
                        .map(ConversationRecord::displayName)
                        .orElse("Group conversation")
                    : null;
            memory =
                new AuthorizedMemory(
                    null, doc.text(), groupName, doc.occurredAt(), bank.group(), doc.documentId());
          }
          if (characters + memory.memory().length() > 16000) continue;
          seen.add(doc.documentId());
          result.add(memory);
          characters += memory.memory().length();
          if (result.size() == 24) return List.copyOf(result);
        }
      }
    } finally {
      futures.forEach(f -> f.cancel(true));
      executor.shutdownNow();
    }
    return List.copyOf(result);
  }
}
