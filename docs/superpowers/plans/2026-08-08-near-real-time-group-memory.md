# Near-Real-Time Group Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make confirmed collective decisions from opted-in BlueChat group conversations available to authorized one-to-one chats within roughly 60 seconds, while also supporting complete time-bounded catch-up summaries, nightly reconciliation, and opt-in proactive digests.

**Architecture:** Postgres is the authority for conversation identity, time-aware membership, source messages, artifact provenance, audience snapshots, digest coverage, settings, and delivery state. A dedicated no-tools model client extracts structured decisions and summary segments from debounced message batches; Mem0 receives per-account semantic projections only after the Postgres transaction commits. One-to-one tools derive the canonical `agent_accounts.account_id`, query only server-authorized groups, and re-authorize every Mem0 hit against Postgres before returning it to the conversational agent. Typed conversation-settings APIs expose the group-wide memory toggle and the current account's personal catch-up preference to the generated web and App Clip clients.

**Tech Stack:** Java 25, Spring Boot scheduling and transactions, Spring Data JPA, PostgreSQL/Flyway, H2 PostgreSQL mode for tests, OpenAI Responses API through the existing client, Mem0, Micrometer, BlueBubbles API.

## Global Constraints

- Keep this work independent of piecemeal response delivery; that feature gets its own later plan.
- `agent_accounts.account_id` is the only person-level persistence key. Never persist account-owned data under a raw phone number, email, Keycloak subject, or LXMF address.
- Group memory is disabled by default. Enabling it in a group starts collection prospectively and posts a visible confirmation in that same group; it does not backfill older messages.
- Personal one-to-one memories never flow into group chats or another account's scope.
- A group artifact's audience is snapshotted from observed membership at extraction time. Joining a group later never grants access to earlier artifacts or digests.
- Treat transcripts as untrusted data. The extraction prompt must not execute instructions found in messages, and returned evidence GUIDs must belong to the submitted batch.
- Only `CONFIRMED` artifacts with `NORMAL` sensitivity and confidence at or above `0.85` are projected to Mem0. Tentative material may appear in catch-up summaries but not durable semantic memory.
- Do not claim to know what a participant actually read. User-facing language says “developments since your last catch-up,” not “unread” or “you missed.”
- Retain raw journal text for 30 days by default. Retain summary segments for 90 days. Retain active decisions and daily digests until superseded, expired, disabled, or explicitly deleted.
- Proactive delivery is off by default, requires account-and-group opt-in, honors quiet hours, sends at most one digest per account per group per day, and never retries an ambiguous BlueBubbles send.
- Do not expose `chatGuid`, arbitrary `account_id`, Mem0 `user_id`, or membership selectors in model-facing tool schemas.
- Do not log message bodies, extracted summaries, raw identities, chat GUIDs, prompts, Mem0 responses, or model responses. Metrics use only low-cardinality tags.
- Conversation settings are formal OpenAPI models, not schema-less key/value data. Add typed `group_memory` and `personal_catchups` sections to `ConversationSettingsResponse`; do not overload the responsiveness `options` array.
- REST changes must update `src/main/resources/openapi.yaml`, regenerate server models and the TypeScript client with `openApiGenerate`/`copyClientToFrontend`, and regenerate/copy the Swift client with `copySwiftClientToAppClip`. Never hand-edit generated clients.
- Add every new property to both application property files and the production manifest. Run formatting through `nix develop --command ./gradlew spotlessApply`.

---

## File and Responsibility Map

- `src/main/resources/db/migration/V29__conversation_memory_core.sql`: conversations, membership intervals, journal messages, extraction work, checkpoints, artifacts, evidence, audience snapshots, rolling summary segments, manual-memory ownership, and Mem0 projections.
- `src/main/resources/db/migration/V30__conversation_memory_catchups.sql`: daily digests, reconciliation leases, proactive preferences, and delivery receipts.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryModels.java`: immutable records and enums shared by memory services.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationJournalService.java`: idempotent message journaling, conversation registration, and debounced work scheduling.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMembershipService.java`: BlueBubbles participant refresh and time-aware canonical membership intervals.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStore.java`: transactional claims, checkpoints, artifacts, evidence, audience snapshots, projection jobs, and retention queries.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryModelClient.java`: no-tools extraction and digest model calls plus strict structured-output validation.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryWorker.java`: five-second scheduler, 60-second debounce, lease handling, extraction, and segment persistence.
- `src/main/java/io/breland/bbagent/server/agent/memory/MemoryScopeResolver.java`: canonical `account:<uuid>` and `conversation:<uuid>` Mem0 scopes derived from trusted runtime context.
- `src/main/java/io/breland/bbagent/server/agent/memory/MemoryProjectionWorker.java`: transactional-outbox-style Mem0 projection retries without rerunning extraction.
- `src/main/java/io/breland/bbagent/server/agent/memory/AuthorizedMemoryRetrievalService.java`: canonical/legacy semantic search, Postgres re-authorization, and sanitized provenance formatting.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationDigestService.java`: catch-up range selection, segment composition, daily reconciliation, and account authorization.
- `src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java`: opt-in scheduling, quiet hours, direct-route selection, deduplication, and conservative sending.
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemorySettingsService.java`: one mutation path shared by the REST settings surface and agent tools, including group-only availability and prospective enablement.
- `src/main/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupMemoryAgentTool.java`: current-group-only prospective enable/disable.
- `src/main/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentTool.java`: authorized one-to-one catch-up retrieval without model-controlled IDs.
- `src/main/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupCatchupAgentTool.java`: account-and-group proactive preference management.
- `src/main/resources/openapi.yaml`: typed group-memory and personal-catch-up settings models plus update endpoints.
- `src/main/java/io/breland/bbagent/server/conversation/ConversationSettingsService.java` and `src/main/java/io/breland/bbagent/server/controllers/ConversationSettingsController.java`: compose and mutate typed settings for the conversation-settings session.
- `frontend/src/pages/ConversationSettingsPage.tsx` and `appclip/BlueChatClip/ClipRootView.swift`: render separate Memory and Personal Catch-ups panels from generated models.
- Existing memory tools, `ToolContext`, `AgentToolRegistry`, `AgentPromptBuilder`, `CadenceIncomingMessageHandler`, `AgentAccountResolver`, `Mem0Client`, generated clients, configuration, privacy copy, and metrics are integration points.

---

### Task 1: Add the Postgres-authoritative memory model

