# Conversation images, reactions, and prompt review

Reviewed on 2026-09-07 against `d3dd8041` plus the changes in this working tree. The review follows the request from ingress through history construction, tool discovery, the Responses API request, and Cadence delivery. It uses synthetic fixtures; it does not claim that this code is deployed or that a live model has passed behavioral evaluations.

## Earlier images

The reported failure had several supporting code paths. Photo-only messages were excluded during restart hydration. Hydration discarded attachment metadata, and saved conversation summaries omitted message and attachment IDs. History search requested and returned text without attachment metadata. Thread context could retain a photo while changing its message ID to that of a later text reply. An attachment identifier in a text tool result was also insufficient to supply the actual image to image generation.

The updated flow preserves photo-only messages and their references, including pending turns that never received a successful assistant response. `search_convo_history` returns attachment metadata; an omitted text query includes uncaptioned photos. `load_conversation_images` accepts message GUIDs and one-based image indexes, verifies membership in the current conversation, downloads the selected files, and returns actual multimodal image inputs. Equivalent `any;-;` and `iMessage;-;` direct-chat GUIDs are accepted; different participants, services, and groups are rejected. These tools do not send the references to the user or to a wall display.

BlueBubbles explicitly requires an attachment join for both message lookup/search and chat-history reads. The client now requests it on all three paths. This was checked against the upstream [message router](https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/http/api/v1/routers/messageRouter.ts) and [chat router](https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/http/api/v1/routers/chatRouter.ts).

Thread metadata now retains a separate `last_image_message_guid`, uses correct seconds-or-milliseconds timestamp conversion, and contains lightweight references instead of base64. The model is instructed to retrieve a referenced image before asking the user to resend it, and to clarify ambiguous selections.

Image loading is bounded to four references, 10 MiB per image, 20 MiB combined, and the existing 20-million-pixel image validation limit. Returned bytes determine the image format, including converted HEIC photos. Temporary downloads are cleaned up. Image content bypasses text-result truncation. Cadence stores short references rather than binary image inputs in workflow history; the activity restores the bytes immediately before calling the model. If that temporary cache expires or the worker restarts, the model receives a request to reload the original chat photo. Unavailable or deleted source attachments still require a resend.

## Reactions and silence

Ingress previously dropped every message matching a broad reaction prefix, including ordinary requests beginning with words such as “Liked.” These messages now reach the model. The response policy defaults to `NO_RESPONSE` for tapbacks, standalone emoji, thanks, laughter, and casual acknowledgements. It distinguishes the quoted reaction target from a new question and allows a response or action for actual questions, corrections, requests, and clear answers to pending assistant questions. Ambiguous reactions do not authorize consequential actions.

An explicit `NO_RESPONSE` previously entered the empty-answer retry loop, which could force a reply or a poll fallback. It now ends the turn without either. The change uses a Cadence version marker so existing workflow histories retain their recorded path during replay.

Complete tapback notifications use a separate conversation-scoped reaction workflow. They leave an ongoing substantive request and its response permission intact. New substantive messages supersede older reaction responses. Prefix-like ordinary questions remain normal requests. Silent mode still requires its existing `Chat` activation prefix for interactive messages. Tapbacks do not accept Terms, trigger a Terms prompt, or generate an unsolicited quota notice when exhausted.

Outgoing reactions were already supported. Their availability remains transport-dependent; LXMF remains text-only. The change restores incoming reaction interpretation and makes deliberate silence effective.

## Materialized prompt coverage

`AgentPromptMatrixTest` captures the final `ResponseCreateParams` body after the real prompt builder, response creator, role conversion, and model picker run. It writes 160 synthetic request snapshots under `build/reports/agent-prompts/`.

| Dimension | Values |
| --- | --- |
| Model/account | Free local; premium local; ChatGPT; Claude; Gemini |
| Conversation | BlueChat direct with location; direct without location; group; LXMF direct |
| Responsiveness | Default; less responsive; more responsive; silent |
| Feedback integration | Present; absent |

The current native-tool and role configuration is checked against each rendered request:

