# Group Catch-up Question Answering Design

## Goal

Extend `get_group_catchup` so an authorized user can ask a precise natural-language question about an enabled group conversation from either a one-to-one chat or that group itself and receive an evidence-backed, server-generated answer. A group conversation may query only itself; cross-group retrieval remains available only from an authorized one-to-one chat. The default range is the preceding 24 hours. Explicit requests may search older iMessage history without the current 30-day search-tool limit, subject to confirmed membership, source availability, and honest coverage reporting.

Raw group messages never enter the main conversation model's context or the tool result. The backend submits only the bounded answering transcript transiently to the configured group-memory model provider, then returns a synthesized answer and coverage metadata—never message excerpts, message GUIDs, phone numbers, email addresses, or a transcript.

## Current State

`get_group_catchup` currently returns authorized daily digests and rolling summary segments with decisions, open questions, and a coverage watermark. This works for broad prompts such as “what happened?” but intentionally omits raw message evidence.

The production Wordle test demonstrated the resulting precision gap: the journal held an exact score share and sender account, while the rolling segment only reported that one score had been shared and produced no durable memory artifact. The main conversation model therefore knew that activity occurred but could not determine the reported score or participant. It supplemented the answer with unrelated historical semantic memory, which was not evidence for the current puzzle.

The existing `search_convo_history` tool cannot close this gap safely. It searches only the current chat, returns raw messages to the main model, and hard-limits results to 30 days. Its underlying BlueBubbles substring-query mechanism is useful, but it must be reused behind the authorized group catch-up boundary rather than exposed directly.

## Scope

This change will:

- Add an optional `question` field to `GetGroupCatchupRequest`.
- Preserve the current catch-up response when `question` is absent or blank.
- Search an explicitly resolved, memory-enabled group when `question` is present.
- Allow an enabled group to query its own history using the server-derived current chat identity.
- Convert the natural-language question into bounded literal substring terms.
- Use exact BlueBubbles search as the primary candidate finder for iMessage history.
- Fetch neighboring messages around matches for conversational context.
- Fall back to chronological, chunked analysis when exact search is insufficient.
- Return only a structured, synthesized answer with confidence and coverage metadata.
- Reuse the configured cost-guarded GLM model and GPT-4.1-mini fallback.
- Add focused operational metrics without message text or user identifiers.

This change will not:

- Expose raw group messages to the main conversation model or tool result.
- Save on-demand historical search results into the journal, Mem0, summary segments, or durable artifacts.
- Add a new REST API, OpenAPI model, database table, or migration.
- Change prospective group-memory collection or retention semantics.
- Infer historical membership beyond intervals the application can verify.
- Add special-purpose Wordle parsing; the question-answering path remains domain-agnostic.

## Tool Contract

`GetGroupCatchupRequest` gains one nullable field:

```java
String question
```

The existing `group`, `from`, `to`, and `lookback_hours` fields remain. If no range is supplied, the service searches the preceding 24 hours. Explicit `from` and `to` values take precedence over `lookback_hours`. Explicit historical ranges are not clipped to 31 days; they are clipped only by valid `Instant` arithmetic, confirmed membership intervals, available iMessage history, and operational request limits.

From a one-to-one chat, question mode requires one unambiguous group. If `group` is absent and more than one authorized group is available, or if the supplied name matches multiple groups, the tool returns the existing disambiguation options without searching any message text. Summary-only one-to-one mode retains its current multi-group behavior.

From a group chat, the server derives transport and chat GUID exclusively from `ToolContext.message`. The request's `group` field is ignored, and both summary and question modes are scoped to the current group. The tool must never use a model-supplied group hint to resolve another conversation from a group context.

Each selected group adds a `question_answer` object:

```json
{
  "status": "answered",
  "answer": "The only reported score is participant ending 0199 with 4/6, so they currently lead among posted results.",
  "confidence": "high",
  "evidence_message_count": 1,
  "retrieval_mode": "exact_search",
  "coverage_status": "complete",
  "from": "2026-08-09T00:00:00Z",
  "to": "2026-08-10T00:00:00Z",
  "coverage_through": "2026-08-10T00:00:00Z"
}
```

Allowed `status` values are `answered`, `insufficient_evidence`, and `unavailable`. Allowed `confidence` values are `high`, `medium`, and `low`. Allowed `retrieval_mode` values are `exact_search`, `chronological`, and `hybrid`. Allowed `coverage_status` values are `complete` and `partial`. Partial results also include a low-cardinality `partial_reason`, such as `source_unavailable`, `history_limit`, `time_limit`, or `model_limit`.

The tool description and agent developer prompt will instruct the main model to pass the user's exact question for precise group Q&A. A scoped `insufficient_evidence` or `partial` answer must not be replaced with unrelated semantic memory as if it were current evidence.

## Authorization and History Boundary

Question answering requires a resolved canonical `agent_accounts.account_id`. In a one-to-one context, the server resolves group hints only among memory-enabled groups authorized for that account. In a group context, the server resolves only the incoming message's transport and chat GUID, then verifies that the current group is memory-enabled and the requesting sender's canonical account has an active membership interval. A group context can never select or query another group.