**Files:**
- Create: `src/main/resources/db/migration/V29__conversation_memory_core.sql`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryModels.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStore.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationMemoryStoreTest.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/account/AgentAccountResolver.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/account/AgentAccountResolverTest.java`

**Interfaces:**
- Produces: `ConversationMemoryStore.upsertConversation(...)`, `recordMessage(...)`, `scheduleExtraction(...)`, `claimDueExtractionWork(...)`, `saveExtraction(...)`, `claimDueProjections(...)`, and authorization queries used by later tasks.
- Produces: `ArtifactKind`, `ArtifactStatus`, `ArtifactSensitivity`, `JournalMessage`, `ExtractionCandidate`, `ExtractionBatch`, `AuthorizedMemory`, and `WorkClaim` records.

- [ ] **Step 1: Write the migration-backed failing tests**

  Add a `@SpringBootTest @Transactional` test that proves:

  ```java
  String conversationId = store.upsertConversation(
      "bluebubbles", "iMessage;+;group-1", true, "Trip planning", observedAt);
  store.recordMembership(conversationId, accountId, observedAt);
  store.recordMessage(new JournalMessage(
      "message-1", conversationId, accountId, "Let's meet Saturday at 6", observedAt,
      false, false, "sha256-content"));

  assertThat(store.findMessages(conversationId, observedAt.minusSeconds(1), observedAt.plusSeconds(1)))
      .extracting(JournalMessage::messageGuid)
      .containsExactly("message-1");
  ```

  Add cases for duplicate message GUID upsert, an edited message replacing text/content hash, an account that joins after an artifact not appearing in its audience, and two workers where only one lease claim succeeds.

- [ ] **Step 2: Run the tests and confirm the schema is absent**

  Run:

  ```bash
  nix develop --command ./gradlew test --tests io.breland.bbagent.server.agent.memory.ConversationMemoryStoreTest
  ```

  Expected: failure because `ConversationMemoryStore` and the V29 tables do not exist.

- [ ] **Step 3: Create the V29 schema**

  Use these tables and keys; use `TEXT` for model-produced structured payloads so the same migration validates in H2 PostgreSQL mode:

  ```sql
  CREATE TABLE agent_conversations (
    conversation_id VARCHAR(36) PRIMARY KEY,
    transport VARCHAR(32) NOT NULL,
    external_conversation_id VARCHAR(512) NOT NULL,
    is_group BOOLEAN NOT NULL,
    display_name VARCHAR(255),
    memory_enabled_at TIMESTAMP WITH TIME ZONE,
    memory_enabled_by_account_id VARCHAR(36),
    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (transport, external_conversation_id)
  );

  CREATE TABLE agent_conversation_memberships (
    membership_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
  );

  CREATE TABLE agent_conversation_messages (
    message_guid VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    sender_account_id VARCHAR(36),
    message_text TEXT,
    content_hash VARCHAR(64) NOT NULL,
    source_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    from_agent BOOLEAN NOT NULL,
    system_message BOOLEAN NOT NULL,
    removed BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
  );

  CREATE TABLE conversation_memory_work (
    conversation_id VARCHAR(36) PRIMARY KEY,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_by VARCHAR(64),
    claimed_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
  );

  CREATE TABLE conversation_memory_checkpoints (
    conversation_id VARCHAR(36) PRIMARY KEY,
    last_processed_at TIMESTAMP WITH TIME ZONE,
    last_processed_message_guid VARCHAR(255),
    last_corpus_hash VARCHAR(64),
    last_reconciled_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
  );

  CREATE TABLE conversation_memory_artifacts (
    artifact_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    artifact_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    sensitivity VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    superseded_by_artifact_id VARCHAR(36),
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (conversation_id, content_hash, occurred_at)
  );

  CREATE TABLE conversation_memory_evidence (
    artifact_id VARCHAR(36) NOT NULL,
    message_guid VARCHAR(255) NOT NULL,
    PRIMARY KEY (artifact_id, message_guid)
  );

  CREATE TABLE conversation_memory_audiences (
    artifact_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (artifact_id, account_id)
  );

  CREATE TABLE conversation_memory_projections (
    artifact_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    mem0_memory_id VARCHAR(255),
    projection_hash VARCHAR(64) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_by VARCHAR(64),
    claimed_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (artifact_id, account_id)
  );

  CREATE TABLE conversation_summary_segments (
    segment_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    summary_text TEXT NOT NULL,
    item_payload TEXT NOT NULL,
    corpus_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (conversation_id, corpus_hash)
  );

  CREATE TABLE conversation_summary_audiences (
    summary_type VARCHAR(16) NOT NULL,
    summary_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (summary_type, summary_id, account_id)
  );

  CREATE TABLE canonical_memory_records (
    memory_record_id VARCHAR(36) PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    mem0_memory_id VARCHAR(255) NOT NULL UNIQUE,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
  );
  ```

  Add indexes for conversation/time journal scans, active membership scans, due work, active artifact lookup, audience-by-account lookup, summary-audience lookup, canonical memory scope lookup, and due projections.

- [ ] **Step 4: Implement focused records and transactional storage**

  `ConversationMemoryModels` must define exact enums and records:

  ```java
  public enum ArtifactKind { GROUP_DECISION, GROUP_FACT }
  public enum ArtifactStatus { PROVISIONAL, CONFIRMED, SUPERSEDED, DELETED }
  public enum ArtifactSensitivity { NORMAL, SENSITIVE, BLOCKED }

  public record JournalMessage(
      String messageGuid,
      String conversationId,
      String senderAccountId,
      String text,
      Instant sourceTimestamp,
      boolean fromAgent,
      boolean systemMessage,
      String contentHash) {}

  public record WorkClaim(String conversationId, String workerId, Instant claimedUntil) {}
  ```

  Claim work with an atomic conditional update, not an in-memory lock:

  ```sql
  UPDATE conversation_memory_work
     SET claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1, updated_at = ?
   WHERE conversation_id = ?
     AND available_at <= ?
     AND (claimed_until IS NULL OR claimed_until < ?)
  ```

  `saveExtraction(...)` must persist artifacts, validate all evidence, snapshot the currently active membership accounts into `conversation_memory_audiences`, enqueue one projection per eligible audience account, advance the checkpoint, and complete the work claim in one transaction.

- [ ] **Step 5: Extend canonical account merging**

  Before changing account IDs, delete only composite-key collisions, then repoint remaining rows:

  ```java
  deleteAccountCollision(
      "conversation_memory_audiences", "artifact_id", targetAccountId, sourceAccountId);
  deleteAccountCollision(
      "conversation_memory_projections", "artifact_id", targetAccountId, sourceAccountId);
  updateAccountColumn(
      "agent_conversation_memberships", "account_id", targetAccountId, sourceAccountId);
  updateAccountColumn(
      "conversation_memory_audiences", "account_id", targetAccountId, sourceAccountId);
  updateAccountColumn(
      "conversation_memory_projections", "account_id", targetAccountId, sourceAccountId);
  jdbcTemplate.update(
      "update canonical_memory_records set scope_id = ? where scope_type = 'ACCOUNT' and scope_id = ?",
      targetAccountId,
      sourceAccountId);
  ```

  Add an integration test that creates source-account membership/audience/projection rows, merges source into target through `linkWebsiteAccount`, and proves the source account can be deleted without losing or duplicating access.

- [ ] **Step 6: Run focused persistence and merge tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ConversationMemoryStoreTest \
    --tests io.breland.bbagent.server.agent.account.AgentAccountResolverTest
  ```

  Expected: all tests pass and Flyway/JPA validation succeeds.

