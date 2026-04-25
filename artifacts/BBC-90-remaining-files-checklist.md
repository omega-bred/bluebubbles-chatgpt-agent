# BBC-90 Remaining Publication Checklist

Target PR branch: `bbc-90-imessage-ingress-contract-sandbox-v2`

## Already published via connector

- [x] `artifacts/BBC-90-publish-instructions.md`
- [x] `artifacts/apply-bbc90-local-commits.sh`
- [x] `src/main/java/io/breland/bbagent/server/controllers/BluebubblesWebhookController.java`
- [x] `src/main/java/io/breland/bbagent/server/agent/persistence/MessageIngressEventEntity.java`
- [x] `src/main/java/io/breland/bbagent/server/agent/persistence/MessageIngressEventRepository.java`
- [x] `src/main/resources/db/migration/V9__message_ingress_events.sql`

## Still expected from local commits `fd1464c` + `7392c09`

- [ ] `src/main/java/io/breland/bbagent/server/agent/MessageIngressPipeline.java`
- [ ] `src/main/resources/openapi.yaml`
- [ ] `src/main/resources/application.properties`
- [ ] `src/test/resources/application.properties`
- [ ] `src/test/java/io/breland/bbagent/server/agent/MessageIngressPipelineTest.java`
- [ ] `src/test/java/io/breland/bbagent/server/controllers/BluebubblesWebhookControllerTest.java`
- [ ] `docs/imessage-ingress-contract.md`
- [ ] `docs/contracts/imessage-ingress/v1/canonical-ingress-event.schema.json`
- [ ] `scripts/imessage-ingress-sandbox.sh`
- [ ] `scripts/sandbox/imessage/valid-new-message.json`
- [ ] `scripts/sandbox/imessage/valid-updated-message.json`
- [ ] `scripts/sandbox/imessage/typing-indicator.json`
- [ ] `scripts/sandbox/imessage/invalid-missing-handle.json`
- [ ] `scripts/sandbox/imessage/invalid-missing-chat.json`
- [ ] `scripts/sandbox/imessage/invalid-missing-sender-address.json`
- [ ] `artifacts/BBC-90-fd1464c.patch`
- [ ] `artifacts/BBC-90-fd1464c-files.tar.gz`
- [ ] `artifacts/BBC-90-bundle-apply-instructions.md`

## Preferred completion path

```bash
bash artifacts/apply-bbc90-local-commits.sh
```

Then verify all checklist items are present in PR #65 diff.
