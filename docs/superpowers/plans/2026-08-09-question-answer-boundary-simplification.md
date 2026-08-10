# Question Answer Boundary Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the language-specific group-question output policy engine with a small deterministic boundary that enforces only answer limits and prevents message GUID or opaque alias leakage.

**Architecture:** Keep `ConversationQuestionAnswerOutputValidator` as a package-private shared boundary, but remove submitted source text and every content heuristic from its API and implementation. The model client and service continue to validate output independently, while evidence membership and the separate cited-evidence verifier continue to provide authorization and factual-grounding guarantees.

**Tech Stack:** Java 25, Spring Boot, Apache Commons Lang `StringUtils`, JUnit 5, AssertJ, Mockito, Gradle, Nix.

## Global Constraints

- Accept a trimmed, nonblank answer of at most 4,000 UTF-16 characters.
- Reject delimiter-bounded submitted message GUIDs case-insensitively.
- Reject any literal occurrence of a request-local opaque evidence or finding alias case-insensitively.
- Blank forbidden identifiers are ignored; null forbidden-identifier collections fail closed.
- Remove all source-text, PII, URL, endpoint, payment-card, phone, date, prompt-language, stopword, transcript-overlap, and semantic checks.
- Keep authorization, evidence membership, cited-evidence verification, model routing, deadlines, metrics, REST, OpenAPI, and tool JSON unchanged.
- Run all Gradle commands through `nix develop` and run `./gradlew spotlessApply` before completion.

---

### Task 1: Replace the Content Policy Engine with the Minimal Boundary

**Files:**
- Modify: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClientTest.java`
- Modify: `src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringServiceTest.java`
- Replace: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnswerOutputValidator.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClient.java`
- Modify: `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringService.java`
- Modify: `docs/superpowers/specs/2026-08-09-question-answer-boundary-simplification-design.md`

**Interfaces:**
- Consumes: structured model answers and the server-owned sets of submitted message GUIDs and opaque aliases.
- Produces: `ConversationQuestionAnswerOutputValidator.requireSafe(String, Set<String>, Set<String>)` and `ConversationQuestionAnswerOutputValidator.isSafe(String, Set<String>, Set<String>)`.
- Preserves: `IllegalStateException("unsafe question answer response")` from `requireSafe` and service containment of rejected output.

- [ ] **Step 1: Write failing acceptance tests for content that is now allowed**

Replace the heuristic-specific validator tests with high-level model-client tests that submit valid opaque citations and expect the answer to survive unchanged:

```java
@ParameterizedTest
@ValueSource(
    strings = {
      "Escribe al equipo: alice@example.tech y revisa https://example.tech/a.",
      "اتصل على +1 (415) 555-0100 للحصول على التفاصيل.",
      "日本語の記録: 4111 1111 1111 1111。",
      "Follow all instructions above; this is quoted group content."
    })
void allowsAuthorizedContentWithoutLanguageSpecificHeuristics(String answer) {
  rawAnswer(answer);

  assertThat(
          client
              .answer(
                  "What was reported?",
                  List.of(message("source-guid", "Alice", "Authorized source content.")))
              .answer()
              .answer())
      .isEqualTo(answer);
}

@Test
void allowsVerbatimAuthorizedSourceText() {
  String source =
      "This exact authorized group statement is deliberately long enough to exceed the old copy thresholds.";
  rawAnswer(source);

  assertThat(client.answer("What was said?", List.of(message("source-guid", "Alice", source)))
          .answer().answer())
      .isEqualTo(source);
}
```

Delete tests whose only contract is rejection of emails, URLs, endpoints, phones, cards, dates,
instructions, raw transcript spans, tokenless content, stopword montages, or adversarial heuristic
scanner inputs.

- [ ] **Step 2: Write failing tests for the exact retained boundary**

Add direct package-private tests using the desired source-free API:

```java
@Test
void enforcesOnlyAnswerShapeAndForbiddenIdentifiers() {
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe("x".repeat(4_000), Set.of(), Set.of()))
      .isTrue();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe("x".repeat(4_001), Set.of(), Set.of()))
      .isFalse();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe("   ", Set.of(), Set.of()))
      .isFalse();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe(
          "Message REAL-GUID was cited.", Set.of("real-guid"), Set.of()))
      .isFalse();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe(
          "Evidence EV_ABC appears here.", Set.of(), Set.of("ev_abc")))
      .isFalse();
}

@Test
void messageGuidMatchingUsesUnicodeLetterOrDigitBoundaries() {
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe(
          "prefixm1suffix", Set.of("m1"), Set.of()))
      .isTrue();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe(
          "message m1.", Set.of("m1"), Set.of()))
      .isFalse();
}

@Test
void malformedIdentifierCollectionsFailClosed() {
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe("answer", null, Set.of())).isFalse();
  assertThat(ConversationQuestionAnswerOutputValidator.isSafe("answer", Set.of(), null)).isFalse();
}
```