- [ ] **Step 7: Commit the persistence slice**

  ```bash
  git add src/main/resources/db/migration/V29__conversation_memory_core.sql \
    src/main/java/io/breland/bbagent/server/agent/memory \
    src/main/java/io/breland/bbagent/server/agent/account/AgentAccountResolver.java \
    src/test/java/io/breland/bbagent/server/agent/memory \
    src/test/java/io/breland/bbagent/server/agent/account/AgentAccountResolverTest.java
  git commit -m "feat: add authoritative conversation memory storage"
  ```

### Task 2: Journal eligible messages and maintain time-aware membership

**Files:**
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationJournalService.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMembershipService.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemorySettingsService.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupMemoryAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/cadence/CadenceIncomingMessageHandler.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/BBMessageAgent.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/AgentToolRegistry.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java`
- Modify: `src/main/resources/openapi.yaml`
- Modify: `src/main/java/io/breland/bbagent/server/conversation/ConversationSettingsService.java`
- Modify: `src/main/java/io/breland/bbagent/server/controllers/ConversationSettingsController.java`
- Modify generated: `frontend/src/client/`
- Modify: `frontend/src/services/api-client.ts`
- Modify: `frontend/src/pages/ConversationSettingsPage.tsx`
- Modify generated: `appclip/Generated/BlueChatAgentClient/`
- Modify: `appclip/BlueChatClip/ClipRootView.swift`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationJournalServiceTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationMemorySettingsServiceTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/cadence/CadenceIncomingMessageHandlerTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupMemoryAgentToolTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/conversation/ConversationSettingsServiceTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/controllers/ConversationSettingsControllerTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/tools/AgentToolContextDerivationTest.java`

**Interfaces:**
- Consumes: Task 1's conversation, journal, membership, and work APIs.
- Produces: `ConversationJournalService.recordEligibleMessage(IncomingMessage)` and `ConversationMembershipService.refreshGroupMembership(String conversationId)`.
- Produces: `ConversationMemorySettingsService.updateGroupMemory(accountId, chatGuid, enabled)` used by both the tool and REST controller.
- Produces: `POST /api/v1/conversationSettings/updateGroupMemory.conversationSettings` and typed `ConversationSettingsResponse.group_memory`/`personal_catchups` fields.

- [ ] **Step 1: Write failing journaling and tool tests**

  Cover these boundaries:

  ```java
  handler.handleIncomingMessage(groupMessageWithSilentResponsiveness);
  verify(journalService).recordEligibleMessage(groupMessageWithSilentResponsiveness);
  verifyNoInteractions(cadenceWorkflowLauncher);
  ```

  Also prove blocked accounts, from-agent messages, reactions, system messages, and blank messages are not journaled; accepted direct messages register a direct route; repeated group messages postpone the same work row to `last_message_at + PT60S`; the configuration tool schema contains only `enabled`; direct chats report group memory unavailable; and a conversation-settings session cannot update a different chat or account.

- [ ] **Step 2: Run the tests and confirm the services are missing**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ConversationJournalServiceTest \
    --tests io.breland.bbagent.server.agent.memory.ConversationMemorySettingsServiceTest \
    --tests io.breland.bbagent.server.agent.tools.memory.ConfigureGroupMemoryAgentToolTest \
    --tests io.breland.bbagent.server.conversation.ConversationSettingsServiceTest \
    --tests io.breland.bbagent.server.controllers.ConversationSettingsControllerTest
  ```

  Expected: failure because the journal and configuration tool are not implemented.

- [ ] **Step 3: Split eligibility from assistant invocation**

  Refactor `CadenceIncomingMessageHandler.prepare(...)` in this order:

  ```java
  if (!isEligibleTransportMessage(rawMessage)) return null;
  profileService.recordMessageIdentities(rawMessage);
  if (profileService.isProcessingBlocked(rawMessage)) return null;
  conversationJournalService.recordEligibleMessage(rawMessage);
  if (!shouldInvokeAssistant(rawMessage)) return null;
  ```

  `isEligibleTransportMessage` rejects self, unsupported transports/services, system events, reactions, and contentless events. `shouldInvokeAssistant` retains the existing assistant-responsiveness behavior. Journaling must be best-effort and must never prevent the current interactive workflow from handling a message; record only a low-cardinality failure code on exception.

- [ ] **Step 4: Implement prospective group enablement**

  Put mutation behavior in `ConversationMemorySettingsService`; `ConfigureGroupMemoryAgentTool` is a group-only adapter that derives the current conversation and requesting account from `ToolContext`:

  ```java
  public record ConfigureGroupMemoryRequest(boolean enabled) {}
  ```

  Enabling sets `memory_enabled_at=clock.instant()`, clears all pre-enable journal text for that conversation, schedules no historical extraction, and posts a visible confirmation in the same group. If the group notice cannot be confirmed, immediately restore the disabled state and return an error so collection never remains silently enabled. Disabling marks active artifacts deleted, queues projection deletes, cancels work, stops future journal retention, and posts a visible group confirmation.

- [ ] **Step 5: Formalize conversation memory settings in OpenAPI**

  Add these required sections to `ConversationSettingsResponse`; the response always includes both objects so generated clients do not need ad hoc shape checks:

  ```yaml
  ConversationGroupMemorySetting:
    type: object
    required: [available, enabled, label, description]
    properties:
      available: { type: boolean }
      enabled: { type: boolean }
      label: { type: string }
      description: { type: string }
      collection_started_at:
        type: string
        format: date-time
        nullable: true
  ConversationPersonalCatchupSetting:
    type: object
    required: [available, enabled, timezone, quiet_start, quiet_end]
    properties:
      available: { type: boolean }
      enabled: { type: boolean }
      timezone: { type: string }
      quiet_start: { type: string, pattern: '^[0-2][0-9]:[0-5][0-9]$' }
      quiet_end: { type: string, pattern: '^[0-2][0-9]:[0-5][0-9]$' }
      next_delivery_at:
        type: string
        format: date-time
        nullable: true
  ```

  Add `ConversationGroupMemoryUpdateRequest { enabled: boolean }` and the typed update endpoint:

  ```text
  POST /api/v1/conversationSettings/updateGroupMemory.conversationSettings
  ```

  Reuse `ConversationSettingsUpdateResponse` so every mutation returns the complete refreshed settings object. For direct chats set `group_memory.available=false`; until Task 7 is implemented, return `personal_catchups.available=false` and `enabled=false` with configured default timezone/quiet hours.

- [ ] **Step 6: Regenerate clients and build the two settings panels**

  Run:

  ```bash
  nix develop --command ./gradlew openApiGenerate copyClientToFrontend copySwiftClientToAppClip
  ```

  Wire the generated TypeScript client through `frontend/src/services/api-client.ts`. Add a group-only “Memory” toggle panel to `ConversationSettingsPage.tsx` and `ClipRootView.swift`, driven by `group_memory.available`; show that collection starts prospectively and that the change is announced in the group. Add a “Personal catch-ups” panel driven by `personal_catchups.available`; it renders disabled explanatory copy until Task 7 makes it available. Do not add hand-written request/response interfaces.

- [ ] **Step 7: Refresh BlueBubbles membership before extraction**

  `ConversationMembershipService.refreshGroupMembership(...)` calls `BBHttpClientWrapper.getConversationInfo(externalGuid)`, resolves every `ChatParticipant.address` through `AgentAccountResolver.resolveOrCreate(bluebubbles, address)`, adds the current message sender if absent, opens intervals for new accounts, and closes active intervals for accounts absent from the latest successful snapshot. If the participant lookup fails, throw a retryable exception and do not extract or project artifacts with an incomplete audience.

- [ ] **Step 8: Update model instructions and registry filtering**

  Register the tool as group-only. Add prompt copy that says group memory begins only after visible enablement, collective artifacts are read-only from personal chats, and the tool must not be used without an explicit request in the current group.

- [ ] **Step 9: Run the focused message-path and settings tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ConversationJournalServiceTest \
    --tests io.breland.bbagent.server.agent.memory.ConversationMemorySettingsServiceTest \
    --tests io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandlerTest \
    --tests io.breland.bbagent.server.agent.tools.memory.ConfigureGroupMemoryAgentToolTest \
    --tests io.breland.bbagent.server.agent.tools.AgentToolContextDerivationTest \
    --tests io.breland.bbagent.server.conversation.ConversationSettingsServiceTest \
    --tests io.breland.bbagent.server.controllers.ConversationSettingsControllerTest \
    --tests io.breland.bbagent.server.agent.BBMessageAgentTest
  ```

