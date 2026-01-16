Any time you add or change REST APIs - always update `src/main/resources/openapi.yaml` and run
`./gradlew openApiGenerate` to regenerate server stubs and clients. Never hand-edit generated APIs.

The web app should always use the generated TypeScript client from OpenAPI.

API paths are in the style of `/api/v1/$resourceType/$verb.$resource(s)`.

Formatting should always be run via `./gradlew spotlessApply`.

Testing:
- Unit/integration tests run with an in-memory H2 database by default (see
  `src/test/resources/application.properties`).
- Flyway is enabled for tests; add migrations in `src/main/resources/db/migration`.

Google Calendar:
- OAuth is handled via `/api/v1/gcal/completeOauth.gcal`.
- OAuth state uses an HMAC secret; set `GCAL_OAUTH_STATE_SECRET`.
- Tokens are stored in Postgres (no local token directory).

BlueBubbles:
- Outbound iMessage requires `BLUEBUBBLES_PASSWORD` and a reachable BlueBubbles server base URL.

If you add new configs:
- Prefer environment variables with sensible defaults in `application.properties`.
- Update `manifests/bluebubbles-chatgpt-agent/be-components.yaml` if needed for production deploys.
- Most properties will need to be replicated into the `application.properties` in the `test/resources` folder.
