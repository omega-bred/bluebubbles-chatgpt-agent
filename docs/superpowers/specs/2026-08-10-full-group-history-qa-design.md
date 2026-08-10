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
tools-disabled QA model can read the complete bounded transcript for the requested period directly.
The main conversation model can then use its existing one-to-one context to resolve a participant
that the group-history service could not name.

This design supersedes the retrieval, synthesis, sender-label, prompt, tool-response, and success
criteria sections of `2026-08-09-group-catchup-question-answering-design.md`. The earlier group
selection and membership authorization boundaries remain in force. The minimal deterministic
output boundary from `2026-08-09-question-answer-boundary-simplification-design.md` also remains in
force.

## Goals

- Answer arbitrary natural-language questions from every eligible text message in the requested
  group and time range, without domain keywords or semantic pre-filtering.
- Treat explicit periods such as "today" as calendar periods, not rolling lookbacks.
- Allow all relevant group content to inform the answer, including names, quoted text, links, email
  addresses, phone numbers, and other ordinary message content.
- Keep group messages isolated from the tool-capable main conversation model so instructions inside
  messages cannot invoke tools or alter the main agent.
- Resolve participant names from all existing trustworthy application sources and let the main model
  use one-to-one context when server-side identity enrichment remains incomplete.
- Return a user-ready answer with natural wording and no internal authorization, retrieval,
  coverage, confidence, or verifier terminology.
- Reduce the normal recent-range path to one history retrieval and one QA model generation.
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
2. Question mode, when `question` is present, returns only a direct answer for the selected group and
   requested range. It does not return or consult catch-up summaries, digest segments, semantic
   memory, decisions, or open questions.

Question mode is content-generic. The service retrieves the chronological message stream and asks
the QA model to answer the exact question. It does not generate search terms and does not call the
BlueBubbles exact-text search API. The group itself may query only its own history; a one-to-one chat
may query a group that the requesting canonical account is authorized to access.

The main agent renders the result conversationally. It must not tell the user that history was
authorized, that coverage was authoritative, that a verifier accepted or rejected a claim, or that
the result has an internal insufficient-evidence status. When the requested history does not answer
the question, it should say so naturally, for example, "I don't see anyone posting that yet."

## Time-Range Semantics

The tool request continues to accept `from`, `to`, and `lookback_hours`.

- Explicit `from` and `to` instants remain the authoritative server range.
- For a user-specified calendar period such as today, yesterday, or this week, the main agent must
  convert the period into exact `from` and `to` instants in the user's applicable IANA timezone and
  pass both fields. It must not substitute `lookback_hours`. An ongoing period ends at the current
  instant, so "today" means local midnight through now rather than midnight through a future time.
- The main model uses a timezone explicitly supplied by the user or already established in
  one-to-one context. Once the zone is known, it may obtain the current zoned time through the
  existing time tool. If the timezone is genuinely unknown and the calendar boundary could change
  the answer, the main agent asks a short clarifying question instead of guessing. It must not infer
  the user's zone from the server JVM default.
- When the user supplies no period, the existing preceding-24-hours default remains.
- The interval is half-open: `from` is inclusive and `to` is exclusive. The tool echoes the searched
  instants so the main model can avoid overclaiming the period.
- Explicit historical ranges may go back as far as the source retains messages, subject to verified
  membership intervals and operational bounds.

No natural-language date parser is added to the server. Date interpretation remains with the main
model, while the server validates the resulting instants and enforces the exact interval.

## Authorization and Isolation Boundary

The security boundary remains deterministic and server-owned:

- The canonical requester comes from `ToolContext`; the model cannot supply an account ID.
- From a group chat, transport and chat GUID come from the incoming message. A supplied group hint is
  ignored, so the group can query only itself.
- From a one-to-one chat, group resolution considers only memory-enabled groups currently available
  to the canonical account. Ambiguous names return disambiguation options before history retrieval.
- The requested interval is intersected with confirmed membership intervals. Each returned message
  is checked against those intervals before it enters a model prompt.
