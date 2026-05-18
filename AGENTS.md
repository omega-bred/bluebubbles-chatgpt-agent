Any time you add or change REST APIs - always update `src/main/resources/openapi.yaml` and run
`./gradlew openApiGenerate` to regenerate server stubs and clients. Never hand-edit generated APIs.

The web app should always use the generated TypeScript client from OpenAPI.

API paths are in the style of `/api/v1/$resourceType/$verb.$resource(s)`.

Formatting should always be run via `./gradlew spotlessApply`.

Nix/dev dependencies:
- Prefer entering the repo dev shell before running project tooling: `nix develop`.
- If you are invoking one command from outside the shell, use `nix develop --command <command>`,
  for example `nix develop --command ./gradlew test` or
  `nix develop --command ./gradlew spotlessApply`.
- The flake provides OpenJDK 25, Gradle, Node 20/npm, Python 3.13 with LXMF/RNS, Postgres,
  Docker/Kubernetes helpers, Keycloak admin tooling, OpenAPI/Flyway CLIs, and 1Password CLI.
- Use `./gradlew` for backend tasks; the shell sets `JAVA_HOME` for the project JDK.
- Use the generated TypeScript client workflow through Gradle (`./gradlew openApiGenerate` and the
  frontend copy/build tasks). For direct frontend work, run npm through the shell, e.g.
  `nix develop --command npm --prefix frontend run dev`.
- For LXMF bridge work, the shell Python can import `RNS` and `LXMF`; run bridge checks inside
  `nix develop` rather than installing Python packages globally.

Testing:
- Unit/integration tests run with an in-memory H2 database by default (see
  `src/test/resources/application.properties`).
- Flyway is enabled for tests; add migrations in `src/main/resources/db/migration`.

Google Calendar:
- OAuth is handled via `/api/v1/gcal/completeOauth.gcal`.
- OAuth state uses an HMAC secret; set `GCAL_OAUTH_STATE_SECRET`.
- Tokens are stored in Postgres (no local token directory).

BlueBubbles:
- Outbound iMessage requires `BLUEBUBBLES_PASSWORD` and a reachable BlueBubbles server base URL.

Canonical agent accounts:
- User/account identity is centered on `agent_accounts.account_id`. Do not key new persistence off
  raw iMessage sender strings, Keycloak subject, Google account id, or Coder user id when the data
  belongs to the agent user.
- Transport identities live in `agent_account_identities` and currently support exactly
  `imessage_email`, `imessage_phone`, and `lxmf_address`. Add new transport rows here as new
  transports arrive; do not create alias tables or parallel sender-link tables.
- Use `AgentAccountResolver` for account lookup, creation, and merging. It normalizes email/phone
  identifiers and can merge accounts when iMessage phone, iMessage email, LXMF address, or linked
  Keycloak email prove they are the same user.
- It is valid for iMessage to alternate between phone and email. All account-related features must
  treat those as the same user after resolution, including OAuth lookup, model access, global contact
  name, linked-account status, and location/Find My lookups.
- Website login is metadata on `agent_accounts` (`website_subject`, website email/name fields), not
  a separate website account table. Link tokens store only a token hash and reference the canonical
  `account_id`.
- OAuth and async integration state belongs to `account_id`: Google Calendar credentials,
  Coder OAuth credentials, Coder pending authorizations, and Coder async task starts. Do not add
  compatibility paths that look up old sender-based account bases.
- Model picker entitlement and global contact name live on `agent_accounts`; do not recreate
  `agent_model_account_settings` or `global_contact`.
- This project intentionally reset the early test data model. There is no backwards-compatibility
  requirement for the dropped account/link/alias tables unless the user explicitly asks for one.

Website accounts / Keycloak:
- Browser login uses Keycloak, not Clerk. The app realm is `bbagent` and the public SPA client is
  `bbagent-web`.
- The production issuer is `https://keycloak.bre.land/realms/bbagent`. Keep
  `KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` aligned with the realm, and mirror test-safe
  defaults into `src/test/resources/application.properties`.
