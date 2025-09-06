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

## Running the bot

```bash
./gradlew :app-bot:run
```
