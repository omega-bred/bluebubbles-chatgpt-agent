#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="${BASE_DIR}/sandbox/imessage"
URL="${IMESSAGE_INGRESS_URL:-http://localhost:8080/api/v1/bluebubbles/messageReceived.message}"

run_case() {
  local name="$1"
  local fixture="$2"
  local expected_status="$3"
  local expected_body_status="${4:-}"
  local expected_error="${5:-}"
  local body_file
  body_file="$(mktemp)"

  local status
  status="$({
    curl -sS -o "${body_file}" -w "%{http_code}" \
      -H 'content-type: application/json' \
      --data-binary "@${fixture}" \
      "${URL}"
  } || true)"

  echo "[${name}] status=${status} expected=${expected_status}"
  cat "${body_file}"
  echo

  if [[ "${status}" != "${expected_status}" ]]; then
    echo "contract check failed for ${name}: unexpected status" >&2
    rm -f "${body_file}"
    exit 1
  fi

  if [[ -n "${expected_body_status}" ]]; then
    if ! grep -q "\"status\"[[:space:]]*:[[:space:]]*\"${expected_body_status}\"" "${body_file}"; then
      echo "contract check failed for ${name}: expected body status=${expected_body_status}" >&2
      rm -f "${body_file}"
      exit 1
    fi
  fi

  if [[ -n "${expected_error}" ]]; then
    if ! grep -q "\"error\"[[:space:]]*:[[:space:]]*\"${expected_error}\"" "${body_file}"; then
      echo "contract check failed for ${name}: expected error=${expected_error}" >&2
      rm -f "${body_file}"
      exit 1
    fi
  fi

  rm -f "${body_file}"
}

run_case "valid_new_message" "${FIXTURE_DIR}/valid-new-message.json" "200" "queued"
run_case "valid_updated_message" "${FIXTURE_DIR}/valid-updated-message.json" "200" "queued"
run_case "typing_indicator_ignored" "${FIXTURE_DIR}/typing-indicator.json" "200" "ok"
run_case "missing_handle" "${FIXTURE_DIR}/invalid-missing-handle.json" "400" "error" "missing_handle"
run_case "missing_chat" "${FIXTURE_DIR}/invalid-missing-chat.json" "400" "error" "missing_chat"
run_case "missing_sender_address" "${FIXTURE_DIR}/invalid-missing-sender-address.json" "400" "error" "missing_sender_address"

echo "iMessage ingress sandbox checks passed"
