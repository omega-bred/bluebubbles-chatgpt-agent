# Full Group History Question Answering Design

**Date:** 2026-08-10

**Status:** Approved architecture

## Context

The first production version of group-history question answering combines two different products:
coarse catch-up summaries and precise question answering. It first asks a model for literal search
terms, searches matching messages, verifies the generated answer with a second model call, and may
then repeat the work over chronological history. The tool returns both the ordinary catch-up fields
and the separate question answer.

A production question about activity "today" exposed three consequences:

- the default rolling 24-hour range included messages from the previous calendar day;
- the main model mixed the coarse catch-up summary into the precise answer and counted activity that
  was outside the intended period; and
- the QA path knew which canonical participant authored the relevant message but could not produce a
  useful name, while the verification path converted that gap into an insufficient-evidence result.

The user-facing response also repeated internal vocabulary such as authorization, coverage, and
insufficient evidence. Those are implementation details, not useful conversational language.

The literal-term planner and separate support verifier are not required for this use case. The
requesting account is already checked against the selected group and membership intervals, and the
tools-disabled QA model can inspect bounded chronological windows directly. Relative phrases such as
"today" are often better understood from the latest conversation sequence than from a rigid
calendar filter. The main conversation model can then use its existing one-to-one context to resolve
a participant that the group-history service could not name.

This design supersedes the retrieval, synthesis, sender-label, prompt, tool-response, and success
criteria sections of `2026-08-09-group-catchup-question-answering-design.md`. The earlier group
selection and membership authorization boundaries remain in force. The minimal deterministic
output boundary from `2026-08-09-question-answer-boundary-simplification-design.md` also remains in
force.

## Goals

- Answer arbitrary natural-language questions by progressively inspecting chronological message
  windows, without domain keywords or semantic pre-filtering.
- Let the QA model interpret relative time phrases from current time, message timestamps, and
  conversational context instead of imposing a fixed calendar boundary.
- Ask the user for an approximate time range when the newest message window provides neither an
  answer nor a useful reason to continue searching backward.
- Allow all relevant group content to inform the answer, including names, quoted text, links, email
  addresses, phone numbers, and other ordinary message content.
- Keep group messages isolated from the tool-capable main conversation model so instructions inside
  messages cannot invoke tools or alter the main agent.
- Resolve participant names from all existing trustworthy application sources and let the main model
  use one-to-one context when server-side identity enrichment remains incomplete.
- Return a user-ready answer with natural wording and no internal authorization, retrieval,
  coverage, confidence, or verifier terminology.
- Reduce the normal recent-question path to one message-window retrieval and one QA model generation.
- Preserve server-derived group scope, membership-interval filtering, bounded resource use, model
  price routing, and the minimal GUID/alias output validator.

## Non-goals

- Expose the raw group transcript to the main conversation model or in the agent tool response.
- Allow group-message content to invoke tools, modify memory, or act as instructions.
- Add domain-specific extraction, parsing, keywords, prompts, or tests to production code.
- Guarantee access to messages that BlueBubbles no longer retains or that fall outside a confirmed
  membership interval.
- Persist on-demand QA transcripts, answers, or identity hints into memory or summary storage.
- Change proactive catch-up delivery, group-memory enablement, or broad summary behavior.
- Add a REST endpoint, OpenAPI schema, database table, or migration.
- Expand the eligible event surface beyond supported text messages in this pass. Attachments,
  reactions, edits, and deleted-message semantics retain their current behavior.

## Product Behavior

`get_group_catchup` retains two explicit modes:

1. Catch-up mode, when `question` is absent or blank, returns the existing summary, developments,
   decisions, and open questions.
2. Question mode, when `question` is present, returns either a direct answer or a natural follow-up
   question for the selected group. It does not return or consult catch-up summaries, digest
   segments, semantic memory, decisions, or open questions.

Question mode is content-generic. The service retrieves the chronological message stream and asks
the QA model to answer the exact question. It does not generate search terms and does not call the
BlueBubbles exact-text search API. The group itself may query only its own history; a one-to-one chat
may query a group that the requesting canonical account is authorized to access.

