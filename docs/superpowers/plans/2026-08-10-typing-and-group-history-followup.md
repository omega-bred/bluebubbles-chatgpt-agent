# Typing Indicator and Group History Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix false-empty BlueBubbles group-history queries, preserve model-driven relative-time QA and identity follow-up, and show a best-effort typing indicator for the complete model turn.

**Architecture:** Keep `Instant` and authorization logic unchanged inside the QA service, converting to epoch milliseconds only at the BlueBubbles HTTP boundary. Treat question-mode `lookback_hours` as summary-only so relative phrases remain inside the exact QA question. Add default no-op transport typing hooks, a BlueBubbles turn-ownership map, non-blocking generated-client calls, and a Cadence start/finally-stop envelope around the full model/tool loop.

**Tech Stack:** Java 25, Spring Boot, Cadence Java client, Reactor/WebClient, generated BlueBubbles Java client, JUnit 5, Mockito, AssertJ, Gradle, Nix.

## Global Constraints

- Typing is BlueBubbles-only, completely best-effort, and must never delay, fail, or alter an assistant turn.
- Start typing immediately before the first model call; stop only after the complete turn succeeds, produces no response, is rate-limited, or fails.
- Preserve summary-mode 24-hour defaults and numeric `lookback_hours` behavior.
- For question mode, only an explicit absolute `from` is a hard lower bound; relative durations remain in the exact question for the QA model.
- Preserve server-derived group scope, membership-interval filtering, tools-disabled QA isolation, deadline/resource limits, and the deterministic GUID/alias validator.
- Do not expose raw group transcripts to the main model or invent participant names.
- Do not add a REST API, application OpenAPI change, database migration, dependency, or feature flag.
- Run project commands through `nix develop --command`; run formatting with `./gradlew spotlessApply`.

---

### Task 1: Correct History Timestamp Units and Question Range Semantics

**Files:**
- Modify: `src/test/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapperQuestionHistoryTest.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapper.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentToolTest.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentTool.java`

**Interfaces:**
- Consumes: `BBHttpClientWrapper.getMessagesInChatForQuestion(String, Instant, Instant, int, int, String, Duration)` and `ConversationDigestService.answerQuestion(...)`.
- Produces: BlueBubbles query strings using `Instant.toEpochMilli()` and question-mode `QuestionRange(from, to, timezone)` that ignores `lookback_hours` when `from` is absent.

- [ ] **Step 1: Change the timestamp forwarding test to require milliseconds**

Update `newestQuestionWindowForwardsDescendingFiveHundredMessageBounds` so the generated client verification is:

```java
verify(chatApi)
    .apiV1ChatChatGuidMessageGet(
        eq("group"),
        eq("pw"),
        eq("handle,chats"),
        eq(Long.toString(FROM.toEpochMilli())),
        eq(Long.toString(TO.toEpochMilli())),
        eq(0),
        eq(500),
        eq("DESC"));
```

- [ ] **Step 2: Change the question-lookback regression to require an unbounded lower range**

Rename `explicitQuestionLookbackCreatesAHardLowerBound` to
`questionModeLeavesRelativeLookbackInsideTheQuestion` and verify:

```java
invokeTool("{\"group\":\"Project chat\",\"question\":\"What changed in the last 48 hours?\",\"lookback_hours\":48}");

verify(digestService)
    .answerQuestion(
        "account-1",
        "Project chat",
        "What changed in the last 48 hours?",
        null,
        NOW,
        null);
```

Add a schema assertion that the `lookback_hours` property description contains `summary` and does
not instruct question callers to manufacture a relative cutoff.

- [ ] **Step 3: Run the two focused test classes and capture RED**

Run:

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapperQuestionHistoryTest \
  --tests io.breland.bbagent.server.agent.tools.memory.GetGroupCatchupAgentToolTest
