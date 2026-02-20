#!/usr/bin/env bash
set -euo pipefail

# Prerequisites
for cmd in curl mvn mkfifo; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Required command not found: ${cmd}"; exit 1
  fi
done

APP_BASE_URL="${APP_BASE_URL:-http://localhost:18789}"
APP_LOG="${APP_LOG:-target/phase2-verify-app.log}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"

mkdir -p target

cleanup() {
  if [[ -n "${APP_PID:-}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
  rm -f "${FIFO:-}"
}
trap cleanup EXIT

echo "[1/5] mvn clean compile"
mvn clean compile -q

echo "[2/5] start app with CLI channel"
FIFO="target/.phase2-stdin-$$"
mkfifo "${FIFO}"
mvn spring-boot:run < "${FIFO}" >"${APP_LOG}" 2>&1 &
APP_PID=$!

# keep fifo open for writing
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

echo "[3/5] GET /health"
health="$(curl -fsS "${APP_BASE_URL}/health")"
if [[ "${health}" != "OK" ]]; then
  echo "Expected OK, got: ${health}"; exit 1
fi
echo "health=OK"

echo "[4/5] CLI channel started"
if grep -q "JAVAClaw CLI" "${APP_LOG}"; then
  echo "cli.prompt=OK"
else
  echo "CLI prompt not found in log"; exit 1
fi

echo "[5/5] /quit exits cleanly"
echo "/quit" >&3
exec 3>&-
sleep 3
if kill -0 "${APP_PID}" 2>/dev/null; then
  echo "App did not exit after /quit"; exit 1
fi
echo "quit=OK"

echo "Phase 2 verification passed."