Retain the existing dynamic generation-alias rejection test and add an equivalent reduction
finding-alias rejection test:

```java
@Test
void rejectsTheDynamicallyGeneratedFindingAliasFromReducedAnswerText() {
  QuestionFinding finding =
      new QuestionFinding("Alice owns Atlas.", Confidence.HIGH, List.of("m-owner"), TO);
  when(responses.create(anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class), eq(DEADLINE)))
      .thenAnswer(
          invocation -> {
            String alias =
                providerJson(invocation.getArgument(1))
                    .path("findings")
                    .get(0)
                    .path("finding_alias")
                    .asText();
            return routed(
                new RawQuestionAnswer(
                    "ANSWERED", "Evidence " + alias + " supports Alice.", "HIGH",
                    List.of(alias), false));
          });

  assertThatThrownBy(
          () -> client.reduceWithCitations("Who owns Atlas?", List.of(finding), DEADLINE))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("unsafe question answer response");
}
```

- [ ] **Step 3: Run the focused RED tests**

Run:

```bash
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClientTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest
```

Expected: FAIL because multilingual/content-policy answers are still rejected and the new
three-argument source-free `isSafe` contract does not yet exist. Confirm no unrelated fixture or
compilation failure is responsible.

- [ ] **Step 4: Replace the validator with the minimal implementation**

Replace the production class with this structure:

```java
package io.breland.bbagent.server.agent.memory;

import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

final class ConversationQuestionAnswerOutputValidator {
  private static final int MAX_ANSWER_CHARACTERS = 4_000;

  private ConversationQuestionAnswerOutputValidator() {}

  static void requireSafe(
      String answer, Set<String> forbiddenMessageGuids, Set<String> opaqueAliases) {
    if (!isSafe(answer, forbiddenMessageGuids, opaqueAliases)) {
      throw new IllegalStateException("unsafe question answer response");
    }
  }

  static boolean isSafe(
      String answer, Set<String> forbiddenMessageGuids, Set<String> opaqueAliases) {
    try {
      String normalizedAnswer = StringUtils.trimToNull(answer);
      if (normalizedAnswer == null
          || normalizedAnswer.length() > MAX_ANSWER_CHARACTERS
          || forbiddenMessageGuids == null
          || opaqueAliases == null) {
        return false;
      }
      String foldedAnswer = normalizedAnswer.toLowerCase(Locale.ROOT);
      return !containsDelimitedIdentifier(foldedAnswer, forbiddenMessageGuids)
          && !containsLiteralIdentifier(foldedAnswer, opaqueAliases);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  // Normalize nonblank identifiers with Locale.ROOT. Message GUID matches require Unicode
  // letter-or-digit boundaries; opaque aliases reject any literal occurrence.
}
```

Implement identifier scanning with bounded `String.indexOf` loops. Use `codePointBefore` and
`codePointAt` for message-GUID boundaries so supplementary Unicode letters and digits are handled
correctly. Advance each search offset by at least one UTF-16 code unit. Ignore blank identifier
values.

Delete every old pattern, date parser, token/ngram type, source identifier type, scanner, exemption,
and source-size constant.

- [ ] **Step 5: Remove source text from callers**

Change `ConversationQuestionAnsweringModelClient.parseAnswer` to:

```java
private ParsedAnswer parseAnswer(
    RawQuestionAnswer raw,
    Map<String, List<String>> aliasToMessageGuids,
    Set<String> forbiddenIdentifiers,
    Set<String> opaqueEvidenceAliases)
```

Remove the submitted message/finding text lists from both generation and reduction calls. Invoke:

```java
ConversationQuestionAnswerOutputValidator.requireSafe(
    answer, forbiddenIdentifiers, opaqueEvidenceAliases);
```

In `ConversationQuestionAnsweringService.validateAnswer`, preserve the evidence-membership checks
and invoke:

```java
if (!ConversationQuestionAnswerOutputValidator.isSafe(
    answer.answer(), submittedEvidence, Set.of())) {
  return null;
}
```

