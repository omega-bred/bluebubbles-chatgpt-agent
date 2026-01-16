# bluebubbles-chatgpt-agent

A ChatGPT agent that integrates with BlueBubbles (iMessage) and optional Google
Calendar. Built with Spring Boot + OpenAPI.

## Features
- BlueBubbles iMessage webhook ingestion + reply tools
- Optional Google Calendar integration (OAuth)
- Optional GIF replies via Giphy
- OpenAI Responses API support (including image generation, web search)
- OpenAPI spec and generated TypeScript client
- Integration with Mem0

## Requirements
- Java 21+ (project uses Gradle toolchains; default is 24)
- Node 20+ (for frontend)
- Postgres (for app data + Google OAuth tokens)
- BlueBubbles server reachable by the agent
- OpenAI API key

## Local development

Backend (Postgres + Spring Boot):

```bash
docker run -p 5432:5432 -e "POSTGRES_PASSWORD=postgres" -e "POSTGRES_USER=postgres" postgres &
./gradlew bootRun
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

## Configuration

All configuration is via environment variables (with defaults in
`src/main/resources/application.properties`).

Required:
- `OPENAI_API_KEY`
- `BLUEBUBBLES_PASSWORD`
- `BLUEBUBBLES_BASE_PATH` (or `bluebubbles.basePath` in properties)
- `POSTGRES_JDBC_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`

Optional (Google Calendar):
- `GCAL_OAUTH_CLIENT_SECRET_PATH` (Google OAuth client JSON)
- `GCAL_OAUTH_REDIRECT_URI` (e.g. `http://localhost:8080/api/v1/gcal/completeOauth.gcal`)
- `GCAL_OAUTH_STATE_SECRET` (HMAC secret for OAuth state JWT)

Optional (Giphy):
- `GIPHY_API_KEY`

## Google Calendar OAuth

The OAuth callback is handled by:
```
/api/v1/gcal/completeOauth.gcal
```

OAuth tokens are stored in Postgres. If you change OAuth client settings, update the redirect URI
to match your deployment.

## OpenAPI / generated clients

When adding or modifying REST APIs:
```bash
./gradlew openApiGenerate
```

The frontend should use the generated TypeScript client in `frontend/src/client`.

## BlueBubbles / iMessage / Apple Account

You almost certainly will want to have a dedicated Mac somewhere running BlueBubbles and very probably its own iMessage/iCloud account. It can be just an email only / no phone number account.
BlueBubbles can be a pain to set up - and requires disable important security features of your Mac. It is best if this is a spare / non-used machine that can be isolated and remain generally unused.

> ⚠️⚠️⚠️⚠️⚠️⚠️ **Important - Apple may or may not do some form of automated bot/spam detection in iMessage. We *DO NOT* recommend using your own personal iCloud account for running this as you may get flagged as spam / could have your account suspended.**

## Testing

Tests run with an in-memory H2 database by default, and Flyway migrations enabled.

```bash
./gradlew test
```

## Notes for open-source use

- Replace any internal URLs in config (BlueBubbles base path, etc.) with your own endpoints.
- Provide your own OAuth credentials and secrets (never commit them).