- [ ] **Step 10: Commit the ingestion and group-settings slice**

  ```bash
  git add src/main/resources/openapi.yaml \
    src/main/java/io/breland/bbagent/server/agent \
    src/main/java/io/breland/bbagent/server/conversation/ConversationSettingsService.java \
    src/main/java/io/breland/bbagent/server/controllers/ConversationSettingsController.java \
    src/test/java/io/breland/bbagent/server/agent \
    src/test/java/io/breland/bbagent/server/conversation/ConversationSettingsServiceTest.java \
    src/test/java/io/breland/bbagent/server/controllers/ConversationSettingsControllerTest.java \
    frontend/src/client frontend/src/services/api-client.ts \
    frontend/src/pages/ConversationSettingsPage.tsx \
    appclip/Generated/BlueChatAgentClient appclip/BlueChatClip/ClipRootView.swift
  git commit -m "feat: add group memory conversation settings"
  ```

### Task 3: Canonicalize existing Mem0 scopes

**Files:**
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/MemoryScopeResolver.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/ToolContext.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemorySaveAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryGetAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryUpdateAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryDeleteAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/Mem0Client.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/Mem0ClientTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/MemoryScopeResolverTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/MemoryAgentToolTest.java`

**Interfaces:**
- Produces: `MemoryScopeResolver.primaryScope(ToolContext)` and `legacyReadScope(ToolContext)`.
- Produces: `ToolContext.canonicalAccountId()` returning `Optional<String>` without raw-sender fallback.

- [ ] **Step 1: Add failing canonical-scope tests**

  Assert that phone and email identities merged into one account both resolve to `account:<same-account-id>`, an enabled group resolves to `conversation:<conversation-id>`, unresolved direct context produces no writable scope, and legacy fallback is exactly the current raw scope and never another participant's identifier.

- [ ] **Step 2: Implement canonical scope derivation**

  ```java
  public Optional<String> primaryScope(ToolContext context) {
    if (context.message().isGroup()) {
      return store.findEnabledConversationId(
          context.message().transportOrDefault(), context.message().chatGuid())
          .map(id -> "conversation:" + id);
    }
    return context.canonicalAccountId().map(id -> "account:" + id);
  }
  ```

  Keep `ToolContext.accountId()` unchanged for unrelated integrations. Add `canonicalAccountId()` so memory never accepts its raw-sender fallback.

- [ ] **Step 3: Route all new memory writes through the canonical scope**

  New one-to-one memories write to `account:<uuid>`. Enabled-group memories write to `conversation:<uuid>`. A disabled group returns `group memory is not enabled`. Change `Mem0Client.addMemory(...)` to return `MemoryMutationResult(boolean success, String memoryId)`, persist the resulting ownership in `canonical_memory_records`, and make update/delete require a matching current canonical scope row before mutating Mem0.

- [ ] **Step 4: Preserve a bounded legacy read only**

  If canonical search returns no result and `bbagent.memory.legacy-scope-read-enabled=true`, search the exact current raw sender or current raw group GUID once. Mark those results `legacy=true`, never project them cross-context, and never permit a legacy group result in a one-to-one chat.

- [ ] **Step 5: Run focused memory-tool tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.MemoryScopeResolverTest \
    --tests io.breland.bbagent.server.agent.tools.memory.MemoryAgentToolTest \
    --tests io.breland.bbagent.server.agent.tools.memory.Mem0ClientTest \
    --tests io.breland.bbagent.server.agent.tools.AgentToolContextDerivationTest
  ```

- [ ] **Step 6: Commit canonical memory scopes**

  ```bash
  git add src/main/java/io/breland/bbagent/server/agent/memory/MemoryScopeResolver.java \
    src/main/java/io/breland/bbagent/server/agent/tools \
    src/test/java/io/breland/bbagent/server/agent/memory/MemoryScopeResolverTest.java \
    src/test/java/io/breland/bbagent/server/agent/tools/memory/MemoryAgentToolTest.java
  git commit -m "feat: key memory by canonical account scopes"
  ```

### Task 4: Add debounced near-real-time extraction

