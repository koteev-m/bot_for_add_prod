#!/usr/bin/env bash
set -euo pipefail

APP_IMAGE="${APP_IMAGE:-app-bot:smoke}"
APP_NAME="${APP_NAME:-bot_app_smoke}"
PG_NAME="${PG_NAME:-bot_pg_smoke}"
PG_PORT="${PG_PORT:-5432}"
APP_PORT="${APP_PORT:-8080}"

# Тестовые ENV (фиктивные значения)
DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/botdb"
DB_USER="botuser"
DB_PASS="botpass"
TELEGRAM_TOKEN="000000:TEST_TOKEN"
OWNER_ID="0"

cleanup() {
  echo ">>> Cleanup"
  docker rm -f "${APP_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${PG_NAME}"  >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo ">>> Build app image: ${APP_IMAGE}"
docker build -t "${APP_IMAGE}" .

echo ">>> Run Postgres: ${PG_NAME}"
docker rm -f "${PG_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${PG_NAME}" \
  -e POSTGRES_DB=botdb \
  -e POSTGRES_USER="${DB_USER}" \
  -e POSTGRES_PASSWORD="${DB_PASS}" \
  -p "${PG_PORT}:5432" \
  --health-cmd="pg_isready -U ${DB_USER} -d botdb" \
  --health-interval=5s --health-timeout=3s --health-retries=10 \
  postgres:16-alpine

# Ждём Postgres (до 60 сек)
echo ">>> Waiting for Postgres to be healthy..."
for i in {1..60}; do
  status="$(docker inspect -f '{{.State.Health.Status}}' "${PG_NAME}" || echo "unknown")"
  if [[ "${status}" == "healthy" ]]; then
    echo ">>> Postgres is healthy"
    break
  fi
  sleep 1
  if [[ $i -eq 60 ]]; then
    echo "!!! Postgres did not become healthy in time"
    docker logs "${PG_NAME}" || true
    exit 1
  fi

done

echo ">>> Run app: ${APP_NAME}"
docker rm -f "${APP_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${APP_NAME}" \
  -p "${APP_PORT}:8080" \
  -e DATABASE_URL="${DB_URL}" \
  -e DATABASE_USER="${DB_USER}" \
  -e DATABASE_PASSWORD="${DB_PASS}" \
  -e TELEGRAM_BOT_TOKEN="${TELEGRAM_TOKEN}" \
  -e OWNER_TELEGRAM_ID="${OWNER_ID}" \
  "${APP_IMAGE}"

# Ретраи /health и /ready (до 60 сек)
echo ">>> Probing /health and /ready"
for i in {1..60}; do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/health" >/dev/null 2>&1; then
    if curl -fsS "http://127.0.0.1:${APP_PORT}/ready"  >/dev/null 2>&1; then
      echo ">>> Smoke OK"
      exit 0
    fi
  fi
  sleep 1

done

echo "!!! Smoke test FAILED — dumping logs"
echo "----- app logs -----"
docker logs "${APP_NAME}" || true
echo "----- postgres logs -----"
docker logs "${PG_NAME}" || true
exit 1
