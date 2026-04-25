# BBC-90 publish steps

## Artifact

- Patch: `artifacts/BBC-90-fd1464c.patch`
- Source commit: `fd1464c`
- Branch: `bbc-90-imessage-ingress-contract-sandbox`

## Apply and publish from an authenticated clone

```bash
git checkout main
git pull --ff-only
git checkout -B bbc-90-imessage-ingress-contract-sandbox
git config user.name "Your Name"
git config user.email "you@example.com"
git am artifacts/BBC-90-fd1464c.patch
git push -u origin bbc-90-imessage-ingress-contract-sandbox
```

## Open draft PR

```bash
gh pr create \
  --base main \
  --head bbc-90-imessage-ingress-contract-sandbox \
  --title "Define iMessage ingress contract and sandbox harness" \
  --body "Implements BBC-90 ingress contract, queue-backed pipeline, replay sandbox fixtures, and retry/error documentation." \
  --draft
```