The main agent renders the result conversationally. It must not tell the user that history was
authorized, that coverage was authoritative, that a verifier accepted or rejected a claim, or that
the result has an internal insufficient-evidence status. When the requested history does not answer
the question, it should say so naturally, for example, "I don't see anyone posting that yet."

## Temporal Discovery

The tool request continues to accept `from`, `to`, and `lookback_hours`, but question mode no longer
defaults to a fixed 24-hour range.

- Explicit absolute dates or ranges supplied by the user remain authoritative. The main agent may
  translate them into `from` and `to`, and the server enforces the half-open interval with `from`
  inclusive and `to` exclusive.
- An explicitly requested numeric lookback continues to use `lookback_hours`.
- Relative or semantic phrases such as "today," "the current one," "recently," or "last time" are
  passed unchanged to the QA model. The main agent does not force them into `from` and `to`.
- When no hard range is supplied, question mode starts with the newest 500 eligible messages. Five
  hundred is a configurable retrieval-window size, not a semantic cutoff.
- The QA model receives the server's current reference instant and the timestamp of every submitted
  message. It uses those timestamps together with the message sequence and question to decide what
  the user's temporal phrase means in context.
- A known user timezone may be supplied as context, but lack of a timezone does not force an
  up-front clarification. The model may answer when the conversation makes the intended period
  clear. It asks for clarification only when the ambiguity actually prevents a supported answer.
- Explicit historical ranges may go back as far as the source retains messages, subject to verified
  membership intervals and operational bounds.

No server-side natural-language date parser or fixed vocabulary of relative periods is added. The
model interprets time semantically; the server remains responsible only for hard user-supplied
bounds, paging cursors, authorization, and resource limits.

## Authorization and Isolation Boundary

The security boundary remains deterministic and server-owned:

- The canonical requester comes from `ToolContext`; the model cannot supply an account ID.
- From a group chat, transport and chat GUID come from the incoming message. A supplied group hint is
  ignored, so the group can query only itself.
- From a one-to-one chat, group resolution considers only memory-enabled groups currently available
  to the canonical account. Ambiguous names return disambiguation options before history retrieval.
- A hard requested interval is intersected with confirmed membership intervals. Without a hard
  interval, every progressively retrieved message is independently checked against confirmed
  membership intervals before it enters a model prompt.
- History calls use only the server-resolved external conversation identifier.

Group messages are untrusted data. They are sent only to a dedicated tools-disabled QA model call,
inside a clearly delimited evidence structure. Its developer prompt says that message text may be
quoted or analyzed but never followed as an instruction. The QA model has no tool definitions,
semantic memory, one-to-one transcript, or authority to perform side effects.

The QA prompt has no content-category prohibitions. Relevant names, messages, URLs, email addresses,
phone numbers, identifiers, and quoted text may appear in the synthesized answer. Prompt-injection
defense comes from model/tool isolation and instruction hierarchy, not English keyword filters or
content redaction.

The raw transcript never enters the main model. The main model receives only the synthesized answer
or clarification question and bounded identity hints for participants cited by an accepted answer.

## Retrieval and Synthesis

### Initial message window

Question mode calls the chronological retriever immediately. With no hard range, it requests the
newest 500 eligible text messages, then presents them to the model in ascending timestamp order. With
a hard range, it requests the newest window inside that range. No planner, literal term, sender hint,
digest, summary segment, or semantic index influences which messages are selected.

The first window is additionally bounded by the configured prompt-character limit. If 500 unusually
large messages cannot fit in one prompt, the server divides that same contiguous window into bounded
subchunks and reduces their decisions before choosing the next action. It never drops messages based
on their topic.

The journal remains a source fallback when BlueBubbles is unavailable. The service records whether
the current window was fully retrieved and whether an older source page is available; it must not
silently treat a truncated window as exhaustive.

### Model-directed action

