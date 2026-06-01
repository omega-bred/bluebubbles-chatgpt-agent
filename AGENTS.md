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
  Docker/Kubernetes helpers, Keycloak admin tooling, OpenAPI/Flyway CLIs, 1Password CLI, and
  Darwin-only App Clip helpers (`swiftformat`, `xcbeautify`).
- App Clip iOS builds still require local Xcode command line tools (`xcodebuild`, `xcrun`,
  `codesign`, `security`) outside Nix. App Store Connect distribution uses the real `asc` CLI
  installed on the machine; do not add nixpkgs `asc`, which is not the App Store Connect CLI.
- Use `./gradlew` for backend tasks; the shell sets `JAVA_HOME` for the project JDK.
- Use the generated TypeScript client workflow through Gradle (`./gradlew openApiGenerate` and the
  frontend copy/build tasks). For direct frontend work, run npm through the shell, e.g.
  `nix develop --command npm --prefix frontend run dev`.
- A Swift OpenAPI client can be generated with `./gradlew openApiGenerateSwiftClient`; it writes an
  SPM package under `build/swift-client-generated`. The App Clip may keep a small hand-written client
  until that generated package is intentionally wired into `appclip/BlueChat.xcodeproj`.
- For LXMF bridge work, the shell Python can import `RNS` and `LXMF`; run bridge checks inside
  `nix develop` rather than installing Python packages globally.

Testing:
- Unit/integration tests run with an in-memory H2 database by default (see
  `src/test/resources/application.properties`).
- Flyway is enabled for tests; add migrations in `src/main/resources/db/migration`.
- The full `./gradlew test` task includes live-network integration coverage. If
  `NominatimReverseLookupIntegTest.testReverseLookup()` fails with a timeout or changed external
  address data, treat it as an ambient/live-service failure and still run focused tests for the
  feature you touched.

Google Calendar:
- OAuth is handled via `/api/v1/gcal/completeOauth.gcal`.
- OAuth state uses an HMAC secret; set `GCAL_OAUTH_STATE_SECRET`.
- Tokens are stored in Postgres (no local token directory).

BlueChat / BlueBubbles:
- Outbound BlueChat messaging requires `BLUEBUBBLES_PASSWORD` and a reachable BlueBubbles server base URL.

Help/contact and Cap:
- The public help/contact form lives at `/help` and `/contact`, uses generated OpenAPI client calls
  to `/api/v1/contact/get.contactConfig` and `/api/v1/contact/create.contactMessages`, and stores
  rows in `website_contact_messages`.
- Contact abuse prevention is Cap-backed. Frontend uses `cap-widget`; backend verifies the emitted
  token by POSTing to `${CAP_BASE_URL}/${CAP_CONTACT_SITE_KEY}/siteverify`.
- Production Cap is hosted at `https://cap.bre.land`. The Kubernetes/1Password item is
  `cap-bbagent-contact` with fields `site-key` and `secret-key`; keep those aligned with
  `CAP_CONTACT_SITE_KEY` and `CAP_CONTACT_SECRET_KEY` in `build.gradle`,
  `application.properties`, test properties, and the production manifest.
- Do not put the Cap secret in frontend config. The frontend should only receive the public Cap API
  endpoint returned by the backend contact config API.

Canonical agent accounts:
- User/account identity is centered on `agent_accounts.account_id`. Do not key new persistence off
  raw BlueChat sender strings, Keycloak subject, Google account id, or Coder user id when the data
  belongs to the agent user.
- Transport identities live in `agent_account_identities` and currently support exactly
  `imessage_email`, `imessage_phone`, and `lxmf_address`. Add new transport rows here as new
  transports arrive; do not create alias tables or parallel sender-link tables.
- Use `AgentAccountResolver` for account lookup, creation, and merging. It normalizes email/phone
  identifiers and can merge accounts when BlueChat phone, BlueChat email, LXMF address, or linked
  Keycloak email prove they are the same user.
- It is valid for BlueChat to alternate between phone and email. All account-related features must
  treat those as the same user after resolution, including OAuth lookup, model access, global contact
  name, linked-account status, and location/Find My lookups.
- Website login is metadata on `agent_accounts` (`website_subject`, website email/name fields), not
  a separate website account table. Link tokens store only a token hash and reference the canonical
  `account_id`.
- OAuth and async integration state belongs to `account_id`: Google Calendar credentials,
  Coder OAuth credentials, Coder pending authorizations, and Coder async task starts. Do not add
  compatibility paths that look up old sender-based account bases.
- Model picker entitlement, selected model, model verbosity, and global contact name live on
  `agent_accounts`; do not recreate `agent_model_account_settings`, `assistant_responsiveness`, or
  `global_contact` for account-level model preferences.