**Files:**
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryModelClient.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryWorker.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationMemoryModelClientTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationMemoryWorkerTest.java`
- Modify: `src/main/java/io/breland/bbagent/server/metrics/OperationalMetricsService.java`
- Modify: `src/test/java/io/breland/bbagent/server/metrics/OperationalMetricsServiceTest.java`

**Interfaces:**
- Consumes: due-work claims, journal ranges, membership refresh, and atomic extraction persistence.
- Produces: `ConversationMemoryModelClient.extract(List<JournalMessage>, List<ExistingArtifact>)` and summary segments consumed by Task 6.

- [ ] **Step 1: Write parser and worker failure tests**

  Use a fake model client and fixed clock. Prove the worker waits until the 60-second debounce expires, processes two close messages as one batch, retries after membership refresh failure, skips unchanged corpus hashes, rejects evidence outside the batch, does not persist overlong text, and advances the checkpoint only after artifact/segment persistence succeeds.

- [ ] **Step 2: Implement the no-tools structured model call**

  Use `temperature(0)`, a configured model, `maxOutputTokens(1200)`, no tools, and a prompt that treats transcript lines as quoted data. Parse this exact shape:

  ```json
  {
    "summary": "The group compared Friday and Saturday and settled on Saturday.",
    "items": [
      {
        "kind": "GROUP_DECISION",
        "text": "The group decided to meet Saturday at 6 PM.",
        "status": "CONFIRMED",
        "sensitivity": "NORMAL",
        "confidence": 0.96,
        "occurred_at": "2026-08-08T17:03:00Z",
        "evidence_message_guids": ["message-1", "message-2"],
        "supersedes_artifact_id": null
      }
    ]
  }
  ```

  Validation rules: maximum 20 items, 500 characters per artifact, 2,000 characters per segment summary, confidence in `[0,1]`, known enum values only, all evidence in the submitted batch, and superseded IDs present in the supplied active artifacts. Invalid candidates are discarded; an invalid top-level payload fails the work claim for retry.

- [ ] **Step 3: Implement lease-safe scheduling**

  ```java
  @Scheduled(
      fixedDelayString = "${bbagent.memory.group.worker-poll-interval:PT5S}",
      initialDelayString = "${bbagent.memory.group.worker-initial-delay:PT15S}")
  public void processDueConversationMemory() {
    for (WorkClaim claim : store.claimDueExtractionWork(workerId, clock.instant(), 10)) {
      process(claim);
    }
  }
  ```

  Read new messages after the checkpoint plus a ten-minute context overlap, cap a batch at 200 messages/40,000 characters, refresh membership before extraction, hash the ordered message GUID/content hashes, persist a summary segment for every non-empty changed batch, and project only eligible confirmed artifacts.

- [ ] **Step 4: Add low-cardinality metrics**

  Add:

  - `bbagent.memory.extraction.count` tagged by `outcome` and `failure_type`.
  - `bbagent.memory.extraction.duration` with the same tags.
  - `bbagent.memory.extraction.candidate.count` tagged by `kind`, `status`, and `accepted`.
  - `bbagent.memory.work.lag` timer/gauge without conversation identifiers.

- [ ] **Step 5: Run extraction tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ConversationMemoryModelClientTest \
    --tests io.breland.bbagent.server.agent.memory.ConversationMemoryWorkerTest \
    --tests io.breland.bbagent.server.metrics.OperationalMetricsServiceTest \
    --tests io.breland.bbagent.server.BBChatGptAgentApplicationSchedulingTest
  ```

- [ ] **Step 6: Commit near-real-time extraction**

  ```bash
  git add src/main/java/io/breland/bbagent/server/agent/memory \
    src/main/java/io/breland/bbagent/server/metrics/OperationalMetricsService.java \
    src/test/java/io/breland/bbagent/server/agent/memory \
    src/test/java/io/breland/bbagent/server/metrics/OperationalMetricsServiceTest.java
  git commit -m "feat: extract group memory near real time"
  ```

### Task 5: Project and retrieve authorized group decisions

**Files:**
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/MemoryProjectionWorker.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/AuthorizedMemoryRetrievalService.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/Mem0Client.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryGetAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryUpdateAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/MemoryDeleteAgentTool.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/Mem0ClientTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/MemoryProjectionWorkerTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/AuthorizedMemoryRetrievalServiceTest.java`

**Interfaces:**
- Consumes: Task 3's `Mem0Client.addMemory(...) -> MemoryMutationResult` and canonical ownership records.
- Produces: `AuthorizedMemoryRetrievalService.search(ToolContext, String) -> List<AuthorizedMemory>`.

- [ ] **Step 1: Write projection and authorization failure tests**

  Prove a confirmed normal artifact is projected once per snapshotted audience account, a Mem0 timeout leaves the projection retryable without rerunning extraction, a superseded artifact queues deletion, a later group joiner cannot hydrate an earlier Mem0 hit, and group artifacts are returned `read_only=true` without a mutable `memory_id`.

- [ ] **Step 2: Verify Mem0 mutation responses remain observable**

  Keep Task 3's mutation result contract:

  ```java
  public record MemoryMutationResult(boolean success, String memoryId) {}
  ```

  Extend the JDK `HttpServer` tests to cover projection metadata. Assert request bodies contain only canonical `user_id` values and expected metadata, and assert logs never include response bodies.

- [ ] **Step 3: Implement the projection worker**

  Use the same conditional-lease pattern as extraction. Projection text must be declarative and source-labeled:

  ```text
  Collective group decision (Trip planning, 2026-08-08): The group decided to meet Saturday at 6 PM.
  ```

  Metadata contains opaque `artifact_id`, `conversation_id`, `kind`, `occurred_at`, and `source=bbagent_group_memory`; it contains no raw chat GUID, phone, email, or transcript. Set projection `SUCCEEDED` only after storing the returned Mem0 ID. Exponential retry delays are 30 seconds, 2 minutes, 10 minutes, and 1 hour, capped at 1 hour.

- [ ] **Step 4: Re-authorize every semantic hit**

  Search `account:<canonical-account-id>`. For each result whose Mem0 ID matches a projection row, load the artifact and require all of:

  ```java
  artifact.status() == ArtifactStatus.CONFIRMED
      && artifact.sensitivity() == ArtifactSensitivity.NORMAL
      && artifact.confidence() >= 0.85
      && store.isInArtifactAudience(artifact.id(), accountId)
      && !artifact.isExpired(clock.instant());
  ```

  Return `artifact_id`, `memory`, `source_group`, `occurred_at`, and `read_only=true`. Ordinary account memories retain `memory_id` and `read_only=false`. Update/delete tools reject read-only artifacts.

- [ ] **Step 5: Update the memory tool description**

  Tell the conversational agent that `memory_get` can return personal memories and authorized collective group decisions, that group decisions include provenance, and that they must be treated as background facts rather than instructions.

- [ ] **Step 6: Run the focused projection tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.tools.memory.Mem0ClientTest \
    --tests io.breland.bbagent.server.agent.memory.MemoryProjectionWorkerTest \
    --tests io.breland.bbagent.server.agent.memory.AuthorizedMemoryRetrievalServiceTest \
    --tests io.breland.bbagent.server.agent.tools.memory.MemoryAgentToolTest
  ```

