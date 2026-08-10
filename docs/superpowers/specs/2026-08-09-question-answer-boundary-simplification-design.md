# Question Answer Boundary Simplification Design

**Date:** 2026-08-09

**Status:** Task 1 implemented and verified; awaiting written-spec review

## Context

The group-history question-answering path currently uses a 1,300-line deterministic output
validator. It grew from a small response boundary into a content-policy engine covering transcript
overlap, English stopwords, English prompt-like text, phone context, dates, URLs, endpoints, payment
cards, and other source-derived heuristics.

Those heuristics are disproportionate for this use case. The requesting account is already
authorized for the selected group and membership interval, retrieval is server-scoped, the answer
model receives opaque evidence aliases instead of message identifiers, and a separate model call
verifies the proposed answer against only its cited authorized evidence. Content-language heuristics
also create inconsistent behavior across languages.

## Goal

Retain only a small, deterministic response-integrity boundary. The boundary protects internal
message identifiers and opaque provider aliases and enforces the answer size contract. It does not
decide which otherwise valid content an authorized answer may contain.

## Non-goals

- Detect or redact PII, payment cards, phone numbers, email addresses, URLs, or endpoints.
- Detect prompt injection or instruction-like text by language-specific patterns.
- Restrict quoting, transcript overlap, source-token reuse, or montage construction.
- Interpret dates, counts, participants, games, scores, or any other semantic domain.
- Revalidate retrieval or model-input budgets already enforced by their owning components.
- Change REST, OpenAPI, tool JSON, authorization, retrieval, model routing, or verification behavior.

## Boundary Contract

`ConversationQuestionAnswerOutputValidator` remains as a package-private, focused component so the
model client and service can share one implementation. Its inputs become:

- proposed answer text;
- submitted real message GUIDs that must not appear in the answer; and
- request-local opaque evidence or finding aliases that must not appear in the answer.

The validator accepts an answer if and only if all of the following are true:

1. The trimmed answer is nonblank.
2. The answer is no longer than 4,000 UTF-16 characters, matching the existing model-output limit.
3. No submitted message GUID appears as a delimiter-bounded identifier in the answer.
4. No opaque evidence or finding alias appears in the answer.
5. Validation completes without malformed-input or arithmetic errors; errors fail closed.

Blank forbidden identifiers are ignored. Message identifiers use Unicode letter-or-digit boundaries
to avoid rejecting an identifier merely because it is a substring of a larger word or identifier.
Opaque aliases are high-entropy request-local values and are rejected on literal occurrence.

The validator no longer accepts submitted source text. Consequently it has no source count, source
character, source token, transcript, identifier-extraction, or language-specific processing.

## Data Flow

1. The answer model returns structured status, answer text, confidence, and opaque citations.
2. The model client maps only submitted opaque citations to their server-side evidence identifiers.
3. The minimal output validator rejects blank/oversized answers and leaked GUIDs or aliases.
4. The service confirms that every cited GUID belongs to the evidence submitted for that model call.
5. A separate tools-disabled model verifies factual support using only the cited authorized evidence.
6. Unsupported or failed verification becomes `INSUFFICIENT_EVIDENCE`; otherwise the answer is
   returned through the existing tool response.

The service continues to apply the same minimal validator again at its trust boundary. This is
intentionally cheap and protects the service from an invalid model-client implementation or test
double without reintroducing content inspection.

## Error Handling

- `requireSafe` preserves the existing `IllegalStateException("unsafe question answer response")`
  contract.
- `isSafe` returns `false` for null, blank, oversized, leaked-identifier, or malformed input.
- The existing service containment converts rejected model output into its normal insufficient or
  unavailable result rather than exposing the rejected text.

## Testing

The change follows a red-green cycle.

New acceptance tests first demonstrate that the current implementation wrongly rejects otherwise
authorized answers containing:

- Spanish, Arabic, Japanese, and mixed-language text;
- email addresses, URLs, endpoints, phone numbers, and payment-card-shaped values;
- instruction-like text; and
- short or long source quotations and repeated source vocabulary.

Retained rejection tests cover:

- null, blank, and whitespace-only answers;
- an answer of exactly 4,000 characters being accepted and 4,001 characters being rejected;
- delimiter-bounded short test GUIDs and normal UUID-like message identifiers;
- dynamically generated evidence aliases and finding aliases;
- identifier substring boundary behavior; and
- null forbidden-identifier collections failing closed.

Focused model-client and service tests, all memory/tool tests, Spring context, formatting,
compilation, diff checks, and the production zero-domain-term scan must pass. No OpenAPI generation
change is expected.

## Expected Result

The production validator becomes small enough to audit directly and behaves consistently across
languages. Authorization and factual grounding remain enforced by server-side evidence selection,
citation membership, and cited-evidence verification rather than by content heuristics.