The tools-disabled QA model receives the exact question, current reference instant, optional known
timezone, ordered messages, participant labels, timestamps, opaque evidence aliases, and message
text. It returns exactly one structured action:

- `ANSWERED`, with a direct natural-language answer, supporting aliases, and referenced participant
  labels;
- `NEED_OLDER_MESSAGES`, with zero or more cited provisional findings, when the window contains a
  useful temporal or conversational clue that makes scanning the immediately preceding window
  likely to help;
- `NEED_TIME_CLARIFICATION`, with one short natural-language question when the window provides no
  supported answer and no useful backward-search anchor; or
- `NO_ANSWER`, when the relevant source range is exhausted and the messages do not answer the
  question.

For `ANSWERED`, the server maps cited aliases back to submitted messages and rejects unknown aliases.
It applies the existing deterministic answer validator for blank/size limits and message-GUID or
opaque-alias leakage. There is no second model verification call. Confidence and internal action
names may remain in service models and telemetry, but the main model never repeats them to the user.

### Progressive older windows

For `NEED_OLDER_MESSAGES`, the server—not the model—advances the source cursor to the next 500 older
eligible messages. Each older window is contiguous with the previous one and is again presented in
ascending timestamp order. This can continue until the model answers, requests clarification, the
hard user-supplied range is exhausted, the source is exhausted, or a page, character, model-call, or
deadline limit is reached.

The model is instructed to request an older window only when the current evidence contains a useful
reason to continue backward. When the first window contains no answer and no temporal anchor, it
should prefer `NEED_TIME_CLARIFICATION` over a blind scan. This is deliberately a model decision,
not a keyword rule.

Provisional findings returned with `NEED_OLDER_MESSAGES` are validated against that window and
carried forward as bounded structured findings. If an answer depends on messages from multiple
windows, one tools-disabled reduction call synthesizes the findings in chronological order. The
server expands reduction citations to original submitted message aliases before accepting the
answer. Raw earlier windows are not repeatedly appended to an ever-growing prompt.

### Empty, incomplete, and failed results

- An empty exhausted source yields a natural no-messages answer.
- `NO_ANSWER` yields a natural no-result answer, not a stock "insufficient evidence" sentence.
- `NEED_TIME_CLARIFICATION` is returned as a user-facing follow-up question. After the user supplies
  an approximate time, the main agent calls question mode again with the original question plus the
  new temporal context or with a hard range when one can be derived reliably.
- A bounded or interrupted scan may still produce a useful answer, but the QA prompt must qualify
  claims that depend on exhaustive history. If no supported answer exists when a safety limit is
  reached, the main agent asks for a narrower approximate time instead of inventing a conclusion.
- Source or model failure returns a short natural unavailable message. Internal reasons remain in
  logs and low-cardinality metrics, not user-facing text.
- If the request deadline is reached before a generated answer can be validated, the answer is not
  returned.

## Participant Identity Enrichment

History mapping uses a request-local identity cache and assigns every sender a stable display label.
The label preference order is:

1. `you` for the requesting canonical account;
2. nonblank `global_contact_name` on the canonical account;
3. nonblank `website_display_name` on the canonical account;
4. a matching BlueBubbles contact display name from a read-only contact lookup;
5. a stable masked participant label.

Read-only QA must not create or merge accounts. BlueBubbles contact lookup is cached once per unique
transport identity for the request and bounded by the same deadline. Contact lookup failure falls
through to the next label source rather than failing question answering.

For every cited participant that still has only a masked or unknown label, the question-mode tool
response includes a bounded unresolved identity hint containing the exact fallback label and the
normalized transport identity already associated with that source message. This hint is internal to
the tool result and is not persisted.

The main model may use its existing one-to-one conversation history and semantic memory to map that
identity or fallback label to a human name. It may replace the fallback label only when the existing
one-to-one context supports the mapping. It must not use unrelated memory to change the group-derived
facts, counts, dates, or answer. If no name can be resolved, it preserves a clear descriptive label
and says that the person could not be identified rather than inventing one. Raw transport identities
should not be repeated to the user unless they ask or the identity itself is relevant.