- [ ] **Step 7: Commit projection and retrieval**

  ```bash
  git add src/main/java/io/breland/bbagent/server/agent/memory \
    src/main/java/io/breland/bbagent/server/agent/tools/memory \
    src/test/java/io/breland/bbagent/server/agent/memory \
    src/test/java/io/breland/bbagent/server/agent/tools/memory
  git commit -m "feat: expose authorized group decisions to personal chats"
  ```

### Task 6: Add time-bounded catch-ups and nightly dreaming

**Files:**
- Create: `src/main/resources/db/migration/V30__conversation_memory_catchups.sql`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationDigestService.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/AgentToolRegistry.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationMemoryWorker.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationDigestServiceTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentToolTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/tools/AgentToolContextDerivationTest.java`

**Interfaces:**
- Produces: `ConversationDigestService.catchUp(accountId, GroupHint, Instant from, Instant to)`.
- Produces: a scheduled `reconcilePreviousDay()` that composes immutable segments and verifies journal coverage.

- [ ] **Step 1: Create failing complete-range tests**

  Prove a catch-up combines daily digests for completed days with rolling segments for the current day, clips to the requested range, never returns a group outside the account's source-time audience, asks for disambiguation when two authorized group display names match, and reports `coverage_through` rather than claiming completeness beyond processed journal data.

- [ ] **Step 2: Add the V30 schema**

  ```sql
  CREATE TABLE conversation_daily_digests (
    digest_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    summary_text TEXT NOT NULL,
    item_payload TEXT NOT NULL,
    corpus_hash VARCHAR(64) NOT NULL,
    coverage_through TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (conversation_id, period_start, period_end)
  );

  CREATE TABLE conversation_digest_work (
    conversation_id VARCHAR(36) NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_by VARCHAR(64),
    claimed_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (conversation_id, period_start, period_end)
  );
  ```

  Rolling segment audiences already use `conversation_summary_audiences` from V29. Persist daily digest audiences in that same table using `summary_type='DIGEST'`; the digest audience is the intersection of its segment audiences. When a summary includes non-artifact discussion, use the membership snapshot observed for the segment's `window_end` and never grant it to a later joiner.

- [ ] **Step 3: Add a specialized direct-chat tool**

  The schema contains semantic selectors, never IDs:

  ```java
  public record GetGroupCatchupRequest(
      String group,
      String from,
      String to,
      Integer lookbackHours) {}
  ```

  Require a one-to-one message and canonical account. Clamp ranges to 31 days and reject a future or inverted range. If no group is supplied, search all authorized enabled groups and rank by activity. Return structured `groups`, `summary`, `key_developments`, `decisions`, `open_questions`, `from`, `to`, and `coverage_through`.

- [ ] **Step 4: Implement nightly reconciliation**

  At 03:15 UTC, seed one `conversation_digest_work` row per enabled conversation/prior UTC day and acquire it with the same conditional database lease used by extraction. Compare the journal corpus hash with stored segments, fetch missing history through the new paginated BlueBubbles helper when coverage has a gap, and compose the prior UTC day into one digest. The reconciliation call uses no tools and cannot create a decision without valid journal evidence. If the corpus changes because of an edit, replace the digest, supersede affected artifacts, and queue projection updates/deletes.

- [ ] **Step 5: Add paginated BlueBubbles history**

  Modify `BBHttpClientWrapper` with:

  ```java
  public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(
      String chatGuid, Instant after, Instant before, int offset, int limit, String sort)
  ```

  Convert instants to epoch seconds, enforce `1 <= limit <= 1000`, and keep the existing one-argument method as the 100-message interactive-history wrapper. Reconciliation pages in ascending order until a short page or the `before` boundary.

- [ ] **Step 6: Update prompt routing**

  Tell the agent to use `get_group_catchup` for “what happened,” “what did I miss,” and time-bounded group-summary questions. Keep `memory_get` for semantic facts and decisions; do not use its top-five results as proof of complete coverage.

- [ ] **Step 7: Run catch-up and regression tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ConversationDigestServiceTest \
    --tests io.breland.bbagent.server.agent.tools.memory.GetGroupCatchupAgentToolTest \
    --tests io.breland.bbagent.server.agent.BBHttpClientWrapperTest \
    --tests io.breland.bbagent.server.agent.tools.AgentToolContextDerivationTest \
    --tests io.breland.bbagent.server.BBChatGptAgentApplicationSchedulingTest
  ```

- [ ] **Step 8: Commit catch-ups and reconciliation**

  ```bash
  git add src/main/resources/db/migration/V30__conversation_memory_catchups.sql \
    src/main/java/io/breland/bbagent/server/agent/memory \
    src/main/java/io/breland/bbagent/server/agent/tools \
    src/main/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapper.java \
    src/test/java/io/breland/bbagent/server/agent/memory \
    src/test/java/io/breland/bbagent/server/agent/tools \
    src/test/java/io/breland/bbagent/server/agent/BBHttpClientWrapperTest.java
  git commit -m "feat: add group catchups and nightly memory reconciliation"
  ```

### Task 7: Add opt-in proactive one-to-one catch-ups

**Files:**
- Extend: `src/main/resources/db/migration/V30__conversation_memory_catchups.sql`
- Modify: `src/main/resources/openapi.yaml`
- Create: `src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java`
- Create: `src/main/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupCatchupAgentTool.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/AgentToolRegistry.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/account/AgentAccountResolver.java`
- Modify: `src/main/java/io/breland/bbagent/server/conversation/ConversationSettingsService.java`
- Modify: `src/main/java/io/breland/bbagent/server/controllers/ConversationSettingsController.java`
- Modify generated: `frontend/src/client/`
- Modify: `frontend/src/services/api-client.ts`
- Modify: `frontend/src/pages/ConversationSettingsPage.tsx`
- Modify generated: `appclip/Generated/BlueChatAgentClient/`
- Modify: `appclip/BlueChatClip/ClipRootView.swift`
- Test: `src/test/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupServiceTest.java`
- Test: `src/test/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupCatchupAgentToolTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/conversation/ConversationSettingsServiceTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/controllers/ConversationSettingsControllerTest.java`

**Interfaces:**
- Consumes: authorized digests, canonical direct routes, BlueBubbles confirmed-send semantics, and response quota.
- Produces: account-and-group preferences and exactly-one-attempt delivery receipts.
- Produces: `POST /api/v1/conversationSettings/updateCatchups.conversationSettings` and a populated `ConversationSettingsResponse.personal_catchups` object for the current account and group.

