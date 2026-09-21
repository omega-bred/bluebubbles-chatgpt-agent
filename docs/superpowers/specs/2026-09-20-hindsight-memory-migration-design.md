# Hindsight Personal and Group Memory Migration

**Date:** 2026-09-20

**Status:** Implemented in source; clean cutover without migration or backwards compatibility. See [operational documentation](../../hindsight-memory.md).

## Decision

Replace Mem0 with Hindsight using both personal and shared group memory. A personal bank belongs
to a canonical `agent_accounts.account_id`. A logical group bank belongs to an internal
`agent_conversations.conversation_id`. Entities describe people and other subjects within a bank;
they do not establish ownership, identity linkage, or permission to read it.

This revises the initial account-bank-only migration proposal. Group artifacts are retained once
per authorized audience, rather than copied into each participant's personal bank.

The application database remains authoritative for canonical identity, group membership, source
evidence, audience snapshots, artifact status, and explicit sharing. Hindsight provides extraction,
retrieval, and scoped synthesis; it does not replace these authorization records.

## Ownership and retrieval

| Context | Read | Write |
| --- | --- | --- |
| Private conversation | Own personal bank; relevant group memory authorized for that account | Personal memory, unless an explicit action targets an authorized group |
| Group conversation | Memory of the current group authorized for every current recipient | Shared group memory |
| Explicit sharing | Authorized source selected by the owner | A new, narrowly scoped copy in the destination group |

Group responses must not load personal banks or other groups' banks. Merely mentioning someone
in a group does not authorize storing that content in their personal bank. A private answer may
combine independently authorized personal and group results, with source attribution preserved.
It must not silently persist the combined answer back into either bank.

Resolve bank IDs on the server from trusted account and conversation records. Model-supplied
bank IDs, entities, tags, or claimed memberships cannot authorize a request. Keep the Hindsight
service credential exclusively on the backend; its current shared tenant key is not per-user auth.

Use opaque bank names such as `bluechat-account-<account-id>` and
`bluechat-group-<conversation-id>-audience-<audience-id>`. Do not use phone numbers or email
addresses as bank identifiers.

## Group audience boundaries

One logical group can need several physical Hindsight banks. Partition group content by immutable
audience snapshots, using an application-owned audience ID. Artifacts with the same exact audience
may share a physical bank; differing audiences must not consolidate together.

- New artifacts receive application-owned audience snapshots. Existing artifacts are not imported.
- For a newly retained source window, derive its audience from verified membership over that
  window. The implementation conservatively uses the intersection across the entire extraction
  input; splitting windows at changes is a possible later recall-quality improvement.
- In a private conversation, select only audience banks that include the requesting account.
- For a group response, select only audience banks that include **every** verified current
  recipient. Adding a member does not grant access to older banks.
- If the current roster or historical evidence is insufficient to establish permission, omit
  the affected group memory.
- Preserve the current historical access semantics: leaving a group does not automatically erase
  access to memories already granted to that account. Do not expand this into access to subsequent
  messages. Explicit revocation must also block retrieval and invalidate affected derived content.

This partitioning protects extraction, consolidation, and reflection before they see the content.
Post-filtering a synthesized answer is insufficient. Tags remain useful for source and topic
selection, but are not the sole authorization boundary in this first implementation.

Do not enable cross-audience observations or mental models. Initially use `recall` for source facts
and let the existing agent answer from authorized results. Enable consolidated observations only
after provenance, correction, and deletion tests pass. An observation must resolve all its source
facts to permitted, active application records; missing or truncated provenance fails closed.
Keep `reflect` opt-in until equivalent authorization checks are demonstrated for its entire input.

## Retention and insight quality

Preserve the topic-independent policy deployed in `0ea305e6`:

- Retain durable context, meaningful changes, shared constraints, and commitments with concrete
  future usefulness. Significant one-time events qualify when their consequences persist.
- Omit routine updates, isolated observations, and historical minutiae without ongoing value.
- Confidence that a statement is true does not establish its usefulness. Retaining nothing is a
  normal successful outcome. Repetition is not new knowledge.
- Preserve uncertainty and evidence; do not promote an inference into a confirmed shared fact.
- Do not persist batch-local participant labels as if they were stable identities.

Personal and group banks get different `retain_mission` and `observations_mission` settings:
personal memories describe that account's useful context; group memories describe shared context
without inferring individual preferences as collective facts. No topic-specific blacklist.