| Model | Image generation in BlueChat | Image generation in LXMF | Native web search | Instruction roles |
| --- | --- | --- | --- | --- |
| Free/premium local | No | No | No | Developer instructions merged into system |
| ChatGPT | Yes | No | Yes | System and developer |
| Claude | No | No | No | System and developer |
| Gemini | No | No | No | System and developer |

Account-link fields, incoming attachments, pending history, and reply/thread metadata affect user context rather than the core instruction templates; focused tests cover those paths separately. Group and LXMF variants receive no Find My context. Explicit silence and the empty-response retry instruction are also checked at the workflow boundary.

The review examined the distinct rendered instruction text, not just the string templates. The following conflicts or gaps were corrected:

| Finding | Result |
| --- | --- |
| General tool discovery included unavailable native tools | Function-tool discovery explicitly excludes native `image_generation` and `web_search`. Each request states actual native capabilities derived from its selected model and transport. Earlier capability claims are marked as potentially stale. |
| Reactions encouraged, but ingress dropped them and silence retried | Reactions reach contextual interpretation; casual acknowledgement defaults to silence; `NO_RESPONSE` terminates normally. |
| Poll and thread instructions could imply mandatory replies | Both follow the same responsiveness and response-decision policy. Normal text threading happens automatically. |
| More-responsive wording encouraged unnecessary reactions | Helpful participation remains enabled, with incoming acknowledgements silent by default. |
| Less-responsive wording was ambiguous in direct chats | Direct requests in one-to-one chats are distinguished from unaddressed group chatter. |
| Mandatory memory lookup before every question | Lookup is conditional on useful missing factual context. Consent is never inferred from memory. Exact messages/photos use conversation tools. |
| Automatic memory saving conflicted with name-storage consent | The name-consent rule also applies to memory storage; bare reactions are not saved. |
| Praise/feedback instructions could record every tapback | Only substantive feedback is recorded. |
| LXMF omitted account-link and async follow-up guidance | Both transports share those instructions while preserving LXMF delivery limits. |
| Scheduled work could conflict with silent/less-responsive modes | Already authorized scheduled tasks may execute; unchanged pending checks remain quiet. Existing follow-ups are reused, and completed work does not get a new check. |
| Missing tools treated as unclear user intent | Search first, then explain the actual limitation. |
| Catch-up wording prohibited discussing missing coverage | Explain relevant limits naturally without exposing internal retrieval terminology. |
| Find My context could overrule a supplied location or imply freshness | Use a location supplied for the request, check freshness/status, and avoid interrupting unrelated requests. |
| Source material lacked an explicit instruction boundary | History, quotations, attachments, image text, names, locations, and tool results are data; embedded instructions do not replace the current request. |

`AuxiliaryPromptMatrixTest` additionally materializes nine requests under `build/reports/agent-prompts/auxiliary/`: group-memory extraction, question-window answering, and finding reduction on both primary and fallback models; Terms classification; and GIF selection with and without thumbnails. These helpers have no action tools. Their output contracts remain separate from conversational formatting and silence rules. The known local model now receives system-role instructions consistently in the memory helper as well. Terms and GIF prompts explicitly treat supplied content as untrusted data. Existing structured-output and evidence-validation tests remain in place.

No further contradictory directives were identified in the reviewed configurations. These checks establish prompt composition, capability alignment, and deterministic application behavior. They are not a guarantee that every live model will interpret every ambiguous reaction correctly. Arbitrary future model aliases, external provider transformations, production deployment, and live messaging remain outside this local verification.

## Verification

The final focused run passed 339 tests with zero failures, errors, or skips. Formatting and `git diff --check` passed. The 169 rendered request snapshots are also packaged in `build/reports/agent-prompt-snapshots.zip`.

Run formatting with `CI=true nix develop --command ./gradlew spotlessApply`. The focused regression suite includes `AgentPromptMatrixTest`, `AuxiliaryPromptMatrixTest`, `LoadConversationImagesAgentToolTest`, `GetThreadContextAgentToolTest`, `BBMessageAgentTest`, Cadence tests, BlueBubbles transport/client tests, tool-registry tests, response-helper tests, and the three conversation-memory model/client test classes.

An additional existing live `GiphyClientTest` returned no results for its external search and failed its nonempty-result assertion. The GIF prompt itself is covered by an offline request-capture test; the external Giphy service result is recorded separately from these changes.