- [ ] **Step 1: Add failing preference and delivery tests**

  Prove default-off behavior, explicit current-account opt-in, quiet-hour deferral, no-change suppression, one delivery per digest hash, a daily maximum of one per account/group, direct-route preference for the most recently observed one-to-one BlueBubbles conversation, response-quota enforcement, and `UNKNOWN` state with no retry after ambiguous confirmation.

- [ ] **Step 2: Extend V30 with preferences and receipts**

  ```sql
  CREATE TABLE group_catchup_preferences (
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    proactive_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    timezone VARCHAR(64) NOT NULL,
    quiet_start VARCHAR(5) NOT NULL,
    quiet_end VARCHAR(5) NOT NULL,
    next_delivery_at TIMESTAMP WITH TIME ZONE,
    claimed_by VARCHAR(64),
    claimed_until TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, conversation_id)
  );

  CREATE TABLE group_catchup_deliveries (
    delivery_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    direct_conversation_id VARCHAR(36) NOT NULL,
    digest_hash VARCHAR(64) NOT NULL,
    coverage_through TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (account_id, conversation_id, digest_hash)
  );
  ```

- [ ] **Step 3: Implement preference management through tools and typed settings API**

  The model-facing request contains `group`, `enabled`, `timezone`, `quietStart`, and `quietEnd`; the server resolves group names only among the current account's authorized groups. Add `ConversationCatchupPreferencesUpdateRequest` with typed `enabled`, `timezone`, `quiet_start`, and `quiet_end` fields and expose:

  ```text
  POST /api/v1/conversationSettings/updateCatchups.conversationSettings
  ```

  The controller derives account and chat from the conversation-settings session and calls the same preference service as the tool. Enabling returns the exact cadence and the phrase “developments since your last catch-up.” Disabling immediately prevents future claims but leaves delivery receipts for deduplication.

- [ ] **Step 4: Regenerate clients and enable the Personal catch-ups panels**

  ```bash
  nix develop --command ./gradlew openApiGenerate copyClientToFrontend copySwiftClientToAppClip
  ```

  Use only the regenerated clients. Make the existing web and App Clip panels editable when `personal_catchups.available=true`; save the toggle, IANA timezone, and `HH:mm` quiet hours through the generated endpoint, then replace local state with the returned complete settings object.

- [ ] **Step 5: Implement conservative delivery**

  Run every 15 minutes. Claim due preferences through an atomic update of `claimed_by` and `claimed_until`, compose only new high-importance decisions/open questions since the latest successful delivery, create the delivery row before sending, and send one plain message through the recorded direct route. Mark `SENT` only after `sendTextDirect` confirms. Any false result after submission becomes `UNKNOWN`; do not automatically send it again.

- [ ] **Step 6: Extend account merging for preferences and receipts**

  Resolve preference key collisions by preserving `proactive_enabled=true`, the later `next_delivery_at`, and the target account's timezone/quiet hours; repoint delivery receipts after deleting only duplicate digest-hash rows.

- [ ] **Step 7: Run proactive and settings tests**

  ```bash
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.agent.memory.ProactiveCatchupServiceTest \
    --tests io.breland.bbagent.server.agent.tools.memory.ConfigureGroupCatchupAgentToolTest \
    --tests io.breland.bbagent.server.agent.account.AgentAccountResolverTest \
    --tests io.breland.bbagent.server.conversation.ConversationSettingsServiceTest \
    --tests io.breland.bbagent.server.controllers.ConversationSettingsControllerTest \
    --tests io.breland.bbagent.server.agent.transport.bb.BlueBubblesMessageTransportTest
  ```

- [ ] **Step 8: Commit proactive catch-ups and generated settings clients**

  ```bash
  git add src/main/resources/db/migration/V30__conversation_memory_catchups.sql \
    src/main/resources/openapi.yaml \
    src/main/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupService.java \
    src/main/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupCatchupAgentTool.java \
    src/main/java/io/breland/bbagent/server/agent/tools/AgentToolRegistry.java \
    src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java \
    src/main/java/io/breland/bbagent/server/agent/account/AgentAccountResolver.java \
    src/main/java/io/breland/bbagent/server/conversation/ConversationSettingsService.java \
    src/main/java/io/breland/bbagent/server/controllers/ConversationSettingsController.java \
    src/test/java/io/breland/bbagent/server/agent/memory/ProactiveCatchupServiceTest.java \
    src/test/java/io/breland/bbagent/server/agent/tools/memory/ConfigureGroupCatchupAgentToolTest.java \
    src/test/java/io/breland/bbagent/server/agent/account/AgentAccountResolverTest.java \
    src/test/java/io/breland/bbagent/server/conversation/ConversationSettingsServiceTest.java \
    src/test/java/io/breland/bbagent/server/controllers/ConversationSettingsControllerTest.java \
    frontend/src/client frontend/src/services/api-client.ts \
    frontend/src/pages/ConversationSettingsPage.tsx \
    appclip/Generated/BlueChatAgentClient appclip/BlueChatClip/ClipRootView.swift
  git commit -m "feat: add opt-in proactive group catchups"
  ```

### Task 8: Configuration, privacy, observability, and guarded rollout

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Modify: `manifests/bluebubbles-chatgpt-agent/be-components.yaml`
- Modify: `frontend/src/pages/PrivacyPage.tsx`
- Modify: `frontend/src/pages/TermsPage.tsx`
- Modify: `src/main/java/io/breland/bbagent/server/metrics/OperationalMetricsService.java`
- Modify: `src/test/java/io/breland/bbagent/server/metrics/OperationalMetricsServiceTest.java`

**Interfaces:**
- Adds production controls and metrics around the typed conversation-settings APIs added in Tasks 2 and 7.

