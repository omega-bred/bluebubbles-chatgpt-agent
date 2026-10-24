# Gayodex self-service over BlueChat

Five native tools read/create/update the current sender's entry, upload a photo
from their current incoming message, and inspect its conversion job. They use
the normal registry and Lucene tool search under the `gayodex` category. No
website login, OAuth, per-texter catalog or new identity tables are involved.

The backend is pinned to one owner's shared catalog by Gayodex configuration.
There are no phone, account, catalog or profile selectors in tool arguments.
Canonical BlueChat account resolution remains unchanged. A linked phone alias
does not prove the sender of an email-addressed message; email identities fail
closed for these tools.

## Sender and attachment boundary

When enabled and fully configured, the webhook controller confirms eligible
iMessage phone messages using BlueBubbles' authenticated `getMessage` API. The
GUID, sender handle, service, conversation, exact text, millisecond timestamp,
inbound direction and non-system/service status must match. Attachment metadata
comes from that authenticated response, replacing webhook-provided locators.
Only then does the internal incoming message gain verified provenance. This
provenance survives Cadence serialization and ordinary text/thread transforms;
legacy, history, scheduled and fabricated messages default to unverified.

Discovery and execution both require this provenance plus current complete
service configuration. Execution also rejects mismatched/scheduled workflow
contexts. Phone normalization uses the existing account identifier normalizer,
with a restrictive phone syntax and canonical E.164 validation. Each group
sender retains their own identity; the group GUID never selects an entry.

The upload tool defaults to the only downloadable image in that current
verified message. For several images, `image_index` selects a one-based choice;
`attachment_guid` remains an optional alternative. Supplying both selectors is
rejected. Ambiguous selection returns `photo_selection_required` with current
choices and performs no download or write. The model receives these choices
in the current user prompt (`current_message_images`) and the profile-read
response (`current_message_photos`). Filenames, MIME types and identifiers are
JSON-encoded source data, not instructions. Texters never need to find a photo
ID. If the original request does not identify a photo among several, ask for
the chosen image attached to a new message.

Photos download through the existing authenticated BlueBubbles client, apply the
existing image decoder's dimension limits (20 million pixels), enforce a 6 MiB
input/output limit, and delete the temporary download. The upstream API supports
64 megapixels; BlueChat intentionally retains its stricter existing decoder
limit. There is no arbitrary URL, history message, contact photo or model base64
input. Gayodex normalizes uploads and strips metadata.

Photo downloads for model input and Gayodex use `original=0`, fixed
`width=1024`, and `quality=good`. The single width preserves aspect ratio and
fits ordinary high-resolution phone photos inside the existing decoder and
upload budgets without raising them. Generic files and model GIF/WebP inputs
keep their existing unresized download path. The returned bytes are still
validated against the pixel/byte limits; a server that ignores resizing does
not bypass them.

BlueBubbles conversion can return JPEG/PNG for an iPhone
HEIC attachment whose metadata still says `image/heic`. Both upload validation
and current model image inputs inspect the actual downloaded bytes. Model
inputs use the detected MIME, with a 10 MiB download/output limit and the same
20-million-pixel limit for decoded photos. Existing GIF/WebP model images retain
their encoding, and non-image file handling is unchanged.
This does not require JVM-native HEIC decoding. If BlueBubbles returns
unsupported/corrupt original bytes, the tool reports a decode error before any
catalog upload and asks for a JPEG/PNG.

An instruction in a later text-only message, such as "use the second photo",
does not authorize an earlier message's attachments for Gayodex. History images
remain usable for ordinary inspection/image generation, but not this upload
path. A separately approved follow-up design could verify the original image
message against the same current raw phone and conversation, require explicit
selection and a bounded retention window, and recheck provenance before
download. This change keeps the existing current-message boundary.

## Edits and responses

Read before every write; supply the returned entry revision. Only `name` (18),
adult integer `age` (18–120), `archetype` (12), `subtitle` (27), `bio` (135), and
up to three `interests` (25 each) are accepted. Text uses Gayodex's handheld
ASCII alphabet. An update may clear the photo with `photo: null`.

Successful edits are drafts and responses explicitly require owner publishing
before a handheld receives them. Photo conversion defaults to 8-bit processing;
poll queued/processing jobs in the current incoming-message turn. A later
scheduled task has no sender authority; ask the sender to text again for a
later status check. `fallback` keeps the original, `superseded` means newer
changes took precedence, and `complete` confirms conversion only. Poll only
`queued`/`processing` jobs with a returned ID. A terminal `fallback` upload can
have no job ID: the original photo is already saved to the draft. Do not poll
or ask the texter to find a job ID in that case.

The HTTP client sends only dedicated self-service routes and injects the bearer
credential and verified sender header internally. Redirects and retries are
disabled, connect timeout is 5 seconds, response timeout 15 seconds and the
whole request is capped at 20 seconds. Responses are capped at 32 KiB. Errors
are fixed messages, never upstream bodies, exception details or credentials.
401/403 require operator configuration repair; 409 requires read/reconsider;
410 requires owner restoration; 429 requires waiting. An ambiguous timeout may
have saved a draft: read before another write. The public response allowlist
excludes owner-private fields, media references and catalog/account metadata.

## Deployment and activation