```

Expected: the generated-client verification fails because seconds are forwarded, and the tool test
fails because `lookback_hours` becomes a hard lower bound.

- [ ] **Step 4: Convert history bounds at the HTTP boundary**

In `BBHttpClientWrapper.getMessagesInChat`, replace both epoch-second conversions:

```java
after == null ? null : Long.toString(after.toEpochMilli()),
before == null ? null : Long.toString(before.toEpochMilli()),
```

Do not change `TimeSupport`, authorization comparisons, paging, or journal timestamps.

- [ ] **Step 5: Make lookback summary-only in question mode**

Annotate the request components with explicit schema descriptions, including:

```java
@Schema(description = "Summary-mode lookback in hours. Omit when question is present; relative time stays in the exact question.")
@JsonProperty("lookback_hours") Integer lookbackHours
```

Simplify `resolveQuestionRange` to derive `from` only from `request.from()`:

```java
Instant to = request.to() == null ? now : Instant.parse(request.to());
Instant from = request.from() == null ? null : Instant.parse(request.from());
```

Retain range ordering, future-time, and timezone validation. Do not change `resolveCatchupRange`.

- [ ] **Step 6: Run focused tests and capture GREEN**

Run the Step 3 command. Expected: both classes pass.

- [ ] **Step 7: Commit the transport/range fix**

```bash
git add src/main/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapper.java \
  src/test/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapperQuestionHistoryTest.java \
  src/main/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentTool.java \
  src/test/java/io/breland/bbagent/server/agent/tools/memory/GetGroupCatchupAgentToolTest.java
git commit -m "fix: retrieve bounded BlueBubbles history"
```

### Task 2: Require Bounded Main-Model Identity Follow-up

**Files:**
- Modify: `src/test/java/io/breland/bbagent/server/agent/BBMessageAgentTest.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java`

**Interfaces:**
- Consumes: question-mode tool JSON containing `unresolved_participants` with `label` and `identity`.
- Produces: developer guidance that uses visible one-to-one context first, otherwise makes one `memory_get` identity lookup, preserves group facts, and falls back to safe labels.

- [ ] **Step 1: Strengthen direct, LXMF, and group prompt tests**

For each existing precise-group-question prompt test, assert the catch-up guidance contains all of:

```java
assertThat(catchupGuidance)
    .contains("omit lookback_hours when question is present")
    .contains("If unresolved_participants is nonempty")
    .contains("call memory_get once")
    .contains("do not change group-derived facts")
    .contains("keep the returned safe label");
```

Keep the existing domain-genericity and internal-jargon absence assertions.

- [ ] **Step 2: Run the prompt tests and capture RED**

Run:

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.BBMessageAgentTest.directBlueChatPromptForwardsPreciseGroupQuestionsWithoutMemorySubstitution \
  --tests io.breland.bbagent.server.agent.BBMessageAgentTest.directLxmfPromptForwardsPreciseGroupQuestionsWithoutMemorySubstitution \
  --tests io.breland.bbagent.server.agent.BBMessageAgentTest.groupPromptRestrictsCatchupQuestionsToTheCurrentConversation
```

Expected: failures on the new summary-only lookback and mandatory single identity-lookup wording.

- [ ] **Step 3: Update all three transport prompt variants consistently**

Replace the current relative-time/unresolved-participant guidance with behavior equivalent to:

```text
Pass relative phrases such as today or recently unchanged in the exact question. Supply from/to only
when the user clearly established an absolute range, and omit lookback_hours when question is
present. If unresolved_participants is nonempty, use visible one-to-one context first; otherwise call
memory_get once only to resolve those identities. Do not change group-derived facts. If no supported
name is found, keep the returned safe label. Never mention retrieval, authorization, coverage,
evidence validation, aliases, models, or internal answer states.
```

Apply the same rule to direct BlueBubbles, direct LXMF, and current-group guidance without adding
topic examples or domain keywords.

- [ ] **Step 4: Run prompt tests and capture GREEN**

Run the Step 2 command. Expected: all three tests pass.

- [ ] **Step 5: Commit the prompt behavior**

```bash
git add src/main/java/io/breland/bbagent/server/agent/AgentPromptBuilder.java \
  src/test/java/io/breland/bbagent/server/agent/BBMessageAgentTest.java
git commit -m "fix: resolve group answer participants from context"
```

### Task 3: Add Fire-and-Forget BlueBubbles Typing Hooks

**Files:**
- Modify: `src/main/java/io/breland/bbagent/server/agent/transport/MessageTransport.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapper.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/transport/bb/BlueBubblesMessageTransport.java`
- Create: `src/test/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapperTypingTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/transport/bb/BlueBubblesMessageTransportTest.java`

