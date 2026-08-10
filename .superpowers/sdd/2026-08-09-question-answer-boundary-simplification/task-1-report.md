# Task 1 Report: Minimal QA Output Boundary

## Result

Replaced the content-policy validator with the required minimal boundary: nonblank answer shape,
4,000 UTF-16 character maximum, Unicode delimiter-bounded submitted message GUID rejection, and
literal opaque-alias rejection. The model client and service no longer pass source text to this
boundary; the service retains cited-evidence membership and generic support verification.

## Files changed

- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnswerOutputValidator.java`
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClient.java`
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringService.java`
- `src/main/java/io/breland/bbagent/server/agent/memory/ConversationHistoryMessageMapper.java`
- `src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringModelClientTest.java`
- `src/test/java/io/breland/bbagent/server/agent/memory/ConversationQuestionAnsweringServiceTest.java`
- `docs/superpowers/specs/2026-08-09-question-answer-boundary-simplification-design.md`

`ConversationHistoryMessageMapper` owns the pre-existing participant-label length/word guard now
that the output validator no longer owns unrelated label validation.

## RED

Command:

```sh
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClientTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest
```

Output: `BUILD FAILED` at `:compileTestJava` with exactly eight expected type errors: the old
validator had `isSafe(String, Set<String>, List<String>)`, while the new acceptance tests required
the missing source-free `isSafe(String, Set<String>, Set<String>)`. No fixture, generated-source,
or unrelated compilation failure occurred.

## GREEN

Command:

```sh
CI=true nix develop --command ./gradlew spotlessApply test \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClientTest \
  --tests io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringServiceTest
```

Output: `BUILD SUCCESSFUL in 5s`; zero test failures.

Additional regression command:

```sh
CI=true nix develop --command ./gradlew test \
  --tests io.breland.bbagent.server.agent.memory.ConversationHistoryMessageMapperTest
```

Output: `BUILD SUCCESSFUL in 1s`; zero test failures.

Static review: `git diff --check` passed. The focused validator scan found no retained
pattern/token/source/transcript/date/payment/endpoint content-policy terms.

## Commit

`Simplify group question answer output boundary` (the final repository HEAD; this report is part of
that commit).

## Concerns

None. No API, deployment, live test, PR, or push action was performed.
