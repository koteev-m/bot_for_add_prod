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

## Perf smoke

Сборка и запуск:
```bash
./gradlew :tools:perf:installDist

# p95 <= 300ms, error-rate <= 1%, 8 воркеров, 30 секунд
./tools/perf/build/install/perf/bin/perf \
  --url=http://localhost:8080 \
  --endpoints=/health,/ready \
  --workers=8 \
  --duration-sec=30 \
  --assert-p95-ms=300 \
  --max-error-rate=0.01

Таргет RPS (например, 200 RPS суммарно):

./tools/perf/build/install/perf/bin/perf \
  --url=http://localhost:8080 \
  --endpoints=/health,/ready \
  --workers=16 \
  --duration-sec=30 \
  --target-rps=200 \
  --assert-p95-ms=300 \
  --max-error-rate=0.01
```


## Local run (Docker)

1. Скопируй `.env.example` → `.env` и заполни **секреты** (минимум `TELEGRAM_BOT_TOKEN`).
2. Подними всё:
   ```bash
   make up
   ```
3. Проверка:
   ```bash
   make health          # OK
   curl -f http://localhost:8080/ready
   ```
4. Логи:
   ```bash
   make logs
   ```
5. Масштабирование (dev):
   ```bash
   docker compose up -d --scale app=2
   docker compose ps
   ```
6. Подключиться к Postgres:
   ```bash
   make psql
   ```

Local Bot API Server (optional):  
Разкомментируй сервис `telegram-bot-api` в `docker-compose.yml`, добавь в `.env` `TELEGRAM_API_ID`/`TELEGRAM_API_HASH`, и запусти `make up`.

---

## Container Smoke Test

### Local
```bash
make smoke
# под капотом: docker build, запуск postgres и app, ретраи /health и /ready до 60 сек
```

CI (GitHub Actions)
- Workflow Container Smoke автоматически собирает образ, поднимает Postgres как сервис, стартует контейнер приложения и проверяет /health и /ready с ретраями.
- Логи приложения выгружаются в шаге Dump app logs on failure при неуспехе.

---

## Deploy (SSH → Docker host)

### Секреты (Repo/Org → Settings → Secrets and variables → Actions → New repository secret)
- SSH_HOST, SSH_USER, SSH_PRIVATE_KEY (PEM), SSH_PORT (опц., 22)
- COMPOSE_PATH (например `/opt/night-concierge`)
- GHCR_USERNAME (ваш GitHub username/robot), GHCR_TOKEN (PAT с read:packages)

### На сервере
- Установить Docker и docker compose (plugin)
- Положить `docker-compose.yml` и `.env` в `${COMPOSE_PATH}`
- Рекомендация: в compose использовать переменную `IMAGE_TAG`:
  ```yaml
  services:
    app:
      image: ghcr.io/<owner>/<repo>/app-bot:${IMAGE_TAG:-latest}
      env_file: .env
      ports: [ "8080:8080" ]
      restart: unless-stopped
  ```

Запуск из Actions (ручной)
- Actions → Deploy (SSH → Docker host) → Run workflow
- Ввести: environment (stage|prod), image_tag (например v1.2.3), service_url (например http://localhost:8080)

Авто-деплой по релизному тегу
- При git tag vX.Y.Z && git push --tags выполнится деплой в prod с image_tag = vX.Y.Z.
