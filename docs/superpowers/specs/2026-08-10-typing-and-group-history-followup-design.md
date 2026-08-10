# Typing Indicator and Group History Follow-up Design

**Date:** 2026-08-10

**Status:** Implemented and locally verified; pending PR deployment

## Context

The full-history group question-answering path is live, but a production question from an authorized
one-to-one chat returned that Wordling Wonders had no messages in the requested range. The agent
correctly discovered and invoked `get_group_catchup`; the failure occurred below the conversational
model.

Read-only production tracing found the exact cause. The tool asked for Wordling Wonders with a
24-hour lookback and the `America/Los_Angeles` timezone. The journal contained three current-day
results, but the BlueBubbles history request supplied `after` and `before` as epoch seconds. The
deployed BlueBubbles server expects epoch milliseconds. An otherwise identical live request returned
zero messages with second bounds and six messages with millisecond bounds.

The turn also remained visually idle while the main model discovered tools, queried group history,
and synthesized the answer. The production BlueBubbles server has SIP disabled, so its Private API
typing endpoints are available. BlueBubbles starts an outgoing typing indicator with
`POST /api/v1/chat/{chatGuid}/typing`; sending a message stops it automatically, and
`DELETE /api/v1/chat/{chatGuid}/typing` stops it explicitly.

This design fixes the history transport contract, reinforces model-driven relative-time discovery
and participant resolution, and adds a best-effort turn-scoped typing lifecycle. It supersedes the
numeric-lookback behavior for question mode in
`2026-08-10-full-group-history-qa-design.md`; summary mode remains unchanged.

## Goals

- Send BlueBubbles history bounds in the units the live server expects.
- Keep relative-time group questions model-driven instead of turning words such as "today" into a
  rolling 24-hour cutoff.
- Start from the newest 500 eligible messages and progressively inspect older windows when the QA
  model requests them.
- Have the main model make one bounded identity-context lookup when an accepted answer cites an
  unresolved participant.
- Start a BlueBubbles typing indicator immediately before the first model call and keep it active
  through all model and tool loops until the turn finishes.
- Make typing completely best-effort and non-blocking. A typing failure must never delay, fail, or
  alter an assistant turn.
- Preserve group authorization, membership-interval filtering, prompt-injection isolation, bounded
  output validation, and all existing catch-up behavior.

## Non-goals

- Stream partial model tokens or intermediate tool output to BlueBubbles.
- Add typing support to LXMF or other transports in this pass.
- Require typing success before starting the model or sending the final response.
- Expose raw group transcripts to the main tool-capable model.
- Infer a participant name without support from canonical account data, BlueBubbles contacts,
  one-to-one context, or semantic memory.
- Change REST APIs, the application OpenAPI contract, persistence, or migrations.
- Change proactive group-memory extraction, catch-up summaries, or group enablement.

## Group History Transport Fix

`BBHttpClientWrapper.getMessagesInChat` will serialize non-null `after` and `before` instants with
`Instant.toEpochMilli()`. Offsets, limits, sort direction, request-deadline propagation, and the
`handle,chats` expansion remain unchanged.

The conversion belongs at the BlueBubbles client boundary because all callers share the same server
contract. The retriever and QA service continue to use typed `Instant` values and half-open
`[from, to)` authorization checks. No timestamp-unit knowledge leaks into question planning or
membership code.

BlueBubbles remains the primary full-history source. The journal remains the bounded fallback when
the BlueBubbles request fails. An empty successful response is still a valid empty source result; the
millisecond regression test prevents the known false-empty request from returning.

## Relative-Time Question Behavior

Question mode will no longer treat `lookback_hours` as a hard retrieval bound. The user's exact
question already carries relative phrases such as "today," "recently," or "the current one," and the
tools-disabled QA model receives current time, timezone context, and every submitted message
timestamp.

For a nonblank `question`:

- an explicit absolute `from` and optional `to` remain hard server-enforced bounds;
- absent `from` means there is no semantic lower bound, regardless of `lookback_hours`;
- the newest 500 eligible messages form the first window;
- the QA model may request the immediately preceding window when useful; and
- lack of an answer or temporal anchor produces a natural request for an approximate time range.

This makes question behavior robust even if the main model redundantly supplies the summary-oriented
`lookback_hours` field. An explicit relative duration such as "in the last 48 hours" stays in the
exact question and is interpreted from message timestamps by the QA model. Catch-up summary mode
retains its existing 24-hour default and numeric `lookback_hours` behavior.

The tool schema and main-agent prompt will state that `lookback_hours` is for summary mode, while
question mode should pass relative phrases unchanged. They will continue to prohibit internal
phrasing about authorization, retrieval, evidence, aliases, or model states in user-facing replies.

## Participant Resolution Follow-up

The dedicated QA path continues to resolve names server-side in this order: `you`, canonical global
contact name, website display name, BlueBubbles contact display name, then a stable masked label.
Only participants cited by an accepted answer may appear in `unresolved_participants`.

When that list is nonempty, the main model will be instructed to use visible one-to-one context
first and otherwise make exactly one `memory_get` lookup aimed only at mapping the supplied fallback
labels or normalized transport identities to names. The lookup may rename participants in the
group-derived answer, but it may not add or change results, counts, dates, scores, decisions, quoted
content, or any other group fact.

If the lookup does not support a name, the assistant keeps the safe descriptive label. It does not
claim that an unidentified participant is a known person, and it does not repeat a raw transport
identity unless the user asks for it or it is directly relevant.

