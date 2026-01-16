# bluebubbles-chatgpt-agent


Local run:

Backend:

```bash
docker run -p 5432:5432 -e "POSTGRES_PASSWORD=postgres"  -e "POSTGRES_USER=postgres" postgres &
./gradlew bootRun
```

Frontend:

```bash
cd frontend
npm run dev
```

# Testing

For running tests locally - you'll almost certainly need 1password cli

brew install --cask 1password-cli
op signin