**Interfaces:**
- Produces: `MessageTransport.startTyping(IncomingMessage message, String turnToken)` and
  `MessageTransport.stopTyping(IncomingMessage message, String turnToken)`, both default no-op.
- Produces: `BBHttpClientWrapper.startTyping(String chatGuid)` and `stopTyping(String chatGuid)`,
  both non-blocking and exception-contained.
- Produces: per-chat compare-and-remove ownership in `BlueBubblesMessageTransport`.

- [ ] **Step 1: Add failing wrapper subscription tests**

Create `BBHttpClientWrapperTypingTest` with a mocked `V1ChatApi`. Use `Mono.create` to record
subscription without completing:

```java
AtomicBoolean subscribed = new AtomicBoolean();
when(chatApi.apiV1ChatChatGuidTypingPost("group", "pw"))
    .thenReturn(Mono.create(sink -> subscribed.set(true)));

wrapper.startTyping("group");

assertThat(subscribed).isTrue();
```

Add the equivalent delete test and tests where the generated method throws synchronously or returns
`Mono.error(new IllegalStateException("private API unavailable"))`; neither public wrapper method may
throw.

- [ ] **Step 2: Add failing transport ownership tests**

Extend the capturing wrapper in `BlueBubblesMessageTransportTest` to record typing starts/stops. Add:

```java
transport.startTyping(message, "turn-1");
transport.stopTyping(message, "turn-1");
assertThat(wrapper.typingStarts).containsExactly(message.chatGuid());
assertThat(wrapper.typingStops).containsExactly(message.chatGuid());
```

Then prove obsolete ownership cannot stop a newer turn:

```java
transport.startTyping(message, "turn-1");
transport.startTyping(message, "turn-2");
transport.stopTyping(message, "turn-1");
assertThat(wrapper.typingStops).isEmpty();
transport.stopTyping(message, "turn-2");
assertThat(wrapper.typingStops).containsExactly(message.chatGuid());
```

- [ ] **Step 3: Run focused typing tests and capture RED**

Run:

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapperTypingTest \
  --tests io.breland.bbagent.server.agent.transport.bb.BlueBubblesMessageTransportTest
```

Expected: compilation fails because the typing interfaces do not exist.

- [ ] **Step 4: Add transport hooks and asynchronous wrapper operations**

Add default methods to `MessageTransport`:

```java
default void startTyping(IncomingMessage message, String turnToken) {}

default void stopTyping(IncomingMessage message, String turnToken) {}
```

In `BBHttpClientWrapper`, invoke the generated POST/DELETE Monos and subscribe without blocking.
Apply the configured API timeout inside the reactive chain, record completion/failure through stable
operation names, and catch both request-construction and subscription exceptions:

```java
public void startTyping(String chatGuid) {
  fireAndForgetTyping(
      "start_typing", () -> chatApi.apiV1ChatChatGuidTypingPost(chatGuid, password));
}

public void stopTyping(String chatGuid) {
  fireAndForgetTyping(
      "stop_typing", () -> chatApi.apiV1ChatChatGuidTypingDelete(chatGuid, password));
}
```

The helper accepts `Supplier<Mono<Void>>`, uses `timeout(apiTimeout)`, has completion/error metric
callbacks, and logs no GUID, message, account, identity, or token.

- [ ] **Step 5: Add compare-and-remove turn ownership**

In `BlueBubblesMessageTransport`, add:

```java
private final ConcurrentMap<String, String> activeTypingTurns = new ConcurrentHashMap<>();
```

Validate nonblank chat GUID and token. `startTyping` stores the token then invokes wrapper POST.
`stopTyping` invokes wrapper DELETE only when `activeTypingTurns.remove(chatGuid, turnToken)` returns
true. Starting a newer turn replaces the previous token.

- [ ] **Step 6: Run focused typing tests and capture GREEN**

Run the Step 3 command. Expected: both classes pass without waiting for an uncompleted Mono.

- [ ] **Step 7: Commit the transport typing layer**

```bash
git add src/main/java/io/breland/bbagent/server/agent/transport/MessageTransport.java \
  src/main/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapper.java \
  src/main/java/io/breland/bbagent/server/agent/transport/bb/BlueBubblesMessageTransport.java \
  src/test/java/io/breland/bbagent/server/agent/transport/bb/BBHttpClientWrapperTypingTest.java \
  src/test/java/io/breland/bbagent/server/agent/transport/bb/BlueBubblesMessageTransportTest.java