- History calls use only the server-resolved external conversation identifier.

Group messages are untrusted data. They are sent only to a dedicated tools-disabled QA model call,
inside a clearly delimited evidence structure. Its developer prompt says that message text may be
quoted or analyzed but never followed as an instruction. The QA model has no tool definitions,
semantic memory, one-to-one transcript, or authority to perform side effects.

The QA prompt has no content-category prohibitions. Relevant names, messages, URLs, email addresses,
phone numbers, identifiers, and quoted text may appear in the synthesized answer. Prompt-injection
defense comes from model/tool isolation and instruction hierarchy, not English keyword filters or
content redaction.

The raw transcript never enters the main model. The main model receives only the synthesized answer,
the exact searched interval, and bounded identity hints for participants cited by that answer.

## Retrieval and Synthesis

### Complete chronological retrieval

Question mode calls the chronological retriever immediately. It pages through every eligible text
message in ascending timestamp order over the authorized interval. No planner, literal term, sender
hint, digest, summary segment, or semantic index influences which messages are selected.

"Complete" means every eligible source message in the authorized requested interval until a declared
source or safety bound is reached. Existing fixed page, message, character, aggregate-character, and
deadline limits remain configurable. Source unavailability or reaching a limit produces an
internally partial result; the service must never silently label a truncated result complete.

The journal remains a source fallback when BlueBubbles is unavailable. It may be considered complete
only when the application can prove journal coverage for the full requested interval; otherwise the
result is internally partial.

### Normal range

When the transcript fits in one configured model batch, the QA model receives the exact question,
searched interval, ordered messages, participant labels, timestamps, opaque evidence aliases, and
message text. It returns:

- a direct natural-language answer;
- the opaque evidence aliases supporting the answer; and
- a bounded list of participant labels referenced in the answer.

The server maps cited aliases back to submitted messages and rejects unknown aliases. It applies the
existing deterministic answer validator for blank/size limits and message-GUID or opaque-alias
leakage. There is no second model verification call. Confidence and internal status may remain in
service models and telemetry, but they do not instruct the main model to repeat internal phrasing.

### Long range

When the transcript exceeds one model batch, the server divides it into contiguous chronological
chunks without semantic filtering. Each tools-disabled map call receives the same exact question and
produces a question-relevant finding with opaque citations. One tools-disabled reduction call sees
all findings in chronological order and produces the final answer.

The server expands reduction citations to the original submitted message aliases before accepting
the result. The same minimal output validator applies. A long-range query therefore costs multiple
model calls only when the transcript cannot fit safely into one call; it does not invoke an extra
planner or verifier.

### Empty, incomplete, and failed results

- An empty complete interval yields a natural no-messages answer.
- Messages that do not answer the question yield a natural no-result answer, not a stock
  "insufficient evidence" sentence.
- A partial transcript may still produce a useful answer, but the QA prompt must qualify claims that
  depend on completeness. The tool carries an internal completion flag so the main model cannot turn
  a partial comparison into a definitive winner or total.
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
into the answer:

```json
{
  "disambiguation_required": false,
  "groups": [
    {
      "group": "Project chat",
      "answer": "Alex posted the only result so far, so Alex is currently leading among posted results.",
      "from": "2026-08-10T07:00:00Z",
      "to": "2026-08-10T14:00:00Z",
      "complete": true,
      "unresolved_participants": []
    }
  ]
}
```

The question-mode response omits `summary`, `key_developments`, `decisions`, `open_questions`,
`status`, `confidence`, `model`, `fallback_used`, `retrieval_mode`, `coverage_status`,
`coverage_through`, `partial_reason`, and evidence counts. Those fields are operational metadata and
previously encouraged the main model to narrate the implementation.

When retrieval is incomplete, `complete` is `false`. The agent prompt tells the main model to qualify
only conclusions whose accuracy depends on exhaustive coverage, using ordinary language. The tool
does not expose the internal failure reason.

