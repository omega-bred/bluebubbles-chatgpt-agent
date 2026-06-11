# iMessage ingress contract

Endpoint: `POST /api/v1/bluebubbles/messageReceived.message`

Contract version: `imessage.ingress.v1` (current)

## Event handling

- `new-message`: validated, normalized, and queued for async dispatch to `BBMessageAgent`.
- `updated-message`: validated, normalized, and queued for async dispatch to `BBMessageAgent`.
- `typing-indicator`: acknowledged and ignored.

## Canonical inbound schema

The versioned canonical schema for downstream integrations is checked in at:

- `docs/contracts/imessage-ingress/v1/canonical-ingress-event.schema.json`

This schema normalizes sender identity, chat metadata, message text, and media placeholders.

## Webhook validation contract (`new-message` and `updated-message`)

Required fields:

- `type`
- `data`
- `data.guid`
- `data.handle`
- `data.handle.address`
- `data.chats` with at least one element
- `data.chats[0].guid`

If any required field is missing, ingress returns HTTP 400 with a machine-readable error code.

## Response contract

- `200 {"status":"queued"}`: message event accepted into ingress queue.
- `200 {"status":"dropped_queue_full"}`: queue saturated; event acknowledged but not enqueued.
- `200 {"status":"ok"}`: non-message event acknowledged (for example `typing-indicator`).
- `200 {"status":"ignored"}`: message event normalized to null payload and skipped.
- `400 {"status":"error","error":"<error_code>"}`: payload contract violation.

Current error codes:

- `missing_request_type`
- `missing_data`
- `missing_message_guid`
- `missing_handle`
- `missing_sender_address`
- `missing_chat`
- `missing_chat_guid`

## Retry semantics

Ingress retry behavior should be treated as follows:

- `200 queued`: terminal success, do not retry.
- `200 ok`: terminal success for ignored non-message events, do not retry.
- `200 ignored`: terminal success for intentionally ignored message event, do not retry.
- `200 dropped_queue_full`: transient capacity signal; retry with backoff.
- `400`: terminal payload contract failure, do not retry until payload is fixed.
- `5xx` or network timeout: transient, retry with exponential backoff and jitter.

Recommended retry policy for transient failures:

1. Attempt 1 immediately.
2. Attempt 2 after 1s.
3. Attempt 3 after 2s.
4. Attempt 4 after 4s.
5. Attempt 5 after 8s, then dead-letter.

Idempotency guidance:

- Use `data.guid` as the dedupe key when retrying transient failures.
- Never mutate `data.guid` across retries of the same logical message.

### Retry examples

- Example A (`400 missing_handle`): stop retries, fix payload shape, replay once corrected.
- Example B (`200 dropped_queue_full`): retry with backoff, preserving original `data.guid`.
- Example C (`503 upstream unavailable`): retry with backoff, preserving original `data.guid`.

## Versioning guidance

- Backward-compatible additive fields: keep same major version (`imessage.ingress.v1`).
- Breaking changes (remove/rename/semantic change): publish `v2` schema path and dual-read during migration.
- Keep fixture transcripts for every version to prevent accidental contract drift.

## Local sandbox harness

Run the harness against a local server:

```bash
./scripts/imessage-ingress-sandbox.sh
```

Optional override:

```bash
IMESSAGE_INGRESS_URL=http://localhost:8080/api/v1/bluebubbles/messageReceived.message ./scripts/imessage-ingress-sandbox.sh
```

The harness posts replay fixtures and fails fast if any response status or expected error code differs from contract.
