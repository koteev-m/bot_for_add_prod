# Bot Monorepo

This repository contains a Kotlin multi-module project built with Gradle Kotlin DSL.

## Modules

- `app-bot` – Ktor HTTP service handling Telegram webhook.
- `core-domain` – Domain models and result types.
- `core-data` – Database access using Exposed, HikariCP and Flyway.
- `core-telemetry` – Micrometer metrics and health endpoints.
- `core-security` – RBAC and request signature utilities.
- `core-testing` – Shared test fixtures.

## Building

```bash
./gradlew build
./gradlew staticCheck
```

## Configuration

Copy `.env.example` to `.env` and provide values for all variables:

```bash
cp .env.example .env
```

Required variables:

- `BOT_TOKEN`
- `WEBHOOK_SECRET_TOKEN`
- `DATABASE_URL`
- `DATABASE_USER`
- `DATABASE_PASSWORD`
- `OWNER_TELEGRAM_ID`

The application reads secrets only from the environment. To switch between
configurations set `APP_ENV` (`dev` is default, set `prod` for production).

## Telegram bot modes

Set `RUN_MODE=webhook` to start the Ktor server and expose `/webhook` for Telegram updates.
Use `/telegram/setup-webhook` to call Bot API `setWebhook` with the URL `WEBHOOK_BASE_URL/webhook`.

Set `RUN_MODE=polling` to start long polling via `PollingMain`.
`getUpdates` and `setWebhook` are mutually exclusive.

When using a local Bot API server (`LOCAL_BOT_API_URL`), the bot will send requests
to that base URL instead of `https://api.telegram.org`.

## Running the bot

```bash
export $(grep -v '^#' .env | xargs)
./gradlew :app-bot:run
```

## Testing

Run the full test suite:

```bash
./gradlew test
```

Run only integration smoke tests:

```bash
./gradlew :core-testing:test --tests "*SmokeTest*"
```

Enable verbose logging:

```bash
./gradlew test -i
```

Test reports are generated under `build/reports/tests/test/index.html`.

