# Proactive Catchup Wiring Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unused dependency, outcome, and return-value plumbing from group memory without changing scheduling, persistence, delivery, or API behavior.

**Architecture:** Keep the current service/store boundary intact. Narrow the constructor signatures to dependencies the service actually uses, narrow the store completion method to the three values its SQL update consumes, and make journal recording a side-effect-only contract because no caller consumes its old boolean result.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, Mockito, Gradle, Nix

## Global Constraints

- Preserve behavior.
- Keep changes small, reviewable, and easy to revert.
- Do not change REST or OpenAPI contracts.
- Run project tooling through `nix develop` and format with `./gradlew spotlessApply`.

---

### Task 1: Remove unused proactive-catchup wiring

**Files:**
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStore.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupServiceTest.java`

**Interfaces:**
- Consumes: `ConversationMemoryStore.completeCatchupPreferenceClaim(CatchupPreferenceClaim, Instant, Instant, String)` and both existing `ProactiveCatchupService` constructors.
- Produces: `ConversationMemoryStore.completeCatchupPreferenceClaim(CatchupPreferenceClaim, Instant, Instant)` and constructors without `ObjectMapper`.

- [x] **Step 1: Capture the focused baseline**

Run:

```bash
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ProactiveCatchupServiceTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationMemoryStoreTest \
  --rerun-tasks
```

Expected: both test classes pass before the refactor.

- [x] **Step 2: Remove the unused constructor dependency**

Delete the `ObjectMapper` import and parameter from both `ProactiveCatchupService` constructors and their delegation, then update the test constructor call. The resulting constructor prefix is:

```java
public ProactiveCatchupService(
    ConversationMemoryStore store,
    ConversationDigestService digestService,
    BBHttpClientWrapper blueBubbles,
    MessageResponseRateLimitService responseQuota,
    @Nullable OperationalMetricsService operationalMetricsService,
    @Nullable Clock clock,
    ...)
```

- [x] **Step 3: Remove the unused outcome data flow**

Change the store method to:

```java
public void completeCatchupPreferenceClaim(
    CatchupPreferenceClaim claim, Instant nextDeliveryAt, Instant completedAt)
```

Update every production and test call. Change `completeClaim` to accept only `(claim, now, delay)` and delete outcome-only strings such as `quiet_hours`, `no_changes`, `deduplicated`, and `quota_exhausted` from this completion path.

- [x] **Step 4: Prove removed symbols are gone**

Run:

```bash
rg -n 'ObjectMapper objectMapper|completeCatchupPreferenceClaim\([^;]*,"|completeClaim\([^;]*,"|quiet_hours|no_changes|no_direct_route|deduplicated|quota_exhausted|unknown_send' \
  src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStore.java \
  src/test/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupServiceTest.java
```

Expected: no matches.

- [x] **Step 5: Format and run focused verification**

Run:

```bash
CI=true nix develop --command ./gradlew spotlessApply compileTestJava test \
  --tests io.breland.bbagent.server.agent.memory.ProactiveCatchupServiceTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationMemoryStoreTest \
  spotlessCheck --rerun-tasks
```

Expected: Gradle exits successfully with no failed tests.

- [x] **Step 6: Review the final scope**

Run:

```bash
git diff --check
git diff --stat
git diff -- src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStore.java \
  src/test/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupServiceTest.java
```

Expected: only the unused wiring is removed and no behavior-bearing SQL or catchup branching changes.

- [ ] **Step 7: Publish**

Stage only the plan and four scoped Java files, commit with `Simplify group memory contracts`, push `codex/simplify-proactive-catchup-wiring`, and open a draft PR against `main` describing the behavior-preserving reduction and validation.

### Task 2: Narrow the journal recording contract

**Files:**
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationJournalService.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationJournalServiceTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/cadence/CadenceIncomingMessageHandlerTest.java`

**Interfaces:**
- Consumes: `boolean ConversationJournalService.recordEligibleMessage(IncomingMessage)` whose result has zero callers.
- Produces: `void ConversationJournalService.recordEligibleMessage(IncomingMessage)` with unchanged side effects and early-exit conditions.

- [x] **Step 1: Capture the focused baseline**

Run:

```bash
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ConversationJournalServiceTest \
  --tests io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandlerTest \
  --rerun-tasks
```

Expected: both test classes pass before the signature change.

- [x] **Step 2: Remove the unused return value**

Change the method contract to:

```java
public void recordEligibleMessage(IncomingMessage message)
```

Replace each `return false` or `return true` with `return`. Keep every condition, store call, hash operation, and extraction schedule unchanged.

- [x] **Step 3: Verify callers and focused behavior**

Use IntelliJ inspection and whole-repository search to confirm that no caller consumed the old result, then run:

```bash
CI=true nix develop --command ./gradlew spotlessApply compileTestJava test \
  --tests io.breland.bbagent.server.agent.memory.ConversationJournalServiceTest \
  --tests io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandlerTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationDigestServiceTest \
  --tests io.breland.bbagent.server.agent.memory.ProactiveCatchupServiceTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationMemoryStoreTest \
  spotlessCheck --rerun-tasks
```

Expected: Gradle exits successfully, and IntelliJ no longer reports that the method's return value is unused.
