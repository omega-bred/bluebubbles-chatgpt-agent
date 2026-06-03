# LXMF Bridge

Minimal text-only LXMF bridge for the agent.

The bridge listens for LXMF deliveries, forwards normalized inbound messages to
`/api/v1/lxmf/receive.messages`, and exposes `POST /api/v1/messages/send` for
agent replies.

The same image also includes `canary.py`, a one-shot LXMF free-tier canary. It
creates a fresh identity, sends the configured canary marker with a nonce
inference prompt, accepts Terms, handles either replayed or follow-up inference,
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

- `CANARY_MARKER`: marker prefix that causes the Spring app to flag the account
  as synthetic canary traffic.
- `LXMF_BRIDGE_BASE_URL`: HTTP base URL for the production bridge.
- `INFLUX_DB_URI`, `INFLUX_DB_TOKEN`, `INFLUX_DB_BUCKET`, `INFLUX_DB_ORG`:
  InfluxDB v2 write target for canary health metrics.
