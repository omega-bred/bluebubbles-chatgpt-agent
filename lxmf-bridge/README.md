# LXMF Bridge

Minimal text-only LXMF bridge for the agent.

The bridge listens for LXMF deliveries, forwards normalized inbound messages to
`/api/v1/lxmf/receive.messages`, and exposes `POST /api/v1/messages/send` for
agent replies.

The same image also includes `canary.py`, a one-shot LXMF free-tier canary. It
creates a fresh identity, sends the configured canary marker with a nonce
inference prompt, checks the configured Terms requirement, handles either replayed or follow-up inference,
and writes `bbagent_lxmf_canary_*` measurements directly to InfluxDB.

Important environment variables:

- `LXMF_BRIDGE_WEBHOOK_SECRET`: shared secret for both bridge directions.
- `LXMF_BRIDGE_LISTEN_HOST`: HTTP listen host, defaulting to `0.0.0.0`.
- `LXMF_BRIDGE_LISTEN_PORT`: HTTP listen port, defaulting to `8091`.
- `AGENT_BASE_URL`: base URL of the Spring agent service.
- `RNS_CONFIG_DIR`: Reticulum config directory.
- `LXMF_STORAGE_DIR`: LXMF router storage directory.
- `LXMF_DISPLAY_NAME`: announced LXMF display name.
- `RNS_BACKBONE_REMOTE`: Reticulum backbone host, defaulting to the rnode service.
- `RNS_BACKBONE_PORT`: Reticulum backbone port, defaulting to `4242`.

Canary-specific environment variables:

- `CANARY_RUN_ID`: optional nonce override; defaults to eight random hexadecimal
  characters, which the prompt asks the model to echo after `CANARY_OK`.
- `CANARY_MARKER`: marker prefix that causes the Spring app to flag the account
  as synthetic canary traffic.
- `CANARY_TERMS_REQUIRED`: expected app agreement requirement, default `true`.
  When enabled, the canary requires the Terms prompt and sends `YES`; when
  disabled, it requires an immediate inference reply and never sends `YES`.
  A mismatched mode fails the canary. Match `BBAGENT_TERMS_REQUIRED` in the app.
- `CANARY_STRICT_INFERENCE_RESPONSE`: defaults to `false`. A nonempty reply from
  the expected bridge proves a healthy round trip even if the model copies the
  nonce incorrectly or replies with other text. Logs and the existing run-count
  and duration measurements report `response_match=matched` or `mismatched`;
  both count as success and keep `bbagent_lxmf_canary_up=1`. Set to `true` to fail
  immediately with `failure_type=response_mismatch` on unexpected reply text.
  A missing reply (including only empty or unrelated-source messages) still
  fails with `failure_type=reply_timeout`. Failures before inference is checked
  use `response_match=not_checked`. The agreement-mode check remains enforced.
- `LXMF_BRIDGE_BASE_URL`: HTTP base URL for the production bridge.
- `INFLUX_DB_URI`, `INFLUX_DB_TOKEN`, `INFLUX_DB_BUCKET`, `INFLUX_DB_ORG`:
  InfluxDB v2 write target for canary health metrics.

Run the offline canary tests with
`nix develop --command python -m unittest discover -s lxmf-bridge -p 'test_*.py'`.

The production CronJob keeps the latest three failed jobs for diagnosis, subject
to the existing 24-hour job TTL.

Path discovery retries every 20 seconds within the existing
`LXMF_SEND_PATH_TIMEOUT_SECONDS` budget (default 45 seconds). This retries only
route requests, before sending the inference message. A missing route still
fails with `failure_type=path_timeout` at the same deadline. Each request and any
timeout log the interface connection state and byte counters to distinguish a
disconnected backbone from an unanswered discovery request.