## Tool Response

Catch-up mode preserves its current JSON shape.

Question mode returns a deliberately smaller shape so the main model cannot blend summary artifacts
into the answer. An answered result looks like:

```json
{
  "disambiguation_required": false,
  "groups": [
    {
      "group": "Project chat",
      "answer": "Alex posted the only result so far, so Alex is currently leading among posted results.",
      "unresolved_participants": []
    }
  ]
}
```

A clarification result contains `clarification_question` instead of `answer`, for example, "About
when did that happen?" The main agent sends that question naturally and waits for the user's reply.

The question-mode response omits `summary`, `key_developments`, `decisions`, `open_questions`,
`status`, `confidence`, `model`, `fallback_used`, `retrieval_mode`, `coverage_status`,
`coverage_through`, `partial_reason`, message-window actions, cursors, and evidence counts. Those
fields are operational metadata and previously encouraged the main model to narrate the
implementation.

Unresolved identity hints are bounded to participants cited by the accepted answer. They are not a
participant directory and never include message text or message GUIDs.

## Main Agent Prompt

The main-agent developer prompt will describe behavior, not security implementation:

- Use `get_group_catchup` with the user's exact question for questions about another group or the
  current group's earlier messages.
- Supply exact `from` and `to` only for an absolute date or range the user clearly established. Pass
  relative phrases such as today or recently unchanged so question mode can interpret them from the
  message sequence.
- Treat the returned answer as group-derived facts, but phrase it naturally. If the tool returns a
  clarification question, ask it and wait for the user's approximate time range.
- Use one-to-one context only to resolve unresolved participant identities, not to add or alter group
  facts.
- Never mention authorization, coverage modes, retrieval, evidence validation, aliases, or internal
  insufficient-evidence states.
- Do not invent a broader hard range. Let question mode page backward internally, and call it again
  only after group disambiguation or after the user answers a time clarification.

The tool description removes the same internal vocabulary. It distinguishes summary mode from
question mode and explains model-driven temporal discovery versus explicit hard ranges.

## Model Routing, Limits, and Latency

The QA path continues to use the group-memory Responses API routing and price guard: GLM is primary
and GPT-4.1-mini is the single fallback while it remains within the configured price ceiling. No
fallback starts after the request deadline.

The initial and continuation message-window size defaults to 500 and is configurable. Existing
aggregate-character, page, model-call, and timeout properties remain unless implementation reveals
an unused planner-only property. Planner-specific term and neighbor-search properties are removed
from question mode and may be deleted when they have no other callers. The request timeout remains a
hard upper bound and is propagated to history, contact, generation, and reduction calls.

Expected normal recent-range work is:

1. one newest-message window retrieval;
2. zero or more cached read-only identity lookups; and
3. one QA generation.

There is no planning generation, exact-search attempt, or support-verification generation. Older
window retrieval occurs only when the QA model requests it. This should materially reduce the
observed latency while allowing intelligent deeper searches.

## Logging and Metrics

Questions, messages, answers, identity hints, raw identities, group names, GUIDs, account IDs, and
search intervals must not appear as metric tags. Existing low-cardinality question-answer metrics
remain, but their counts must reflect actual work:

- logical QA generation count;
- reduction count;
- message-window count and model-directed continuation/clarification outcome;
- provider attempt/fallback count;
- retrieved page and message counts;
- answer latency and outcome;
- completion state and low-cardinality failure type.

Planner and verifier metrics are removed from this path. Existing dashboard panels must be updated if
their queries depend on those operations. Application logs may record low-cardinality outcome and
timing but not transcript or identity content.

## Compatibility and Migration

This is an internal agent-tool behavior change. The tool schema is generated from its Java request
record, not OpenAPI, so no REST or generated-client change is required. Existing callers that omit
`question` receive the unchanged catch-up response. Question-mode response tests and main-agent
prompt tests change intentionally.

