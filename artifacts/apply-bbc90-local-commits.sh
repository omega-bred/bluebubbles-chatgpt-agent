#!/usr/bin/env bash
set -euo pipefail

git fetch origin
git checkout -B bbc-90-imessage-ingress-contract-sandbox-v2 origin/bbc-90-imessage-ingress-contract-sandbox-v2
git cherry-pick fd1464c 7392c09
git push origin bbc-90-imessage-ingress-contract-sandbox-v2

echo "BBC-90 commits published to bbc-90-imessage-ingress-contract-sandbox-v2"
