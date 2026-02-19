#!/usr/bin/env bash
set -euo pipefail

APP_BASE_URL="${APP_BASE_URL:-http://localhost:18789}"
WS_URL="${WS_URL:-ws://localhost:18789/ws}"
APP_LOG="${APP_LOG:-target/phase1-verify-app.log}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"

mkdir -p target

cleanup() {
  if [[ -n "${APP_PID:-}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "[1/5] mvn clean compile"
mvn clean compile

echo "[2/5] start app and wait /health"
mvn spring-boot:run >"${APP_LOG}" 2>&1 &
APP_PID=$!

health_ok=0
for ((i=1; i<=WAIT_SECONDS; i++)); do
  if health="$(curl -fsS "${APP_BASE_URL}/health" 2>/dev/null)"; then
    if [[ "${health}" == "OK" ]]; then
      health_ok=1
      break
    fi
  fi

  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    echo "Application exited before health became ready."
    tail -n 80 "${APP_LOG}" || true
    exit 1
  fi

  sleep 1
done

if [[ "${health_ok}" -ne 1 ]]; then
  echo "Timeout waiting for /health."
  tail -n 80 "${APP_LOG}" || true
  exit 1
fi

echo "[3/5] GET /health"
health="$(curl -fsS "${APP_BASE_URL}/health")"
if [[ "${health}" != "OK" ]]; then
  echo "Expected /health to return OK, got: ${health}"
  exit 1
fi
echo "health=OK"

echo "[4/5] POST /v1/chat"
chat_resp="$(curl -fsS -X POST "${APP_BASE_URL}/v1/chat" -H "Content-Type: application/json" -d '{"message":"hello"}')"

if command -v python3 >/dev/null 2>&1; then
  py_bin="python3"
elif command -v python >/dev/null 2>&1; then
  py_bin="python"
else
  py_bin=""
fi

if [[ -n "${py_bin}" ]]; then
  reply="$("${py_bin}" -c 'import json,sys; print(json.loads(sys.stdin.read()).get("reply",""))' <<<"${chat_resp}")"
else
  reply="$(echo "${chat_resp}" | sed -n 's/.*"reply":"\([^"]*\)".*/\1/p')"
fi

if [[ "${reply}" != "hello" ]]; then
  echo "Expected chat reply=hello, got: ${chat_resp}"
  exit 1
fi
echo "chat.reply=hello"

echo "[5/5] WebSocket echo"
ws_verified=0

if [[ -n "${py_bin}" ]]; then
  set +e
  py_ws_out="$("${py_bin}" - "${WS_URL}" <<'PY'
import asyncio
import sys

url = sys.argv[1]
try:
    import websockets
except Exception:
    print("PY_WEBSOCKETS_MISSING")
    raise SystemExit(2)

async def main():
    async with websockets.connect(url) as ws:
        await ws.send("ping")
        msg = await ws.recv()
        print(msg)
        return 0 if msg == "echo: ping" else 1

raise SystemExit(asyncio.run(main()))
PY
)"
  py_ws_code=$?
  set -e

  if [[ "${py_ws_code}" -eq 0 ]]; then
    echo "ws.echo=${py_ws_out}"
    ws_verified=1
  elif [[ "${py_ws_code}" -ne 2 ]]; then
    echo "Python websockets check failed: ${py_ws_out}"
    exit 1
  fi
fi

if [[ "${ws_verified}" -eq 0 ]]; then
  if command -v wscat >/dev/null 2>&1; then
    ws_cmd=(wscat)
  elif command -v npx >/dev/null 2>&1; then
    ws_cmd=(npx -y wscat)
  else
    ws_cmd=()
  fi

  if [[ "${#ws_cmd[@]}" -eq 0 ]]; then
    echo "ws.echo=SKIP (python websockets and wscat unavailable)"
  else
    set +e
    ws_out="$("${ws_cmd[@]}" -c "${WS_URL}" -x ping -w 2 2>&1)"
    ws_code=$?
    set -e
    if [[ "${ws_code}" -ne 0 ]]; then
      echo "wscat check failed:"
      echo "${ws_out}"
      exit 1
    fi
    if ! echo "${ws_out}" | grep -q "echo: ping"; then
      echo "Expected websocket echo 'echo: ping', got:"
      echo "${ws_out}"
      exit 1
    fi
    echo "ws.echo=echo: ping"
  fi
fi

echo "Phase 1 verification passed."
