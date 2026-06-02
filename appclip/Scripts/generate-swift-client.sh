#!/bin/sh
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

if [ "${BLUECHAT_SKIP_SWIFT_CLIENT_GENERATION:-}" = "1" ]; then
  echo "Skipping Swift OpenAPI client generation."
  exit 0
fi

cd "$repo_root"

find_nix() {
  if command -v nix >/dev/null 2>&1; then
    command -v nix
    return 0
  fi

  for candidate in /nix/var/nix/profiles/default/bin/nix /run/current-system/sw/bin/nix; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

if nix_bin="$(find_nix)"; then
  echo "Generating Swift OpenAPI client with nix develop..."
  if env BBAGENT_NIX_SHELL_QUIET=1 CI=true "$nix_bin" develop --command ./gradlew --quiet copySwiftClientToAppClip; then
    exit 0
  fi

  echo "nix develop failed; retrying with ./gradlew directly..."
fi

echo "Generating Swift OpenAPI client with ./gradlew..."
exec env CI=true ./gradlew --quiet copySwiftClientToAppClip
