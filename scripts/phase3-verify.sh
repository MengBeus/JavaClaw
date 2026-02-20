#!/usr/bin/env bash
set -euo pipefail

# Prerequisites
for cmd in curl mvn mkfifo psql; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Required command not found: ${cmd}"; exit 1
  fi
done

APP_BASE_URL="${APP_BASE_URL:-http://localhost:18789}"
APP_LOG="${APP_LOG:-target/phase3-verify-app.log}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
DB_URL="${DB_URL:-postgresql://javaclaw:javaclaw@localhost:5432/javaclaw}"

mkdir -p target

cleanup() {
  if [[ -n "${APP_PID:-}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
  rm -f "${FIFO:-}"
}
trap cleanup EXIT

echo "[1/7] mvn clean compile"
mvn clean compile -q

echo "[2/7] start app"
FIFO="target/.phase3-stdin-$$"
mkfifo "${FIFO}"
mvn spring-boot:run < "${FIFO}" >"${APP_LOG}" 2>&1 &
APP_PID=$!
exec 3>"${FIFO}"

health_ok=0
for ((i=1; i<=WAIT_SECONDS; i++)); do
  if curl -fsS "${APP_BASE_URL}/health" >/dev/null 2>&1; then
    health_ok=1; break
  fi
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    echo "App exited before health ready."
    tail -n 40 "${APP_LOG}" || true
    exit 1
  fi
  sleep 1
done

if [[ "${health_ok}" -ne 1 ]]; then
  echo "Timeout waiting for /health."
  tail -n 40 "${APP_LOG}" || true
  exit 1
fi

echo "[3/7] GET /health"
health="$(curl -fsS "${APP_BASE_URL}/health")"
if [[ "${health}" != "OK" ]]; then
  echo "Expected OK, got: ${health}"; exit 1
fi
echo "health=OK"

echo "[4/7] CLI channel started"
if grep -q "JAVAClaw CLI" "${APP_LOG}"; then
  echo "cli.prompt=OK"
else
  echo "CLI prompt not found in log"; exit 1
fi

echo "[5/7] Flyway migrations (sessions table)"
sessions_exists="$(psql "${DB_URL}" -tAc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='sessions';" 2>/dev/null || echo "0")"
if [[ "${sessions_exists}" -ge 1 ]]; then
  echo "table.sessions=OK"
else
  echo "sessions table not found"; exit 1
fi

echo "[6/7] Flyway migrations (chat_messages table)"
messages_exists="$(psql "${DB_URL}" -tAc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='chat_messages';" 2>/dev/null || echo "0")"
if [[ "${messages_exists}" -ge 1 ]]; then
  echo "table.chat_messages=OK"
else
  echo "chat_messages table not found"; exit 1
fi

echo "[7/7] /quit exits cleanly"
echo "/quit" >&3
exec 3>&-
sleep 3
if kill -0 "${APP_PID}" 2>/dev/null; then
  echo "App did not exit after /quit"; exit 1
fi
echo "quit=OK"

echo "Phase 3 verification passed."
