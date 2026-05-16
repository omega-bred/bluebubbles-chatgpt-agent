package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.persistence.AccountIdentityAliasEntity;
import io.breland.bbagent.server.agent.persistence.AccountIdentityAliasRepository;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountIdentityAliasService {
  private final AccountIdentityAliasRepository repository;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public AccountIdentityAliasService(
      AccountIdentityAliasRepository repository,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper) {
    this.repository = repository;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public void recordMessageAliases(IncomingMessage message) {
    if (message == null
        || !message.isBlueBubblesTransport()
        || message.sender() == null
        || message.sender().isBlank()) {
      return;
    }
    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    aliases.add(message.sender());
    if (bbHttpClientWrapper != null) {
      try {
        aliases.addAll(bbHttpClientWrapper.getContactAddressesFor(message.sender()));
      } catch (Exception ignored) {
        // BlueBubbles contact lookup is a best-effort alias hint.
      }
    }
    recordAliases(message.transportOrDefault(), aliases, message.sender());
  }

  @Transactional(readOnly = true)
  public List<String> accountBaseCandidates(String accountBase) {
    if (accountBase == null || accountBase.isBlank()) {
      return List.of();
    }
    GcalScopedBase scoped = GcalScopedBase.parse(accountBase);
    if (scoped != null) {
      return simpleAccountBaseCandidates(scoped.identifier()).stream()
          .map(scoped::withIdentifier)
          .toList();
    }
    return simpleAccountBaseCandidates(accountBase);
  }

  @Transactional(readOnly = true)
  public String preferredAccountBaseForWrite(String accountBase) {
    if (accountBase == null || accountBase.isBlank()) {
      return accountBase;
    }
    GcalScopedBase scoped = GcalScopedBase.parse(accountBase);
    if (scoped != null) {
      return scoped.withIdentifier(preferredSimpleAccountBaseForWrite(scoped.identifier()));
    }
    return preferredSimpleAccountBaseForWrite(accountBase);
  }

  @Transactional
  public void recordAliases(String transport, Collection<String> rawAliases, String accountBase) {
    if (StringUtils.isBlank(accountBase) || rawAliases == null || rawAliases.isEmpty()) {
      return;
    }
    String transportKey =
        StringUtils.defaultIfBlank(transport, IncomingMessage.TRANSPORT_BLUEBUBBLES);
    Map<String, AliasInput> aliases = new LinkedHashMap<>();
    rawAliases.stream()
        .filter(StringUtils::isNotBlank)
        .forEach(
            rawAlias ->
                AgentAccountIdentifiers.normalize(transportKey, rawAlias)
                    .ifPresent(
                        normalized ->
                            aliases.putIfAbsent(
                                normalized.aliasKey(),
                                new AliasInput(rawAlias.trim(), normalized))));
    if (aliases.isEmpty()) {
      return;
    }

    List<AccountIdentityAliasEntity> existing =
        repository.findAllByAliasKeyIn(aliases.keySet()).stream()
            .sorted(Comparator.comparing(AccountIdentityAliasEntity::getUpdatedAt).reversed())
            .toList();
    String canonicalAccountBase =
        existing.stream()
            .map(AccountIdentityAliasEntity::getAccountBase)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(accountBase);

    LinkedHashSet<String> previousAccountBases = new LinkedHashSet<>();
    previousAccountBases.add(canonicalAccountBase);
    existing.stream()
        .map(AccountIdentityAliasEntity::getAccountBase)
        .filter(StringUtils::isNotBlank)
        .forEach(previousAccountBases::add);
    if (!previousAccountBases.isEmpty()) {
      repository.findAllByAccountBaseIn(previousAccountBases).stream()
          .filter(entity -> !canonicalAccountBase.equals(entity.getAccountBase()))
          .forEach(
              entity -> {
                entity.setAccountBase(canonicalAccountBase);
                entity.setUpdatedAt(Instant.now());
                repository.save(entity);
              });
    }

    Instant now = Instant.now();
    for (AliasInput input : aliases.values()) {
      AccountIdentityAliasEntity entity =
          repository
              .findById(input.normalized().aliasKey())
              .orElseGet(
                  () ->
                      new AccountIdentityAliasEntity(
                          input.normalized().aliasKey(),
                          canonicalAccountBase,
                          transportKey,
                          input.identifier(),
                          input.normalized().type(),
                          input.normalized().value(),
                          now,
                          now));
      entity.setAccountBase(canonicalAccountBase);
      entity.setTransport(transportKey);
      entity.setIdentifier(input.identifier());
      entity.setIdentifierType(input.normalized().type());
      entity.setNormalizedIdentifier(input.normalized().value());
      entity.setUpdatedAt(now);
      repository.save(entity);
    }
  }

  private List<String> simpleAccountBaseCandidates(String accountBase) {
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    candidates.add(accountBase);
    AgentAccountIdentifiers.normalize(IncomingMessage.TRANSPORT_BLUEBUBBLES, accountBase)
        .ifPresent(
            normalized -> {
              List<AccountIdentityAliasEntity> direct =
                  repository.findAllByAliasKeyIn(List.of(normalized.aliasKey()));
              direct.stream()
                  .map(AccountIdentityAliasEntity::getAccountBase)
                  .filter(StringUtils::isNotBlank)
                  .forEach(candidates::add);
            });
    List<AccountIdentityAliasEntity> groupRows = repository.findAllByAccountBaseIn(candidates);
    groupRows.stream()
        .sorted(Comparator.comparing(AccountIdentityAliasEntity::getUpdatedAt).reversed())
        .forEach(
            entity -> {
              candidates.add(entity.getAccountBase());
              candidates.add(entity.getIdentifier());
            });
    return List.copyOf(candidates);
  }

  private String preferredSimpleAccountBaseForWrite(String accountBase) {
    return AgentAccountIdentifiers.normalize(IncomingMessage.TRANSPORT_BLUEBUBBLES, accountBase)
        .flatMap(
            normalized ->
                repository.findAllByAliasKeyIn(List.of(normalized.aliasKey())).stream()
                    .map(AccountIdentityAliasEntity::getAccountBase)
                    .filter(StringUtils::isNotBlank)
                    .findFirst())
        .orElse(accountBase);
  }

  private record AliasInput(
      String identifier, AgentAccountIdentifiers.NormalizedIdentifier normalized) {}

  private record GcalScopedBase(String prefix, String identifier) {
    static GcalScopedBase parse(String accountBase) {
      int index = accountBase == null ? -1 : accountBase.lastIndexOf('|');
      if (index <= 0 || index >= accountBase.length() - 1) {
        return null;
      }
      return new GcalScopedBase(
          accountBase.substring(0, index + 1), accountBase.substring(index + 1));
    }

    String withIdentifier(String value) {
      return prefix + value;
    }
  }
}
