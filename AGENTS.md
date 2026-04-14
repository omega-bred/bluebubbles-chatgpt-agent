Any time you add or change REST APIs - always update `src/main/resources/openapi.yaml` and run
`./gradlew openApiGenerate` to regenerate server stubs and clients. Never hand-edit generated APIs.

The web app should always use the generated TypeScript client from OpenAPI.

API paths are in the style of `/api/v1/$resourceType/$verb.$resource(s)`.

Formatting should always be run via `./gradlew spotlessApply`.

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
  access token. Redeeming links the Keycloak subject to the iMessage sender identity. Re-redeeming
  by the same account should be idempotent; redeeming an already-used token from a different account
  should conflict.
- The account dashboard is read-only for integrations. It derives Coder status from the same
  sender-based account base used by `CoderMcpClient`, and Google Calendar status from the existing
  chat/sender account base used by calendar tools. Revoking underlying OAuth tokens remains handled
  by the existing iMessage tools, not the website dashboard.

Model access:
- Model picker entitlement storage lives in Postgres table `agent_model_account_settings`, keyed by
  the same sender/account base used for website account links. Missing rows mean standard access.
- Standard accounts are read-only and use the hard-coded `local` model surface, backed by the
  current local responses model configured in `ModelAccessService`. Premium rows set
  `is_premium=true`; premium users currently default to `chatgpt`, with future options exposed
  read-only in account/model summaries until model selection is implemented.

If you add new configs:
- Prefer environment variables with sensible defaults in `application.properties`.
- Update `manifests/bluebubbles-chatgpt-agent/be-components.yaml` if needed for production deploys.
- Most properties will need to be replicated into the `application.properties` in the `test/resources` folder.

Primarily, the "agent" lives in `src/main/java/io/breland/bbagent/server/agent/BBMessageAgent.java`. 

When running the JDK/JVM for development - you may need to update the project to use java21- but never commit this change to the repository. 
