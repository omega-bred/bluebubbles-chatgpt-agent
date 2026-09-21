# Optional Hindsight memory

BlueChat uses [Hindsight](https://hindsight.vectorize.io/) for long-term memory. The integration
uses the v0.10.0 bank, asynchronous retain, document, operation-status, and recall APIs.
There is no alternate provider or legacy scope fallback.

## Enable it

Memory is disabled by default. A deployment without Hindsight can run normally; no Hindsight
requests are sent, no outbox work is claimed, and the four memory tools are not registered.

```sh
HINDSIGHT_ENABLED=true
HINDSIGHT_BASE_URL=http://localhost:8888
# Set this if the Hindsight server requires bearer authentication:
HINDSIGHT_API_KEY=...
```

Keep the key on the backend. Never expose it to a browser or model: the server's tenant key can
access multiple banks, so BlueChat enforces all account and conversation authorization locally.
For local Gradle runs, the existing 1Password secret loader reads the Hindsight tenant key only
when `HINDSIGHT_ENABLED=true`. Other deployments can supply their own environment variables.

| Variable | Default | Purpose |
| --- | --- | --- |
| `HINDSIGHT_ENABLED` | `false` | Enable provider requests and memory tools |
| `HINDSIGHT_BASE_URL` | empty | Reachable Hindsight API URL |
| `HINDSIGHT_API_KEY` | empty | Optional backend bearer credential |
| `HINDSIGHT_REQUEST_TIMEOUT` | `PT10S` | Timeout per HTTP operation, clamped to 1–15 seconds |
| `HINDSIGHT_RECALL_MAX_TOKENS` | `4096` | Per-bank recall budget; clamped to 256–16384 |
| `HINDSIGHT_RECALL_MAX_BANKS` | `12` | Maximum banks per query; clamped to 1–32 |
| `BBAGENT_GROUP_MEMORY_ENABLED` | `false` | Existing group-memory feature switch; each group also opts in |

The repository's production manifest explicitly enables Hindsight using the internal API Service
and the `hindsight-secrets` 1Password item. Deployers using those manifests should remove the
Hindsight secret resource and environment entries when leaving memory disabled.

## Accounts, groups, and entities

A personal bank belongs to a canonical `agent_accounts.account_id`, shared across linked phone,
email, and LXMF identities. Account merges transfer local bank ownership to the surviving account;
existing remote banks remain accessible through those mappings. New writes use the survivor's
personal bank. A bank ID is never derived from a phone number or email address.

Group memory belongs to the conversation. Each exact audience has its own physical bank, so
Hindsight cannot consolidate content across differing audiences. A selected group artifact is
stored once, not copied to every participant. Entities describe subjects inside a bank; they
are not accounts, tenants, or access-control grants.

- Private recall searches the account's personal banks and authorized group audience banks.
- Group recall searches only the current group, after refreshing its roster. Every recipient must
  be authorized for each queried bank. Unknown or incomplete rosters fail closed.
- Joining does not grant older memory. Leaving preserves previously granted historical memory,
  but grants no later content. Rejoining grants no intervening content automatically.
- Automatic extraction grants the intersection of membership across its entire source window and the latest verified roster.
  Windows preceding the first verified roster receive no audience and are not retained remotely.
  This conservative intersection can omit otherwise useful facts from mixed-audience windows.
- Group content never automatically populates personal banks. Automatically extracted group
  artifacts are read-only through personal memory tools.

Only source facts (`world`, `experience`) are recalled. Observations, reflection, and mental models
are not used in replies. Every hit must resolve to a current local document with a matching content
hash, successful processing state, and valid authorization. Group artifacts also require confirmed
status, normal sensitivity, sufficient confidence, and unexpired provenance. Responses use the
validated source text, with group attribution, rather than trusting provider text as instructions.

## Retention and retrieval

Retention remains selective and topic-independent: useful durable context, meaningful changes,
constraints, and actionable commitments. Routine updates, isolated observations, repetition, and
trivia without ongoing consequences do not qualify. An empty extraction is successful. Personal
and group banks receive separate missions with this policy before the first retain.

Automatic group extraction still selects source artifacts locally before Hindsight ingestion;
explicit `memory_save` calls queue individual sources. Full journal history is not uploaded.
This preserves the existing selection policy while leaving Hindsight-native conversation extraction
for a separately evaluated change.

The agent is encouraged to retrieve early for substantive planning, advice, recommendations,
follow-ups, and references to people or projects, even when relevance is uncertain. Retrieval does
not require an explicit “remember” request. It skips trivial or self-contained messages and does
not infer consent from memory. Recall fans out to at most four banks concurrently, has a 12-second
overall wait budget, and returns up to 24 distinct sources / 16,000 characters. Personal banks are
searched first, then recent authorized group banks. Large histories beyond the configured bank
limit can require a more targeted conversation-history tool. Recall is local on our deployment;
retain/consolidation can still incur the configured model's cost.

## Durable writes, corrections, and deletion

`hindsight_memory_documents` is the outbox and source ledger. It records the physical bank, source
artifact (when applicable), stable document ID, content hash, operation UUID, and processing state.
The worker claims one document at a time with a lease. Async retains reuse their operation UUID
across lost acknowledgements and retries. A completed operation, including zero extracted facts,
finishes successfully. HTTP failures back off and stop after eight attempts; failed/cancelled
provider operations and operations stuck for over a day become `EXHAUSTED` for operator review.
Do not generate a new operation ID merely because an acknowledgement was lost.

Saves return “queued,” not “stored.” Corrections replace a completed document using a new operation
UUID and immediately hide its old version locally. Updates to a pending document ask the caller
to wait. Deletion immediately removes retrieval authorization, waits for any outstanding retain,
then deletes the provider document and checks that its document endpoint returns 404. A deletion
failure leaves the document hidden locally and visible as failed/exhausted work for the operator.
The existing `bbagent.memory.projection` metrics cover personal and group document processing.

Retain data and document mutations are asynchronous, so a queued save may not be found immediately.
A provider outage degrades recall to no results and leaves writes queued or visibly failed; other
agent functionality continues.

## Cutover from the removed provider

Flyway V32 deliberately discards the old provider mappings and projection backlog, retires existing
local extracted artifacts, and moves enabled conversations' extraction start time to cutover.
It creates empty Hindsight tables. It does **not** import any existing memories, backfill the journal,
or delete remote data from the former provider. Immutable historical Flyway migrations still mention
the old schema; they are not runtime compatibility code. Conversation journals and digest features
retain their existing local behavior. Deploy this schema and application together; rolling back to
an application expecting the dropped provider tables requires restoring a database backup.