- This project intentionally reset the early test data model. There is no backwards-compatibility
  requirement for the dropped account/link/alias tables unless the user explicitly asks for one.

Account blocking / abuse controls:
- Admin abuse controls should block the canonical `agent_accounts` row via
  `processing_blocked`, `processing_blocked_reason`, `processing_blocked_at`, and
  `processing_blocked_by`. Do not add sender-specific or website-specific blocklist tables for
  account-level abuse controls.
- `AccountModerationService` can resolve block targets by canonical account id, website subject,
  website email, BlueChat email, BlueChat phone, or LXMF address. Transport identity blocks should
  use `AgentAccountResolver` so phone/email normalization and account merging remain canonical.
- `BBMessageAgent` intentionally checks `processing_blocked` before terms gating, metrics, model
  calls, or workflow launch. Keep that early drop behavior when changing inbound processing.

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
- Use the `link_website_account` agent tool when a BlueChat user asks to log in, sign up, manage
  their web account, connect BlueChat to the website, or view linked integrations.
- Use the `get_website_account_link_status` agent tool when the user asks whether the current
  sender, another sender, or the current chat identity is already linked to a website account. The
  current incoming message context may already include `websiteAccountLinked` and
  `websiteAccountExactChatLinked`; call the tool when the user asks directly, names a different
  sender, or the context is absent/ambiguous. The tool also returns read-only model access info
  such as `is_premium`, plan, current model, and whether website model selection is configurable.
- The `link_website_account` tool infers the current BlueChat sender/chat context, creates a
  short-lived pending link token, stores only a token hash, and returns a safe
  `/account/link?token=...` URL for the user. Link tokens default to 30 minutes and are single-use.
- `/account/link` requires Keycloak login, then calls the protected redeem API with the Keycloak
  access token. Redeeming links the Keycloak subject to the canonical agent account for the current
  BlueChat sender/chat identity. Re-redeeming by the same account should be idempotent; redeeming an
  already-used token from a different account should conflict.
- Account identity is canonicalized through `agent_accounts` plus `agent_account_identities`.
  Supported identity types are the legacy internal values `imessage_email`, `imessage_phone`, and
  `lxmf_address`; show them as BlueChat email/phone and LXMF address in user-facing copy. Phone and
  email forms that refer to the same user should resolve to the same `agent_accounts.id`.
- The account dashboard lists linked chat identities and OAuth integrations. Coder and Google
  Calendar OAuth credentials are keyed by canonical `agent_accounts.id`, and the dashboard may unlink
  those OAuth credentials. The dashboard should not unlink chat identities themselves unless the
  account model is intentionally redesigned.

App Clip:
- The native App Clip project lives in `appclip/BlueChat.xcodeproj`. The containing app bundle id is
  `land.bre.bluechat.ios`, the App Clip bundle id is `land.bre.bluechat.ios.Clip`, the Apple team id
  is `U2Q8X6GTU9`, and the App Clip domain is `bluechat.bre.land`.
- App Clip entry links use the same `/account/link?token=...` URL as the website. The App Clip calls
  `/api/v1/appClip/createSession.appClipSessions` with the one-time account link token and then sends
  the returned session token as `X-App-Clip-Session` on App Clip-authenticated APIs.
- Link tokens are stored hashed, default to 30 minutes, and are single-use. App Clip session tokens
  default to 30 days via `appclip.session-token-ttl-days`; do not extend normal link-token TTLs for
  production convenience.
- The App Clip should show the same core account state as the website dashboard: linked chat
  identities, Google Calendar and Coder OAuth integration status, current model access, and billing
  status. Some accounts have no website email or display name; fall back to linked chat identity or
  account id instead of rendering blank identity text.
- Website account summary/model endpoints can accept App Clip session auth. The App Clip currently
  uses `/api/v1/websiteAccount/listLinkedAccounts.websiteAccountLinks` and
  `/api/v1/websiteAccount/updateModel.websiteAccountModels` with `X-App-Clip-Session`.
- Premium users can change the selected model through the shared website account model API. Standard
  accounts should remain on the local model surface. Premium accounts that did not come from Apple
  StoreKit should render billing as externally managed, for example `Managed on Website`, instead of
  showing Apple subscription management controls.
- StoreKit purchases validate through
  `/api/v1/subscription/validateStoreKit.subscriptionProviderEvents` with `X-App-Clip-Session`.
  Preserve `apple` as the subscription provider key for App Store / StoreKit premium state.
- Keep `apple-app-site-association` valid on `bluechat.bre.land` for both app links and App Clip
  invocation. The default App Clip experience in App Store Connect should target the account link URL
  pattern on that domain.
- Before uploading a new TestFlight build, increment `CFBundleVersion` in both
  `appclip/BlueChat/Info.plist` and `appclip/BlueChatClip/Info.plist`. Verify the containing app
  entitlements include `com.apple.developer.associated-appclip-app-identifiers` with
  `U2Q8X6GTU9.land.bre.bluechat.ios.Clip`; missing this causes ITMS-90876.
