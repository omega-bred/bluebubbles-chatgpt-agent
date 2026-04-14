#!/usr/bin/env zsh
set -euo pipefail

if [[ -f "${HOME}/.zshrc" ]]; then
  source "${HOME}/.zshrc"
fi

SERVER="${KEYCLOAK_SERVER:-https://keycloak.bre.land}"
REALM="${KEYCLOAK_REALM:-bbagent}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-bbagent-web}"
APPLY_THEME="${APPLY_KEYCLOAK_THEME:-false}"

if ! command -v kcadm >/dev/null 2>&1; then
  echo "kcadm was not found. Source ~/.zshrc or run this from a shell where the kcadm alias/function is loaded." >&2
  exit 1
fi

if ! kcadm get serverinfo --server "${SERVER}" >/dev/null 2>&1; then
  echo "Keycloak admin session is not authenticated. Run: kcadm config credentials --server ${SERVER} --realm master --user <admin>" >&2
  exit 1
fi

if ! kcadm get "realms/${REALM}" --server "${SERVER}" >/dev/null 2>&1; then
  kcadm create realms \
    --server "${SERVER}" \
    -s "realm=${REALM}" \
    -s enabled=true \
    -s registrationAllowed=true \
    -s resetPasswordAllowed=true \
    -s rememberMe=true \
    -s loginWithEmailAllowed=true \
    -s duplicateEmailsAllowed=false \
    -s displayName="Chat iMessage"
else
  kcadm update "realms/${REALM}" \
    --server "${SERVER}" \
    -s enabled=true \
    -s registrationAllowed=true \
    -s resetPasswordAllowed=true \
    -s rememberMe=true \
    -s loginWithEmailAllowed=true \
    -s duplicateEmailsAllowed=false \
    -s displayName="Chat iMessage"
fi

client_id="$(
  kcadm get clients \
    --server "${SERVER}" \
    -r "${REALM}" \
    -q "clientId=${CLIENT_ID}" \
    --fields id \
    --format csv \
    --noquotes 2>/dev/null \
    | tail -n +2 \
    | head -n 1
)"

client_settings=(
  -s "clientId=${CLIENT_ID}"
  -s enabled=true
  -s publicClient=true
  -s standardFlowEnabled=true
  -s implicitFlowEnabled=false
  -s directAccessGrantsEnabled=false
  -s serviceAccountsEnabled=false
  -s 'redirectUris=["https://chatagent.bre.land/*","http://localhost:8080/*","http://localhost:5174/*"]'
  -s 'webOrigins=["https://chatagent.bre.land","http://localhost:8080","http://localhost:5174"]'
  -s 'attributes."pkce.code.challenge.method"=S256'
)

if [[ -z "${client_id}" ]]; then
  kcadm create clients --server "${SERVER}" -r "${REALM}" "${client_settings[@]}"
else
  kcadm update "clients/${client_id}" --server "${SERVER}" -r "${REALM}" "${client_settings[@]}"
fi

if [[ "${APPLY_THEME}" == "true" ]]; then
  kcadm update "realms/${REALM}" --server "${SERVER}" -s loginTheme=bbagent
fi

echo "Keycloak realm '${REALM}' and client '${CLIENT_ID}' are configured."
if [[ "${APPLY_THEME}" != "true" ]]; then
  echo "Theme files are in keycloak/themes/bbagent. Install them on Keycloak, then rerun with APPLY_KEYCLOAK_THEME=true."
fi