git commit -m "feat: add best-effort BlueBubbles typing"
```

### Task 4: Hold Typing Across the Complete Cadence Turn

**Files:**
- Modify: `src/main/java/io/breland/bbagent/server/agent/cadence/CadenceAgentActivities.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/cadence/CadenceAgentActivitiesImpl.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/cadence/CadenceMessageWorkflowImpl.java`
- Create: `src/test/java/io/breland/bbagent/server/agent/cadence/CadenceMessageWorkflowImplTest.java`

**Interfaces:**
- Consumes: the Task 3 transport start/stop hooks.
- Produces: `CadenceAgentActivities.startTyping(IncomingMessage, AgentWorkflowContext)` and
  `stopTyping(...)` activities.
- Produces: package-private `CadenceMessageWorkflowImpl(CadenceAgentActivities activities)` for
  deterministic direct unit tests; the public no-argument constructor retains the real activity
  stub.

- [ ] **Step 1: Add a direct workflow lifecycle test fixture**

Refactor only the test seam in the planned test first: instantiate
`new CadenceMessageWorkflowImpl(activities)` with a mocked `CadenceAgentActivities`, an unscheduled
request, empty history/input JSON, and a final bundle:

```java
when(activities.notifyIfMessageResponseLimitExceeded(message, context)).thenReturn(false);
when(activities.getConversationHistory(message)).thenReturn(List.of());
when(activities.buildConversationInputJson(List.of(), message)).thenReturn("[]");
when(activities.createResponseBundle("[]", message, context))
    .thenReturn(new CadenceResponseBundle("{}", "done", "[]", List.of()));
when(activities.handleGeneratedImages("{}", "done", message, context))
    .thenReturn(new ImageSendResult(false, false));
when(activities.sendThreadAwareText(message, "done", context)).thenReturn(true);
```

Verify with `InOrder` that start precedes `createResponseBundle`, finalization follows send, and stop
is last. Verify start and stop are each called once.

- [ ] **Step 2: Add tool-loop and exceptional-exit regressions**

Add a two-bundle tool loop where the first bundle has one `CadenceToolCall` and the second has final
text. Assert typing starts/stops once around both model calls. Add a test where
`createResponseBundle` throws and assert `stopTyping` still runs before the exception escapes.

- [ ] **Step 3: Run the workflow test and capture RED**

Run:

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.cadence.CadenceMessageWorkflowImplTest
```

Expected: compilation fails because the activity methods and injectable constructor do not exist.

- [ ] **Step 4: Add activity implementations**

Add both methods to `CadenceAgentActivities`. In `CadenceAgentActivitiesImpl`, resolve the transport
and call it with a stable token derived from the workflow context's message GUID. Catch all runtime
failures inside each activity and log only the transport plus low-cardinality failure type:

```java
transportRegistry.resolve(message).startTyping(message, workflowContext.messageGuid());
```

Use the same token for stop. Null/blank context values are no-op.

- [ ] **Step 5: Wrap the full model/tool body in start/finally-stop**

Move creation of the activity stub into the public constructor and add the package-private injection
constructor. In `run`, preserve schedule and response-limit behavior. Build conversation input, then:

```java
activities.startTyping(message, request.workflowContext());
try {
  runModelTurn(inputItemsJson, message, request.workflowContext());
} finally {
  activities.stopTyping(message, request.workflowContext());
}
```

Extract only enough of the existing method into `runModelTurn` to make the `finally` cover every
existing return path. Do not change tool-loop limits, response behavior, finalization calls, send
ordering, or retry semantics.

- [ ] **Step 6: Run workflow and transport tests and capture GREEN**

Run:

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.cadence.CadenceMessageWorkflowImplTest \
  --tests io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapperTypingTest \
  --tests io.breland.bbagent.server.agent.transport.bb.BlueBubblesMessageTransportTest