- Distribution should archive/export with Xcode signing and upload through `asc publish testflight`.
  If App Store Connect says the build is not internally testable yet, wait for processing, set
  `usesNonExemptEncryption=false`, add the build to the Internal Testers group, and remove older
  internal-test builds. Do not submit the app for App Review unless the user explicitly asks.

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
  `is_premium=true`; premium users can choose among the exposed premium model options through the
  website dashboard or App Clip model picker.
- Response verbosity is an account-level `agent_accounts.model_verbosity` setting with
  `low`, `medium`, and `high` values. It is available to both free and premium accounts through the
  website, App Clip, and `set_preferred_model` agent tool.

Subscription billing:
- Keep BTCPay and Stripe side by side. Do not replace one provider path with the other; new billing
  work should go through `SubscriptionProvider`, `SubscriptionProviderRegistry`, and
  `SubscriptionService`.
- Stripe subscription checkout uses Checkout Sessions, Customer Portal for manage/cancel, and
  signed webhooks. Preserve metadata keys `bbagent_account_id`, `bbagent_checkout_id`,
  `bbagent_plan_key`, and `bbagent_provider` for reconciliation.
- Production currently uses Stripe test mode by default. `build.gradle` pulls the test-mode
  1Password item `stripe-bbagent-subscriptions-test` for local boot/test, while the Kubernetes
  manifest leaves the live item wiring in place for future promotion.

Operational metrics, dashboards, and alerts:
- Prefer Micrometer/MeterRegistry instrumentation for new operational metrics. Use stable
  `bbagent.*` dot-separated meter names in code and low-cardinality tags such as `operation`,
  `outcome`, `model`, `failure_type`, `transport`, `tool`, or `provider`. Do not tag raw user ids,
  chat guids, message contents, prompts, tokens, phone numbers, emails, or other
  high-cardinality/sensitive values. For agent traffic, use the operational `transport` tag values
  `imessage` and `lxmf`; BlueBubbles-originated iMessage traffic should not be tagged as
  `bluebubbles`.
- Current Influx/Grafana wiring writes these meters into the `bluebubbles-chatgpt-agent` bucket.
  Influx measurement names use underscores, e.g. `bbagent.agent.llm.call.count` becomes
  `bbagent_agent_llm_call_count`. Counter/gauge values use `_field == "value"`. Timer summaries may
  expose fields such as `count`, `sum`, `mean`, and `upper`.
- When adding a metric that should be operationally visible, update the BlueBubbles Grafana
  dashboard as part of the same pass. The production dashboard is `BlueBubbles`
  (`/d/brtxbw8/bluebubbles`, UID `brtxbw8`), the Influx datasource UID is `bf1yfcwx2pv5sf`, and
  the bucket is `bluebubbles-chatgpt-agent`. Prefer panels that split by meaningful low-cardinality
  tags and show both rate/count and latency/health where applicable.
- For alerting, use the Grafana MCP/API rather than hand-editing exported dashboard JSON. Put
  BlueBubbles alert rules in folder UID `bluebubbles`, rule group `BlueBubbles operational alerts`,
  and route page-worthy alerts to the Grafana contact point `pagerduty-bluebubbles`. The PagerDuty
  service is `BlueBubbles ChatGPT Agent`; do not paste or commit PagerDuty routing/integration keys.
  If the key changes, store it in the Grafana contact point or 1Password, not in source.
- Seed Flux alert queries with `array.from`/`union` when a metric may be absent or idle so the alert
  returns a deterministic zero instead of noisy no-data. Use `noDataState=OK` for idle workload
  metrics where no traffic is healthy, but `noDataState=Alerting` for required health telemetry.
  Keep `execErrState=Alerting` for page-worthy alerts so broken queries do not hide real incidents.
- Existing paging examples: `bluebubbles-health-down` pages when `bbagent_bluebubbles_health_up` is
  below 1 or missing for more than one minute; `bluebubbles-llm-calls-failing` pages when
  `bbagent_agent_llm_call_count` has at least one `outcome=failure` and zero `outcome=success` in
  the last ten minutes.

If you add new configs:
- Prefer environment variables with sensible defaults in `application.properties`.
- Update `manifests/bluebubbles-chatgpt-agent/be-components.yaml` if needed for production deploys.
- Most properties will need to be replicated into the `application.properties` in the `test/resources` folder.

Primarily, the "agent" lives in `src/main/java/io/breland/bbagent/server/agent/BBMessageAgent.java`.

Development JDK/JVM use is pinned through the Nix shell; do not commit local Java-version downgrades
just to work around a machine-specific JDK issue.