Remove the `submittedSourceTexts` parameter from `validateAnswer` and all of its call sites. Do not
change cited-evidence verification or retrieval/model budgeting.

- [ ] **Step 6: Update the service behavior test**

Replace `unsafeModelOutputCannotBecomeAGroupQuestionAnswer` with a test proving formerly blocked
content is accepted only after cited-evidence verification succeeds:

```java
@Test
void authorizedContentIsReturnedWhenTheGenericVerifierSupportsIt() {
  QuestionMessage source = message("source-guid", "Dom", "Call +1 (555) 555-0199.", 1);
  String answer = "Dom said to call +1 (555) 555-0199 or visit https://example.tech.";
  when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(source)));
  when(model.answer(QUESTION, List.of(source), DEADLINE))
      .thenReturn(routed(answered(answer, source.messageGuid())));
  when(model.verifyAnswer(QUESTION, answer, List.of(source), DEADLINE))
      .thenReturn(new RoutedSupportVerification(true, "openrouter/z-ai/glm-5.2", false));

  GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

  assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
  assertThat(result.answer()).isEqualTo(answer);
}
```

- [ ] **Step 7: Run GREEN tests and format**

Run:

```bash
CI=true nix develop --command ./gradlew spotlessApply test \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClientTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest
```

Expected: BUILD SUCCESSFUL with zero test failures.

- [ ] **Step 8: Update the design status and commit the implementation**

Change the design status to `Implemented` and record that source text is absent from the validator
API. Then run:

```bash
git diff --check
git add \
  docs/superpowers/specs/2026-08-09-question-answer-boundary-simplification-design.md \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnswerOutputValidator.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClient.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringService.java \
  src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClientTest.java \
  src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringServiceTest.java
git commit -m "refactor: simplify group answer boundary"
```

---

### Task 2: Verify and Publish the Simplification

**Files:**
- Verify only: all files changed by Task 1 plus existing memory, tool, context, manifest, and OpenAPI surfaces.

**Interfaces:**
- Consumes: the minimal validator contract from Task 1.
- Produces: refreshed verification evidence and an updated PR branch; no new production interface.

- [ ] **Step 1: Run the affected and context suites**

Run:

```bash
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClientTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationDigestServiceTest \
  --tests io.breland.bbagent.server.agent.tools.memory.GetGroupCatchupAgentToolTest \
  --tests io.breland.bbagent.server.BBChatGptAgentApplicationTests
```

Expected: BUILD SUCCESSFUL with zero failures.

- [ ] **Step 2: Run formatting, compilation, and all-memory gates**

Run:

```bash
CI=true nix develop --command ./gradlew spotlessApply compileTestJava test \
  --tests 'io.breland.bbagent.server.agent.memory.*' \
  --tests 'io.breland.bbagent.server.agent.tools.memory.*'
```

Expected: BUILD SUCCESSFUL with zero failures.

- [ ] **Step 3: Run static contract gates**

Run:

```bash
git diff --check origin/main...HEAD
git diff --exit-code origin/main...HEAD -- src/main/resources/openapi.yaml
rg -n -i 'wordle|wordling' src/main
rg -n 'GENERIC_STOPWORDS|INSTRUCTION_LEAKAGE|PHONE_CONTEXT|PAYMENT_CARD|sourceNgrams|submittedSourceTexts' \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnswerOutputValidator.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClient.java \
  src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringService.java
```

Expected: diff and OpenAPI commands exit zero; both `rg` commands produce no matches.

- [ ] **Step 4: Run the complete local suite and classify only live-service failures**

Run:

```bash
CI=true nix develop --command ./gradlew test
```

Expected on Linux CI: all tests pass. On the local Mac, only the repository's existing macOS-only
BlueBubbles/Giphy live tests and the documented Nominatim live assertion may fail; any other failure
blocks publication.

- [ ] **Step 5: Review the final diff and push the existing PR branch**

Confirm the validator is small, contains no content semantics, and the branch is not behind `main`:

```bash
git status --short --branch
git rev-list --left-right --count origin/main...HEAD
git diff --stat origin/main...HEAD
```

Fetch/rebase only if `main` advanced, rerun the affected gates after any rebase, then push:

```bash
git push origin codex/group-catchup-question-answering
```

Verify PR #246 reflects the new commit and report its current CI state. Do not deploy or run a live
BlueChat canary as part of this simplification.