```

Expected: all pass.

- [ ] **Step 7: Commit the workflow lifecycle**

```bash
git add src/main/java/io/breland/bbagent/server/agent/cadence/CadenceAgentActivities.java \
  src/main/java/io/breland/bbagent/server/agent/cadence/CadenceAgentActivitiesImpl.java \
  src/main/java/io/breland/bbagent/server/agent/cadence/CadenceMessageWorkflowImpl.java \
  src/test/java/io/breland/bbagent/server/agent/cadence/CadenceMessageWorkflowImplTest.java
git commit -m "feat: hold typing through model turns"
```

### Task 5: Format, Verify, Document, and Publish

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-typing-and-group-history-followup-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-typing-and-group-history-followup.md`

**Interfaces:**
- Consumes: all prior task commits.
- Produces: formatted verified branch and a ready follow-up PR.

- [ ] **Step 1: Run formatting**

```bash
nix develop --command ./gradlew spotlessApply
```

- [ ] **Step 2: Run the complete focused feature matrix**

```bash
nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapperQuestionHistoryTest \
  --tests io.breland.bbagent.server.agent.tools.memory.GetGroupCatchupAgentToolTest \
  --tests io.breland.bbagent.server.agent.BBMessageAgentTest \
  --tests io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapperTypingTest \
  --tests io.breland.bbagent.server.agent.transport.bb.BlueBubblesMessageTransportTest \
  --tests io.breland.bbagent.server.agent.cadence.CadenceMessageWorkflowImplTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionHistoryRetrieverTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest
```

- [ ] **Step 3: Run broader memory, transport, and Spring gates**

```bash
nix develop --command ./gradlew test \
  --tests 'io.breland.bbagent.server.agent.memory.*' \
  --tests 'io.breland.bbagent.server.agent.tools.memory.*' \
  --tests io.breland.bbagent.server.agent.BBHttpClientWrapperTest \
  --tests io.breland.bbagent.server.BBChatGptAgentApplicationTests
```

- [ ] **Step 4: Run static and full-suite gates**

```bash
nix develop --command ./gradlew compileTestJava test
git diff --check origin/main...HEAD
git diff --exit-code origin/main...HEAD -- src/main/resources/openapi.yaml
```

Classify only the AGENTS.md-documented live BlueBubbles, Giphy, or Nominatim failures as ambient;
all affected compile, unit, integration, and Spring-context failures are blockers.

- [ ] **Step 5: Update docs with exact verification evidence**

Set the design status to `Implemented` and append the exact focused/broader/full test counts, skipped
tests, any documented ambient failures, and static-gate results. Mark every completed plan checkbox.

- [ ] **Step 6: Commit final formatting and evidence**

```bash
git add docs/superpowers/specs/2026-08-10-typing-and-group-history-followup-design.md \
  docs/superpowers/plans/2026-08-10-typing-and-group-history-followup.md \
  src/main src/test
git commit -m "docs: verify typing and history follow-up"
```

- [ ] **Step 7: Re-fetch and verify freshness**

```bash
git fetch origin main
git rev-list --left-right --count origin/main...HEAD
git status --short
```

If remote main advanced, rebase the branch and rerun affected verification before publication.

- [ ] **Step 8: Push and open the follow-up PR**

```bash
git push -u origin codex/typing-and-history-followup
gh pr create --base main --head codex/typing-and-history-followup \
  --title "Improve group history QA and show typing during turns" \
  --body $'## Summary\n- send BlueBubbles history bounds in epoch milliseconds and keep relative question ranges model-driven\n- resolve unanswered participant labels from bounded one-to-one memory context\n- show a fire-and-forget BlueBubbles typing indicator for the complete model/tool turn\n\n## Verification\n- focused history, tool, prompt, transport, and Cadence tests\n- all memory and memory-tool tests\n- Spring context, formatting, compile, full suite, diff, and unchanged OpenAPI gates\n\n## Deployment follow-up\nAfter merge and deployment, run the scoped Wordling Wonders question and typing canary documented in the design.\n\nNo REST API, application OpenAPI, database schema, migration, or production configuration change.'
```

The PR body must include the production root cause, typing lifecycle, tests, no-OpenAPI/schema note,
and the post-deployment Wordling Wonders/typing canary still required.