No data migration is required. Existing group enablement, journal messages, membership intervals,
summary segments, and proactive preferences remain valid.

## Testing

Implementation follows a red-green cycle and proves:

- A question about "today" is passed unchanged with current time and timestamped messages; no fixed
  date range is manufactured.
- With no hard range, question mode starts with the newest 500 eligible messages.
- An explicit absolute range is enforced exactly, while an explicit numeric lookback retains its
  existing meaning.
- Question mode retrieves complete contiguous windows without planner terms, exact-search calls,
  summary segments, semantic memory, or topical filtering.
- A single relevant post is described as the only posted result, not a tie or a complete-group
  comparison.
- Multiple relevant posts are compared in timestamp order and attributed to the correct sender.
- Arbitrary topics, languages, URLs, emails, phone numbers, quotes, and instruction-like text can
  support an answer without semantic filtering.
- Instructions embedded in group messages cannot call tools, change the QA task, or enter the raw
  main-agent context.
- Normal one-batch queries make one QA generation and no planner or support-verification calls.
- `NEED_OLDER_MESSAGES` retrieves the immediately preceding 500-message window with a server-owned
  cursor and can repeat within safety limits.
- A first window with no answer or useful time anchor returns a natural clarification question rather
  than blindly scanning all history.
- Multi-window answers preserve original citation membership through chronological reduction.
- Unknown GUIDs and aliases, blank output, and oversized output remain rejected by the deterministic
  validator.
- Known canonical names, website display names, BlueBubbles contact names, `you`, and masked fallbacks
  follow the specified precedence and use request-local caching.
- An unresolved participant hint is emitted only for a participant cited by the answer; the main
  model may resolve it from one-to-one context without changing the group facts.
- Question-mode JSON omits catch-up summaries and internal status, confidence, retrieval, provider,
  evidence, and failure metadata.
- Group context cannot query another group, one-to-one selection remains authorized and
  disambiguated, and every message is clipped to membership intervals.
- Page, character, aggregate, model-call, and deadline limits produce a qualified answer or a request
  for a narrower approximate time without overclaiming.
- GLM price routing and the single GPT-4.1-mini fallback remain bounded by the request deadline.
- Existing catch-up, proactive delivery, group-memory extraction, and deterministic validator tests
  stay green.

The implementation gate includes Spotless, focused QA/tool/prompt/identity tests, all memory and
memory-tool tests, the Spring context test, static scans proving no domain keywords or content
categories exist in production QA routing, and the applicable full test suite.

After the intended image is deployed, run a scoped production canary in an enabled test group:

1. post one fresh, uniquely identifiable fact;
2. ask from the authorized one-to-one chat about that fact using "today";
3. verify the model interprets "today" from the timestamped recent conversation, excludes an older
   irrelevant post, and names the author when either group identity data or one-to-one context
   supports the name;
4. verify the normal request uses one QA generation, has exactly one reply, and is materially faster
   than the previous multi-stage path; and
5. verify logs contain no raw group text or identity values.

## Success Criteria

- Precise group questions start from the newest 500 eligible messages and progressively search older
  contiguous windows when the model has a useful reason, without keyword planning or summary
  contamination.
- Relative time is interpreted from current time, timestamps, and conversation context; explicit
  absolute ranges remain deterministic.
- When the first window lacks both an answer and a temporal anchor, the agent asks for an approximate
  time range instead of guessing or blindly scanning.
- Group-derived facts may contain any relevant ordinary content; only instructions in message data
  are inert.
- Names resolve from canonical, website, contact, or supported one-to-one context without inventing
  identities.
- The main model never receives raw group messages and never exposes internal security or retrieval
  jargon.
- A normal recent query requires one QA generation and no verifier generation.
- Authorization, membership intervals, output size, message GUIDs, opaque aliases, resource limits,
  model price limits, and deadlines remain deterministically enforced.
- Focused and applicable regression tests pass, followed by a clean scoped production canary after
  deployment.