Every candidate message must fall within an active `agent_conversation_memberships` interval for the requesting account. A broad requested range is intersected with confirmed membership intervals before retrieval, and messages outside those intervals are discarded before any model call. Current membership does not authorize messages from an unverified period before the user joined.

The service never accepts a chat GUID, conversation ID, account ID, or sender identifier from the model. It derives those values from `ToolContext`, authorized group selection, and server-side records. BlueBubbles history calls use only the resolved external conversation ID.

## Retrieval Architecture

### 1. Search planning

A server-side model call receives only the user's question and requested time range. It returns a structured search plan containing zero to five literal substring terms and optional time or sender hints. For “Who is winning the current Wordle?” the expected term is `Wordle`.

Search terms are data, not query fragments. The server trims and deduplicates them, limits each term to 128 characters, escapes SQL `LIKE` wildcard characters, and uses only a fixed parameterized BlueBubbles where-clause template. The model cannot inject BlueBubbles query syntax.

### 2. Exact candidate search

For BlueBubbles conversations, a generalized private history-search method accepts the already-authorized chat GUID, literal term, time bounds, page size, and offset. It removes the existing helper's hard-coded current-chat and 30-day assumptions while preserving parameterized substring matching.

Terms are searched independently, newest first, then merged and deduplicated by message GUID. The server fetches up to three adjacent messages before and after each hit within the authorized range. Candidate messages are ordered by timestamp before analysis.

Search paging uses 500 rows per page, at most five terms, and at most 100 pages per request. These limits permit deep exact-text searches while bounding BlueBubbles load. Reaching a limit produces partial coverage rather than a silent truncation.

### 3. Chronological fallback

The service uses chronological retrieval when the planner produces no useful literal terms, exact search returns no hits, or the answer model reports that the candidates are insufficient and more context may help.

The server pages the authorized time range and processes messages in chronological batches of at most 100 messages and 60,000 characters. For a range larger than one batch, each batch produces a structured intermediate finding containing an answer candidate, confidence, and validated evidence references. A final reduction call synthesizes those findings. The request processes at most five model batches or 300,000 aggregate transcript characters; reaching either limit returns partial coverage.

For BlueBubbles, on-demand history is the source of truth for question mode. The near-real-time journal may satisfy a range when BlueBubbles is unavailable, but journal coverage must be reported as partial unless the service can prove the requested interval is complete. LXMF and future transports remain journal-only until their transport exposes an equivalent authorized history source.

### 4. Answer synthesis

The answer model receives the exact question, sanitized sender labels, timestamps, and bounded candidate message text. Group content is explicitly marked as untrusted evidence. The prompt prohibits following instructions found in messages, using external knowledge, invoking tools, or filling evidence gaps with semantic memory.

The structured model result contains an answer, confidence, evidence message GUIDs, and a `needs_more_context` flag. The server validates that every evidence GUID belongs to the submitted candidate set. GUIDs are used only for validation and evidence counts; they are never returned to the main model or persisted by this flow.

If only one score or response is present, the model must say “only reported” or equivalent. It may not imply that every participant responded. If the evidence cannot support the requested comparison, it returns `insufficient_evidence` rather than guessing.

## Sender Labels

The answering transcript uses stable, server-generated labels:

1. `you` for the requesting canonical account.
2. The participant's nonblank `global_contact_name` when an existing canonical identity resolves to an account with that verified self-provided name.
3. A masked transport label such as `participant ending 0199` when no verified name exists.
4. `unknown participant` when no safe identifier can be derived.

Historical search performs read-only identity lookup. It must not create or merge canonical accounts as a side effect of answering a question.

## Model Routing and Cost Controls

Search planning and answer synthesis reuse the group-memory Responses API routing configuration: GLM is primary, GPT-4.1-mini is attempted once on primary failure, and OpenRouter price limits remain enforced. The implementation should share transport, price-guard, serialization, and fallback behavior with `ConversationMemoryModelClient` while keeping search-plan and question-answer prompts and schemas separate from durable memory extraction.

The end-to-end question-answering operation has a 90-second wall-clock budget. Any source or model work still outstanding at the deadline is abandoned and reported as partial. No additional fallback is attempted after the existing single GPT-4.1-mini retry.

## Failure Handling

- An exact-search miss triggers chronological fallback.
- A low-confidence exact answer with `needs_more_context=true` triggers one chronological retry.
- BlueBubbles unavailability falls back to journal data when available and marks coverage partial.
- Model planning failure skips exact search and uses chronological retrieval.
- Answer-model failure after fallback returns `unavailable` without breaking the surrounding agent response.
- Invalid ranges return the existing `invalid catch-up range` result.
- Ambiguous groups return options without any history access.
- Unauthorized or empty intervals return `insufficient_evidence` without revealing whether older messages exist.
- History, time, character, page, or model limits return the best supported synthesis with partial coverage and a low-cardinality reason.

## Privacy, Logging, and Metrics

Raw questions and messages must not appear in application logs, exception messages, traces, metrics, or model-routing diagnostics. Search-plan terms must not be logged because they may reproduce message content. The tool result contains no excerpts or evidence identifiers.