Unresolved identity hints are bounded to participants cited by the accepted answer. They are not a
participant directory and never include message text or message GUIDs.

## Main Agent Prompt

The main-agent developer prompt will describe behavior, not security implementation:

- Use `get_group_catchup` with the user's exact question for questions about another group or the
  current group's earlier messages.
- Supply exact `from` and `to` values for explicit calendar periods; do not use a rolling lookback for
  words such as today or yesterday.
- Treat the returned answer as group-derived facts for that interval, but phrase it naturally.
- Use one-to-one context only to resolve unresolved participant identities, not to add or alter group
  facts.
- Never mention authorization, coverage modes, retrieval, evidence validation, aliases, or internal
  insufficient-evidence states.
- Do not call the tool repeatedly with broader ranges unless the user requested a broader range or
  the first response asks for disambiguation.

The tool description removes the same internal vocabulary. It distinguishes summary mode from
question mode and explains the exact-range rule.

## Model Routing, Limits, and Latency

The QA path continues to use the group-memory Responses API routing and price guard: GLM is primary
and GPT-4.1-mini is the single fallback while it remains within the configured price ceiling. No
fallback starts after the request deadline.

Existing batch, aggregate-character, page, and timeout properties remain unless implementation
reveals an unused planner-only property. Planner-specific term and neighbor-search properties are
removed from question mode and may be deleted when they have no other callers. The request timeout
remains a hard upper bound and is propagated to history, contact, generation, and reduction calls.

Expected normal recent-range work is:

1. one paged chronological history retrieval;
2. zero or more cached read-only identity lookups; and
3. one QA generation.

There is no planning generation, exact-search attempt, support-verification generation, or duplicate
chronological retry. This should materially reduce the observed latency while improving completeness.

## Logging and Metrics

Questions, messages, answers, identity hints, raw identities, group names, GUIDs, account IDs, and
search intervals must not appear as metric tags. Existing low-cardinality question-answer metrics
remain, but their counts must reflect actual work:

- logical QA generation count;
- reduction count;
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

- A question about "today" sends exact calendar-day bounds and excludes a prior-day message that is
  still inside a rolling 24-hour window.
- An omitted period retains the 24-hour default.
- Question mode retrieves every eligible chronological message in range without planner terms,
  exact-search calls, summary segments, or semantic memory.
- A single relevant post is described as the only posted result, not a tie or a complete-group
  comparison.
- Multiple relevant posts are compared in timestamp order and attributed to the correct sender.
- Arbitrary topics, languages, URLs, emails, phone numbers, quotes, and instruction-like text can
  support an answer without semantic filtering.
- Instructions embedded in group messages cannot call tools, change the QA task, or enter the raw
  main-agent context.
- Normal one-batch queries make one QA generation and no planner or support-verification calls.
- Long transcripts use contiguous chronological map/reduce batches and preserve original citation
  membership through reduction.
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
- Page, character, aggregate, and deadline limits produce `complete=false` without overclaiming.
- GLM price routing and the single GPT-4.1-mini fallback remain bounded by the request deadline.
- Existing catch-up, proactive delivery, group-memory extraction, and deterministic validator tests
  stay green.

The implementation gate includes Spotless, focused QA/tool/prompt/identity tests, all memory and
memory-tool tests, the Spring context test, static scans proving no domain keywords or content
categories exist in production QA routing, and the applicable full test suite.

After the intended image is deployed, run a scoped production canary in an enabled test group:

1. post one fresh, uniquely identifiable fact;
2. ask from the authorized one-to-one chat about that fact using "today";
3. verify the answer includes only the current calendar day's post and names the author when either
   group identity data or one-to-one context supports the name;
4. verify the normal request uses one QA generation, has exactly one reply, and is materially faster
   than the previous multi-stage path; and
5. verify logs contain no raw group text or identity values.

## Success Criteria

- Precise group questions are answered from the complete eligible chronological transcript for the
  exact requested interval, without keyword planning or summary contamination.
- Calendar periods are calendar-correct in the applicable timezone.
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