- Keycloak realm/client setup lives in `scripts/setup-keycloak-bbagent.sh`. Run
  `kcadm config credentials` against the master realm first, then run the script. Only run it with
  `APPLY_KEYCLOAK_THEME=true` after the `bbagent` theme is installed on the Keycloak pods.
- Theme source lives in `keycloak/themes/bbagent`. Keycloak's `login` theme type covers login,
  registration, reset password, and related auth screens.
- In production, the theme is installed from the kube repo at
  `/Users/breland/repos/kube/apps/keycloak`: Kustomize generates a ConfigMap from
  `apps/keycloak/themes/bbagent`, and `keycloak-theme-mount-patch.yaml` mounts it at
  `/opt/keycloak/themes/bbagent`. Push kube changes and let Flux reconcile/roll Keycloak before
  setting `loginTheme=bbagent`.

Website account linking:
- Use the `link_website_account` agent tool when an iMessage user asks to log in, sign up, manage
  their web account, connect iMessage to the website, or view linked integrations.
- Use the `get_website_account_link_status` agent tool when the user asks whether the current
  sender, another sender, or the current chat identity is already linked to a website account. The
  current incoming message context may already include `websiteAccountLinked` and
  `websiteAccountExactChatLinked`; call the tool when the user asks directly, names a different
  sender, or the context is absent/ambiguous. The tool also returns read-only model access info
  such as `is_premium`, plan, current model, and whether website model selection is configurable.
- The `link_website_account` tool infers the current iMessage sender/chat context, creates a
  short-lived pending link token, stores only a token hash, and returns a safe
  `/account/link?token=...` URL for the user. Link tokens default to 30 minutes and are single-use.
- `/account/link` requires Keycloak login, then calls the protected redeem API with the Keycloak
  access token. Redeeming links the Keycloak subject to the canonical agent account for the current
  iMessage sender/chat identity. Re-redeeming by the same account should be idempotent; redeeming an
  already-used token from a different account should conflict.
- Account identity is canonicalized through `agent_accounts` plus `agent_account_identities`.
  Supported identity types are `imessage_email`, `imessage_phone`, and `lxmf_address`; phone and
  email forms that refer to the same user should resolve to the same `agent_accounts.id`.
- The account dashboard lists linked chat identities and OAuth integrations. Coder and Google
  Calendar OAuth credentials are keyed by canonical `agent_accounts.id`, and the dashboard may unlink
  those OAuth credentials. The dashboard should not unlink chat identities themselves unless the
  account model is intentionally redesigned.

Coder:
- Do not use a `coder_oauth_clients` table or dynamic Coder client registration. Coder OAuth uses
  one statically configured client from `CODER_OAUTH_CLIENT_ID` and optional
  `CODER_OAUTH_CLIENT_SECRET`, plus `CODER_OAUTH_REDIRECT_URI` and `CODER_OAUTH_STATE_SECRET`.
- Coder agent tools must receive the canonical `account_id` from `ToolContext`, and Coder credential
  lookup/revoke must be scoped to that exact account id.

Model access:
- Model picker entitlement storage lives directly on `agent_accounts`. Missing accounts or unset
  flags mean standard access.
- Standard accounts are read-only and use the hard-coded `local` model surface, backed by the
  current local responses model configured in `ModelAccessService`. Premium rows set
  `is_premium=true`; premium users currently default to `chatgpt`, with future options exposed
  read-only in account/model summaries until model selection is implemented.

If you add new configs:
- Prefer environment variables with sensible defaults in `application.properties`.
- Update `manifests/bluebubbles-chatgpt-agent/be-components.yaml` if needed for production deploys.
- Most properties will need to be replicated into the `application.properties` in the `test/resources` folder.

Primarily, the "agent" lives in `src/main/java/io/breland/bbagent/server/agent/BBMessageAgent.java`.

Development JDK/JVM use is pinned through the Nix shell; do not commit local Java-version downgrades
just to work around a machine-specific JDK issue.