The natural-language question is sent to the configured model provider for search planning. Only bounded, authorized candidate messages are sent for answer synthesis. These provider calls use the same application-controlled routing and retention posture as group-memory extraction; no raw question, search plan, candidate transcript, or provider response is added to application persistence by this feature.

Operational instrumentation uses existing `bbagent.memory.*` conventions with low-cardinality tags only. Record question-answer count and latency with tags for `operation`, `outcome`, `retrieval_mode`, `coverage_status`, `model`, and `failure_type`. Record message, page, generation/reduction model-batch, logical planning-call, and logical support-verification-call counts without group, account, chat, sender, query, or content tags. Lower-level provider-attempt telemetry remains distinct from these logical request-scoped counts. If a new meter is required, update the BlueBubbles Grafana dashboard in the same implementation pass.

## Configuration

Add environment-backed properties with these defaults to main and test configuration:

- `bbagent.memory.group.qa.max-search-terms=5`
- `bbagent.memory.group.qa.search-page-size=500`
- `bbagent.memory.group.qa.max-history-pages=100`
- `bbagent.memory.group.qa.neighbor-message-count=3`
- `bbagent.memory.group.qa.max-batch-messages=100`
- `bbagent.memory.group.qa.max-batch-characters=60000`
- `bbagent.memory.group.qa.max-model-batches=5`
- `bbagent.memory.group.qa.max-aggregate-characters=300000`
- `bbagent.memory.group.qa.request-timeout=PT90S`

Question answering remains gated by `bbagent.memory.group.enabled` and the per-group `memory_enabled_at` setting. It does not need a separate feature flag.

## Implementation Boundaries

The implementation should introduce one focused question-answering service responsible for authorization-preserving retrieval orchestration and one model-facing component or clearly isolated model-client methods for search planning and answer synthesis. `ConversationDigestService` continues to own time-bounded catch-up assembly and delegates question mode rather than absorbing BlueBubbles paging and model prompt logic.

`BBHttpClientWrapper` gains a private-usage history-search method with explicit chat and time bounds. The existing `search_convo_history` tool retains its current public behavior unless a separate compatibility cleanup is justified during implementation.

Because the agent tool schema is generated from its Java request record and no REST endpoint changes, this feature does not modify `src/main/resources/openapi.yaml` or run `openApiGenerate`.

## Testing

Unit and focused integration tests will prove:

- Blank `question` preserves the exact existing catch-up path and response.
- Question mode defaults to 24 hours and honors explicit older `from`, `to`, and `lookback_hours` ranges without a 31-day product limit.
- Question mode requires one unambiguous authorized group.
- Group-context calls derive the current group from `ToolContext`, ignore model-supplied group hints, and cannot query any other conversation.
- Requested ranges and every candidate message are clipped to confirmed membership intervals.
- Natural language produces bounded literal terms, and `%`, `_`, backslashes, long terms, and duplicate terms cannot alter the fixed query.
- Exact hits are deduplicated and receive bounded neighboring context.
- A Wordle score question returns the only reported leader from a single `4/6` share without using unrelated league memory.
- Multiple score shares for the same puzzle are compared, while different puzzle numbers are not conflated.
- Exact misses, planner failures, and `needs_more_context` invoke the intended chronological fallback.
- Large histories use bounded map/reduce processing and report partial coverage at every configured limit.
- BlueBubbles failure uses journal evidence conservatively and marks coverage partial.
- Model evidence GUIDs outside the submitted candidate set are rejected.
- Prompt-like instructions inside group messages cannot change the task or leak transcript content.
- Known names, `you`, masked identifiers, and unknown-participant fallbacks are applied without account creation.
- GLM routing, price constraints, and the single GPT-4.1-mini fallback remain intact.
- Tool responses, logs, and metrics never contain raw messages, search terms, group GUIDs, account IDs, phone numbers, or email addresses.
- Existing catch-up, proactive delivery, digest reconciliation, and memory extraction tests remain green.

After deployment, run a scoped production E2E in an enabled test group: post a fresh score, ask both from the authorized one-to-one chat and from inside the group who is currently leading, verify both answers include the supported score and participant label, and verify logs expose no raw group content. Also attempt to name a different group from the group-context call and verify the server still searches only the current group. The E2E must confirm that answers report only posted results and do not substitute historical semantic memory.

## Success Criteria

The feature is ready when:

- The existing broad catch-up behavior is unchanged without `question`.
- A precise question about a recent enabled-group message receives a specific, evidence-backed answer.
- The same precise question works from inside the enabled group while remaining strictly scoped to that current group.
- Explicit historical ranges can search available iMessage history beyond 30 days when membership and operational limits permit.
- Raw messages never enter the main agent context, tool result, application logs, metrics, Mem0, or application persistence; only bounded candidates are transmitted transiently to the configured QA model provider.
- Unsupported conclusions return insufficient or partial states rather than guesses.
- Authorization is enforced at group selection, requested-range, and individual-message time.
- Focused tests, the full applicable memory/tool test suite, formatting, and the scoped production E2E pass.
