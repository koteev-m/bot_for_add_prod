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
./gradlew detekt ktlintCheck
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

## Running the bot

```bash
export $(grep -v '^#' .env | xargs)
./gradlew :app-bot:run
```
