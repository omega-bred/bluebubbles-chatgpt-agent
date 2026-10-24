# BlueChat OAuth DPoP

BlueChat's browser uses RFC 9449 DPoP with authorization code flow and PKCE S256.
The API accepts both properly proved DPoP tokens and existing **unbound** bearer
tokens during migration. A token with a `cnf` claim is never accepted as Bearer.
The `bbagent-web` Keycloak client requirement remains **optional**. Do not change
other clients, realms, or the platform Keycloak configuration for this rollout.

## Implementation and consumers

The released `keycloak-js` 26.2.4 package has no DPoP API (the upstream main-branch
documentation describes unreleased functionality). The browser therefore uses
`oidc-client-ts` 3.5.0. Its maintained implementation generates non-extractable
P-256 private `CryptoKey`s, ES256 proofs, `dpop_jkt` authorization-code binding,
token-endpoint proofs, and refresh proofs. PKCE and OAuth state are retained.
Registration uses Keycloak's supported `prompt=create` flow.

Keys are persisted in IndexedDB and scoped by origin, issuer, and client. Web Locks
serialize key creation, signing, refresh, and logout across tabs. Tokens and PKCE
state are confined to each tab's session storage; no tokens are put in local
storage. Reloading a tab retains its session and proof key. New tabs use Keycloak
SSO to acquire their own tokens with the same key. Logout removes the key, tokens,
and pending PKCE state, then starts Keycloak logout. A nonsecret logout generation
in local storage invalidates other tabs, including suspended tabs that missed the
storage event, without deleting a newly created key from a subsequent login.
Lost keys, storage denial, changed key binding, and non-DPoP token responses fail
closed and require sign-in. HTTPS (or localhost), IndexedDB, WebCrypto, and Web
Locks are required. Same-origin malicious scripts can still request signatures;
non-extractable keys do not replace XSS prevention.

The generated TypeScript API client still makes every website request. A dedicated
Axios transport signs each final request URL and method after token refresh, with
`ath` over the exact access token. Query and fragment are omitted from `htu`.
Signing is restricted to the configured API origin and `/api/v1/` paths. Proofs
are fresh for every dispatch and retry. The application never manufactures a
proof at the ingress or exchanges a bound token for bearer transport.

| Consumer | Authentication after this change |
| --- | --- |
| Website account/link, admin, subscription/checkout/portal, authenticated contact | Browser DPoP through the generated client |
| Existing browser bundles and other legitimate JWT callers | Unbound bearer accepted during transition |
| Native iOS app and StoreKit validation | Existing `X-BlueChat-App-Session` flow |
| App Clip and token-scoped conversation settings | Existing `X-App-Clip-Session` flow |
| Public contact/config, texting discovery, link bootstrap | Existing public flow |
| Apple/Stripe/BTCPay webhooks, Google/Coder OAuth callbacks | Existing provider/signature/state authentication |
| BlueBubbles/LXMF ingress and outbound integration clients | Existing transport/provider credentials |
| Browser WebSocket/SSE | No token-consuming browser WebSocket, EventSource, or streaming API exists in the inspected source; Vite's development HMR is unrelated |

Spring Security 7.1.1's `DPoPAuthenticationProvider` and
`DPoPProofJwtDecoderFactory` verify the issuer-signed access token, public asymmetric
proof key, signature/algorithm, `typ=dpop+jwt`, `cnf.jkt`, `ath`, method, target URI,
required `iat`/`jti`, and the +/-30-second issuance window. An additional check
requires exact HTTP method case. Spring's general `AuthenticationFilter` dispatches
the two schemes; the bearer-only filter cannot carry DPoP authentications because
it deliberately rejects bound tokens. JWT principals, roles, and alternate session
filters are preserved. Missing or invalid proofs return 401 with a DPoP challenge.

Only fully verified proofs enter `oauth_dpop_used_proofs` (Flyway V33). A unique
SHA-256 hash of key thumbprint and `jti` is inserted atomically in Postgres; concurrent
requests at different replicas have one winner. Rows survive restarts and rolling
updates. The stored expiry is `iat + 120s`, safely beyond the accepted proof window;
cleanup runs every minute. Database failure rejects DPoP authentication. Spring's
per-instance replay cache is additional protection, never the cluster authority.

## Public URLs, proxies, CORS, and nonces

`BLUECHAT_DPOP_PUBLIC_ORIGINS` contains the exact external origins. Production pins
`https://bluechat.bre.land,https://chatagent.bre.land`. The ingress must preserve the
original Host and path with no prefix rewrite. The verifier matches Host against
this list and uses the configured scheme plus the actual request path. It ignores
Forwarded/X-Forwarded-* headers, so arbitrary callers cannot choose a proof target
by spoofing proxy headers. Unknown or ambiguous hosts are rejected for DPoP.
`server.forward-headers-strategy=NONE` explicitly disables Spring/Tomcat rewriting.
If infrastructure changes that assumption, reverify the tests and configure a trusted ingress boundary first.
With no configured origins (direct local development), the actual servlet request
URL is used. A proxied development origin can be listed explicitly if necessary.

`BLUECHAT_CORS_ALLOWED_ORIGINS` lists exact browser origins allowed to call the API.
The API permits Authorization/DPoP/content-type and native session headers and
exposes WWW-Authenticate/DPoP-Nonce. Credentials/cookies are not used for API auth.
Keep Keycloak's `bbagent-web` web origins aligned with the deployed browser origins.