For the first cutover, retain only newly selected artifacts and newly saved explicit memories. This is
a deliberate transitional choice: Hindsight's upstream guidance prefers original structured
conversations, since pre-summarizing loses relationships and temporal context. Do not run both
full extraction pipelines permanently without measuring their cost and quality.

In a subsequent evaluation, compare our selection with Hindsight-native extraction using bounded,
audience-homogeneous original conversation windows, timestamps, stable pseudonymous authors, and
source context. Move selection into Hindsight only when it meets the same usefulness and privacy
criteria. Do not bulk-ingest the historical journal as part of this migration.

## Provider contract and persistence

Use a Hindsight client for retain/upsert, recall, operation status, and document deletion.
Wire memory save/get/update/delete tools and automatic group memory through the durable outbox.
The tools retain their user-facing meanings; provider IDs are implementation details.

Persist the logical scope, physical bank, stable source document ID,
content hash, operation ID, and processing state. Replace assumptions that one write produces one
`mem0_memory_id`: a Hindsight document can yield several facts, and synthesized observations can
have multiple sources. Preserve source GUIDs and artifact IDs as provenance.

Use stable document IDs derived from application record IDs. Generate and persist an operation
UUID before asynchronous submission; reuse it when retrying that same attempt. A new content
version gets a new operation ID but keeps its document ID. Track queued, processing, completed,
and failed outcomes separately. A completed write yielding no facts is successful, not retryable.
Bound retries and concurrency; surface terminal failures instead of retrying forever.

Corrections replace the stable document or explicitly supersede the corresponding source.
Deletion immediately removes application authorization, then deletes provider content and verifies
that derived observations no longer expose it. Do not equate an HTTP acknowledgement with completed
forgetting. Mental models require separate invalidation/refresh verification before enabling them.

Account merges must preserve access to existing bank mappings while migrating them to the surviving
canonical account. Normalize audience membership through the resolver as well. An identity alias
must never create a second personal bank or orphan previously granted group memory.

## Clean cutover

The final user instruction supersedes the earlier migration proposal: fully remove the former
provider, import no existing memories, and provide no backwards compatibility. V32 retires old
local artifacts, drops old provider mappings and projection jobs, and advances enabled groups'
extraction start times. It leaves remote former-provider data untouched and starts Hindsight empty.

Memory is disabled by default and requires an explicit enable flag and reachable URL. The production
manifest uses the existing internal Hindsight service and 1Password tenant credential. Verify the
source changes and live Hindsight contract before deploying the schema and application together.
Source-fact recall is the initial retrieval path; observations and reflection remain outside it.

## Required verification

- Linked phone, email, and LXMF identities resolve to one personal memory scope, including merges.
- Private memory never enters a group request; unrelated groups remain isolated.
- Joining, leaving, rejoining, stale rosters, and partial evidence respect audience snapshots.
- A group response uses only memories visible to every current recipient.
- Cross-source observations cannot bypass those boundaries; incomplete provenance is rejected.
- Lost acknowledgements and repeated submissions do not create duplicate ingestion jobs; completed
  empty extractions do not retry.
- Replacing or deleting a document removes stale facts and their influence on recall and synthesis.
- Useful commitments and stable context survive across varied topics; transient detail and
  repeated mentions do not accumulate. Evaluate real model output, not just mocked parser results.
- Store one new logical group fact once per audience, rather than once per member, and prove retrieval
  from both private and group contexts.
- Production verification covers retain completion, authorized recall, correction, deletion,
  background consolidation, latency, and restart/queue health using bounded synthetic records.

## References

- [Hindsight best practices](https://hindsight.vectorize.io/best-practices)
- [Bank configuration](https://hindsight.vectorize.io/developer/api/memory-banks)
- [Retain and retry semantics](https://hindsight.vectorize.io/developer/api/retain)
- [Recall and source provenance](https://hindsight.vectorize.io/developer/api/recall)
- [Document lifecycle](https://hindsight.vectorize.io/developer/api/documents)
- [Deployment task](codex://threads/01a0c155-02ed-7dd0-bf76-56ef6663d2c0)

Implementation must check these contracts against the deployed version; current upstream docs may
describe newer behavior. Hindsight storage is self-hosted, but the current GLM 5.2 processing path
uses LiteLLM and hosted inference.