## Typing Lifecycle

The transport abstraction will gain default no-op `startTyping` and `stopTyping` hooks so non-
BlueBubbles transports retain their current behavior. `BlueBubblesMessageTransport` will implement
them through new wrapper methods for the generated typing `POST` and `DELETE` endpoints.

The Cadence workflow will:

1. complete eligibility, response-limit, history, and input-building work without typing;
2. call `startTyping` immediately before the first `createResponseBundle` model activity;
3. retain the indicator across every model response, tool execution, image step, retry, and final
   send; and
4. call `stopTyping` in a turn-level `finally` path after success, no-response completion, rate
   limiting, or failure.

The start and stop activities return immediately after subscribing to the BlueBubbles request. They
do not block on a network response. Synchronous client-construction errors and asynchronous request
errors are caught and logged without message text, account identifiers, or other sensitive data.
They never escape into Cadence turn logic.

BlueBubbles automatically clears typing when the final message is sent. The explicit stop remains
necessary for reactions, images without text, no-response turns, model failures, and other exits that
do not send text.

`BlueBubblesMessageTransport` will track the active turn token per chat in a concurrency-safe map.
A stop from an obsolete turn removes and sends `DELETE` only when its token still owns that chat.
This prevents a superseded Cadence run from clearing a newer turn's typing indicator. Starting a new
turn replaces the token and sends a fresh `POST`. The state is deliberately process-local and best-
effort; the final outgoing message and explicit stop remain the normal cleanup mechanisms.

## Error Handling and Observability

History retrieval failures continue to use the existing bounded journal fallback and low-cardinality
failure outcomes. The millisecond conversion itself has no new configuration or fallback.

Typing operations are fire-and-forget. Failures are logged at a bounded diagnostic level and may use
the existing BlueBubbles operation metrics with stable operation/outcome/failure-type tags. Logs and
metrics must not include chat GUIDs as metric tags, message contents, phone numbers, account IDs, or
typing turn tokens.

No feature flag is required. On a BlueBubbles server without the Private API, typing calls fail
quietly while the assistant turn proceeds normally. The production server supports the endpoints.

## Compatibility

This is an internal transport, agent-tool, prompt, and workflow behavior change. Default methods keep
existing `MessageTransport` implementations source-compatible. Catch-up summary requests preserve
their JSON shape and range semantics. Question responses preserve their existing answer,
clarification, and unresolved-participant shapes.

There is no REST or public OpenAPI change, no generated application client change, and no database
migration. The generated BlueBubbles client already contains the typing operations from
`src/main/resources/bluebubbles.yaml`.

## Testing

Implementation follows a red-green cycle and proves:

- history `after` and `before` values are forwarded as epoch milliseconds;
- a live-shaped current-day message is no longer excluded by second-based bounds;
- question mode ignores summary-oriented `lookback_hours` when no absolute `from` is supplied;
- summary mode retains its existing lookback behavior;
- relative question text, reference time, timezone, and message timestamps reach the QA model;
- the first unbounded question window remains the newest 500 eligible messages and can page older;
- nonempty `unresolved_participants` requires at most one identity-only memory lookup instruction;
- the main prompt prohibits changing group facts or exposing internal retrieval language;
- typing starts immediately before the first model response activity;
- typing remains active through multiple tool/model loops and stops only after turn completion;
- success, reaction-only, image-only, no-response, rate-limited, and exceptional exits attempt a
  stop;
- BlueBubbles start/stop calls subscribe without waiting for completion;
- synchronous and asynchronous typing errors never fail the workflow;
- an obsolete turn cannot stop a newer turn's indicator; and
- LXMF and other transports remain no-op for typing.

Verification includes Spotless, focused wrapper/tool/prompt/transport/Cadence tests, all memory and
memory-tool tests, the Spring context test, `git diff --check`, and the applicable full test suite.
The documented local live-service failures remain separately classified if they recur.

Implementation verification on 2026-08-10 produced the following evidence:

- Spotless completed successfully.
- The focused history, tool, prompt, QA, transport, and Cadence matrix passed 110/110 tests.
- The broader memory, memory-tool, legacy wrapper, and Spring-context matrix passed 174/174 tests.
- The full compile/test run compiled successfully and ran 473 tests: 461 passed, 3 skipped, and 9
  documented ambient live-service tests failed (7 local BlueBubbles client probes, Giphy, and
  Nominatim). No affected unit, integration, compile, or Spring-context test failed.
- `git diff --check` passed, production QA sources contained no example-domain terms, and the
  application OpenAPI document was unchanged.

After deployment, a scoped production canary will ask the authorized one-to-one chat a relative-time
question about Wordling Wonders and verify that the current-day messages are retrieved, the answer
compares the posted results and identifies participants as far as supported context allows, exactly
one reply is delivered, typing begins before model processing, and typing clears after completion.

## Success Criteria

- The production history request that returned zero rows with epoch seconds returns the eligible
  current-day messages with epoch milliseconds.
- A question containing "today" is answered from timestamped conversation context rather than a
  rolling 24-hour shortcut.
- Unresolved participant names receive one bounded main-model context lookup without changing group
  facts or inventing an identity.
- BlueBubbles displays typing from the first model call through the completed turn when the Private
  API is available.
- Typing failures are invisible to users and have no effect on model, tool, or delivery behavior.
- Existing authorization, membership, prompt-isolation, output-validation, deadline, and resource
  boundaries remain intact.