The API does not require a server nonce: short-lived proofs and persistent replay
rejection provide its freshness protection. `oidc-client-ts` handles a Keycloak
token-endpoint nonce challenge with one fresh proof retry. API nonce challenges
are cached separately by resource origin and retried once only for HTTP 401 with
`DPoP error="use_dpop_nonce"` and DPoP-Nonce. No generic mutation retry is added.

## Safe deployment and enforcement gates

Preflight on 2026-10-24 found `main` at `e85fa810`, production on
`47188ec57eafb4b73d908f9c233fb521f2bacdff-928`, digest
`sha256:9a71c3750dd48414664844dc94ea267bd8af00f04a6c7585c0921ea4745cf203`.
The app pod was ready. These are observations at implementation time, not proof
of a subsequent rollout. The existing Keycloak client is managed in this app's
`manifests/bluebubbles-chatgpt-agent/keycloak-bbagent/` bundle.

1. Deploy support with `dpop.bound.access.tokens=false`. V33 is additive. Wait for
   CI image publication and Flux application of the manifest, then verify the pod's
   exact tag/digest, readiness, and restart count. During the rolling update, old
   bearer-only pods can reject requests from new DPoP browser bundles; wait for all
   replicas before evaluating success. Old open tabs continue to use unbound tokens.
2. Verify production login and registration (including Apple), code/key binding,
   account-link redemption with its one-time URL intact, admin role authorization,
   account/model APIs, contact, checkout/portal, refresh, reload, two tabs, and logout.
   Exercise both public hostnames and CORS if requests cross origins. Never log
   access tokens, proofs, passwords, or one-time link URLs.
3. Verify missing/wrong proof rejection, bound-as-Bearer rejection, method/URI/ath,
   stale proofs, and replay across replicas/restart using an isolated test account.
   Confirm old unbound bearer sessions, native app, App Clip conversation settings,
   StoreKit, public routes, webhooks, and the existing transport flows still work.
4. Inventory every other `bbagent-web` consumer before enforcement. Old bundles or
   in-flight `keycloak-js` callbacks may need a reload and sign-in with the existing
   SSO cookie. Keep bearer acceptance during this migration; do not infer that a
   client requirement makes previously issued bearer tokens sender-constrained.
   Establish a bounded old-session retirement/relogin plan before removing bearer
   compatibility in a separate change.
5. Only after those gates pass, change **only** this app's `bbagent-web`
   `dpop.bound.access.tokens` attribute to true through its managed manifest and
   verify the EDP operator applied it (the existing client uses `addOnly`). Recheck
   missing/wrong-key token and refresh requests. No enforcement change is included
   in this implementation.

Rollback: keep/restore `dpop.bound.access.tokens=false` first. Prefer retaining the
DPoP-capable API while rolling back just the browser. Rolling back to a bearer-only
API makes already issued DPoP tokens unusable: refresh alone cannot remove their
binding. Have affected browser tabs sign out, discard their local session/key, and
sign in using the old client after rollback. Keep the additive replay table; do not
drop it during a rolling downgrade or restore a stale database just for this feature.
No Keycloak platform downgrade or unrelated realm/client change is needed.

## Verification

`CI=true nix develop --command ./gradlew test` runs browser type checks and Vitest
alongside the backend tests. `DpopSecurityTest` uses real issuer/proof signatures,
the actual Spring filter chain, and shared JDBC storage, including concurrent
independent verifier instances. Existing native/App Clip/controller tests remain
part of the suite. `./gradlew openApiGenerate copySwiftClientToAppClip` regenerates
clients after contract changes; generated sources are not edited by hand.

For an isolated real-browser/Keycloak test (Docker and local browser required):

```sh
nix develop --command npm --prefix frontend exec -- playwright install chromium
python3 test-support/run-dpop-browser-e2e.py
```

The harness imports a temporary realm into a pinned Keycloak 26.8.1 container,
starts a minimal Spring server with the production security classes and V33,
and serves the real frontend/generated client through Vite. It tests interactive
login, PKCE and `dpop_jkt`, API auth, missing/replayed/bearer-downgraded proof
rejection, valid and missing/wrong-key refresh proofs, reload, two-tab key continuity,
and logout cleanup. It removes
the container and temporary credentials in `finally`; it never uses production
credentials or changes live realms. Ports 18181 and 15174 must be free.

Implementation verification on 2026-10-24: the non-network backend run completed
1,216 tests with zero failures (1,200 passed, 16 skipped). All 17 browser auth
unit tests and their type checks passed, as did the isolated Keycloak/browser test. The default
full local run encountered nine live-service failures in `BBClientTest`,
`GiphyClientTest`, and `NominatimReverseLookupIntegTest`; rerunning with only those
three classes excluded passed. No production secrets were supplied to those tests.
These local exclusions are not committed into the normal test task. Production
rollout and enforcement verification remain the gates above.

## Upstream references

- [RFC 9449](https://www.rfc-editor.org/rfc/rfc9449.html)
- [Spring DPoP resource server support](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/dpop-tokens.html)
- [oidc-client-ts 3.5.0 source](https://github.com/authts/oidc-client-ts/tree/v3.5.0/src)
- [Released Keycloak JS 26.2.4](https://github.com/keycloak/keycloak-js/tree/26.2.4/lib)
- [Keycloak DPoP guide](https://www.keycloak.org/securing-apps/dpop)