Server contract: [Gayodex PR #3](https://github.com/omega-bred/gayodex/pull/3),
merged as `958d2136788403a1e58eaa1e46bdac57dd9464cd` and deployed through
[Kubernetes PR #45](https://github.com/omega-bred/kube/pull/45). The exact
linux/amd64 deployment image passed all 48 server tests before publication.
Server credential and private-network configuration are tracked in
[Kubernetes PR #46](https://github.com/omega-bred/kube/pull/46).

The BlueChat manifest enables `GAYODEX_BLUECHAT_ENABLED=true` and supplies the
private URL and a required `token` reference to `gayodex-bluechat`. A missing
Kubernetes Secret or token key prevents startup. Disabled or partial application
configuration hides and blocks all five tools. Local tooling reads the optional
1Password token only when explicitly enabled. Use the 1Password MCP server when
setting up a 1Password developer environment; do not paste credentials into
chat, shell arguments or source.

After reviewing/merging and deploying the tested Gayodex server implementation:

1. Generate at least 32 random bytes as a URL-safe unpadded token using a secure
   local process and write it directly to 1Password. Provision Kubernetes vault
   item `gayodex-bluechat`, field `token`, for BlueChat only. Compute its SHA-256
   without logging either value and put the digest in separate item
   `gayodex-bluechat-server`, field `token-sha256`, for Gayodex. Both dedicated
   items were provisioned on 2026-10-23 and their token/digest relationship was
   verified without logging either value. Both operator-managed Kubernetes
   Secrets were confirmed present before activation. Never use a generic owner/admin bearer token.
2. Re-read the latest live/remote Gayodex GitOps source. The local kube checkout
   was older than the live workload when inspected on 2026-10-23. Add
   `manifests/gayodex-bluechat/network-policy.yaml` and `service-secret.yaml` to
   that source and apply `deployment-patch.yaml` as a strategic merge patch.
   Preserve its current image, readiness checks, OpenAI workload identity and
   MCP tunnel rules. The patch adds only bridge configuration and does not
   select/replace an image.
3. Reconfirm the patch's explicit owner against the owning deployment and stored
   account before activation. On 2026-10-23 the live issuer and configured legacy
   owner subject hashed to `054e3513beb73cc8f738810c32a875660b764dc0cf761c30fdb58a95abfc9e9d`;
   a read-only pod check confirmed that exact account exists, the subject matches,
   and it owns the legacy shared catalog. This was not first-account selection.
4. Recheck ingress and egress. Live Gayodex ingress on 8767 currently allows
   namespace `mcps` AND pod label `app.kubernetes.io/name=tunnel-client-gayodex`.
   The staged additive policy allows namespace `bluebubbles-chatgpt-agent` AND
   pod label `app=bluebubbles-chatgpt-agent`. Do not split these into separate
   `from` peers (which would mean OR). The service targets Gayodex pods with
   `app.kubernetes.io/name=gayodex`. BlueChat currently has no Kubernetes or
   Cilium namespace egress policy and no cluster-wide Cilium policy was present.
   Gayodex's existing Cilium egress policy allows DNS and required HTTPS services;
   established response traffic needs no new egress rule. If BlueChat gains
   egress isolation, add TCP 8767 to these Gayodex pods and retain DNS access.
5. Let Flux reconcile server image, secret and policy; verify readiness and
   backend connectivity. A read-only unbound profile request is enough for
   credential/connectivity checks. Keep tokens/phones and raw responses out of
   logs. Do not mutate real entries or send live canaries without human
   authorization. Only then set `GAYODEX_BLUECHAT_ENABLED=true` in BlueChat's
   manifest, build/deploy its intended image and verify discovery/runtime.

Rotate raw token and digest together and restart both services. Back up Gayodex
account state with its draft; its persistent phone-binding HMAC key must survive
rotation/redeployment. Disable BlueChat's feature flag to stop discovery and
execution without changing catalog contents.

Activation prerequisites verified on 2026-10-23: both operator-managed Secrets
were present, the server's configured bridge was Ready, and a read-only probe
from the BlueChat namespace/workload network identity successfully read an
unbound synthetic sender. The same probe confirmed that the backend token was
denied owner catalog access and that invalid tokens and non-phone senders were
rejected. It made no catalog mutations and sent no messages.

## Verification

```sh
nix develop --command env CI=true ./gradlew spotlessApply test \
  --tests '*Gayodex*' --tests '*AgentToolRegistryTest' \
  --tests '*BluebubblesWebhookControllerTest' --tests '*IncomingMessageTest' \
  --tests '*BBHttpClientWrapperTest'
```

Tests use synthetic identities, an authenticated-transport stub and a local HTTP
server. No live catalog or message mutation is needed. This feature adds no
BlueChat REST endpoint, so there are no new generated OpenAPI APIs/clients.

Local validation on 2026-10-23: the final deterministic suite ran 1,179 tests
(1,164 passed, 15 skipped), including all 81 focused feature/registry/webhook/
incoming-message/transport checks. That run excluded only `BBClientTest`, `GiphyClientTest`
and `NominatimReverseLookupIntegTest`: an earlier unrestricted run had seven
BlueBubbles failures without a configured host, one Giphy failure without
credentials, and one Nominatim external-result failure. Formatting and manifest
rendering passed; server dry runs accepted the additive NetworkPolicy, server
OnePasswordItem, and bridge patch composed with the current live Deployment.