- [ ] **Step 1: Add enabled-by-default global configuration with per-group opt-in**

  Add identical test-safe keys to both property files and environment wiring to the manifest:

  ```properties
  bbagent.memory.group.enabled=${BBAGENT_GROUP_MEMORY_ENABLED:true}
  bbagent.memory.group.debounce=${BBAGENT_GROUP_MEMORY_DEBOUNCE:PT60S}
  bbagent.memory.group.worker-poll-interval=${BBAGENT_GROUP_MEMORY_POLL_INTERVAL:PT5S}
  bbagent.memory.group.worker-initial-delay=${BBAGENT_GROUP_MEMORY_INITIAL_DELAY:PT15S}
  bbagent.memory.group.reconciliation-cron=${BBAGENT_GROUP_MEMORY_RECONCILIATION_CRON:0 15 3 * * *}
  bbagent.memory.group.cleanup-cron=${BBAGENT_GROUP_MEMORY_CLEANUP_CRON:0 45 3 * * *}
  bbagent.memory.group.proactive-poll-interval=${BBAGENT_GROUP_MEMORY_PROACTIVE_POLL_INTERVAL:PT15M}
  bbagent.memory.group.responses-model=${BBAGENT_GROUP_MEMORY_RESPONSES_MODEL:openrouter/z-ai/glm-5.2}
  bbagent.memory.group.fallback-responses-model=${BBAGENT_GROUP_MEMORY_FALLBACK_RESPONSES_MODEL:openai/gpt-4.1-mini}
  bbagent.memory.group.max-prompt-price-per-million=${BBAGENT_GROUP_MEMORY_MAX_PROMPT_PRICE_PER_MILLION:0.40}
  bbagent.memory.group.max-completion-price-per-million=${BBAGENT_GROUP_MEMORY_MAX_COMPLETION_PRICE_PER_MILLION:1.60}
  bbagent.memory.group.raw-retention=${BBAGENT_GROUP_MEMORY_RAW_RETENTION:P30D}
  bbagent.memory.group.segment-retention=${BBAGENT_GROUP_MEMORY_SEGMENT_RETENTION:P90D}
  bbagent.memory.group.minimum-confidence=${BBAGENT_GROUP_MEMORY_MINIMUM_CONFIDENCE:0.85}
  bbagent.memory.group.proactive-enabled=${BBAGENT_GROUP_MEMORY_PROACTIVE_ENABLED:false}
  bbagent.memory.legacy-scope-read-enabled=${BBAGENT_MEMORY_LEGACY_SCOPE_READ_ENABLED:true}
  ```

  Production first enables the global pipeline while all conversations remain individually disabled. Proactive delivery remains globally false through the observation period.

- [ ] **Step 2: Add retention cleanup**

  Schedule daily cleanup that nulls/deletes expired raw text after 30 days, deletes segments after 90 days only when covered by a daily digest, and queues projection deletes for expired/deleted artifacts. Record counts, never content.

- [ ] **Step 3: Update privacy and terms copy**

  State plainly that opted-in groups may be summarized, group decisions may be made available only to participants observed at the time, source text has bounded retention, Mem0 is used as a semantic index, and proactive summaries require individual opt-in. Explain how a group can disable future collection.

- [ ] **Step 4: Complete operational metrics**

  Add projection, digest, catch-up, and proactive-delivery count/duration metrics using only `operation`, `outcome`, `failure_type`, `kind`, and `delivery_mode` tags. Add gauges for oldest due extraction age, oldest due projection age, and failed work count. Never tag account, conversation, message, artifact, model output, or Mem0 ID.

- [ ] **Step 5: Update the BlueBubbles Grafana dashboard during deployment**

  Add panels for extraction success rate/latency, due-work lag, projection retry backlog, catch-up calls, and proactive send outcomes using the existing Influx datasource/bucket conventions. Add no paging alert in the observation phase; establish a seven-day baseline first. If required telemetry disappears entirely after enablement, add a required-health alert with deterministic seeded zero and `execErrState=Alerting`.

- [ ] **Step 6: Run formatting and the full focused suite**

  ```bash
  nix develop --command ./gradlew openApiGenerate copyClientToFrontend copySwiftClientToAppClip
  nix develop --command ./gradlew spotlessApply
  nix develop --command ./gradlew test \
    --tests io.breland.bbagent.server.BBChatGptAgentApplicationTests \
    --tests io.breland.bbagent.server.BBChatGptAgentApplicationSchedulingTest \
    --tests 'io.breland.bbagent.server.agent.memory.*' \
    --tests 'io.breland.bbagent.server.agent.tools.memory.*' \
    --tests io.breland.bbagent.server.agent.account.AgentAccountResolverTest \
    --tests io.breland.bbagent.server.agent.BBMessageAgentTest \
    --tests io.breland.bbagent.server.agent.BBHttpClientWrapperTest \
    --tests io.breland.bbagent.server.conversation.ConversationSettingsServiceTest \
    --tests io.breland.bbagent.server.controllers.ConversationSettingsControllerTest \
    --tests io.breland.bbagent.server.metrics.OperationalMetricsServiceTest
  ```

- [ ] **Step 7: Run the full test suite and classify ambient failures**

  ```bash
  nix develop --command ./gradlew test
  ```

  Expected: project tests pass. If `NominatimReverseLookupIntegTest.testReverseLookup()` alone fails because of live-network timeout or changed external data, record that ambient failure and retain the passing focused suite as feature evidence.

- [ ] **Step 8: Commit configuration and disclosures**

  ```bash
  git add src/main/resources/application.properties \
    src/test/resources/application.properties \
    manifests/bluebubbles-chatgpt-agent/be-components.yaml \
    frontend/src/pages/PrivacyPage.tsx \
    frontend/src/pages/TermsPage.tsx \
    src/main/java/io/breland/bbagent/server/metrics/OperationalMetricsService.java \
    src/test/java/io/breland/bbagent/server/metrics/OperationalMetricsServiceTest.java
  git commit -m "docs: configure and disclose group memory"
  ```

- [ ] **Step 9: Roll out in gates**

  1. Deploy with both global group memory and proactive delivery disabled; verify Flyway, scheduler startup, and zero backlog errors.
  2. Enable the global pipeline but leave every group disabled; verify idle metrics remain healthy.
  3. Enable one internal test group prospectively; verify journal retention starts at enable time and a confirmed decision becomes available to an authorized one-to-one chat within two minutes.
  4. Verify a later-added test account cannot retrieve the earlier artifact.
  5. Exercise “what happened today?” and compare `coverage_through` with journal timestamps.
  6. Observe extraction quality, leakage tests, cost, retry backlog, and latency for seven days.
  7. Enable proactive delivery globally only after the observation gate; then opt in one internal account/group and verify quiet hours, dedupe, quota, and ambiguous-send behavior.

---

## Acceptance Checklist

- A confirmed collective decision becomes searchable in an authorized personal chat within two minutes under normal load.
- A tentative suggestion is not projected as durable memory.
- Catch-up answers cover an explicit time range and report `coverage_through`.
- A later joiner cannot retrieve artifacts or digests created before their observed membership.
- Disabling group memory prevents new collection and removes existing semantic projections.
- Mem0 downtime causes projection retries without rerunning extraction or losing Postgres artifacts.
- Nightly reconciliation repairs a missed webhook or edited message idempotently.
- Proactive delivery remains silent without explicit account-and-group opt-in.
- Conversation Settings exposes group-wide Memory and account-specific Personal catch-ups as separate typed sections on both web and App Clip.
- The OpenAPI-generated Java models, TypeScript client, and Swift client match the checked-in schema; no hand-written API types bypass them.
- Metrics and logs contain no raw message content or high-cardinality identity fields.
